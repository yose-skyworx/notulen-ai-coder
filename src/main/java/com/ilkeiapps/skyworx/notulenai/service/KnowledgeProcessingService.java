package com.ilkeiapps.skyworx.notulenai.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.ChunkDraft;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.NormalizedElement;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.config.KnowledgeProperties;
import com.ilkeiapps.skyworx.notulenai.entity.DocumentChunk;
import com.ilkeiapps.skyworx.notulenai.entity.DocumentChunkElement;
import com.ilkeiapps.skyworx.notulenai.entity.DocumentElement;
import com.ilkeiapps.skyworx.notulenai.entity.DocumentProcessingJob;
import com.ilkeiapps.skyworx.notulenai.entity.KnowledgeDocument;
import com.ilkeiapps.skyworx.notulenai.entity.QDocumentProcessingJob;
import com.ilkeiapps.skyworx.notulenai.utility.Utility;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Full document-processing pipeline: download → conversion to structured elements → normalization →
 * persist {@code DocumentElement}s → structure-aware chunking → BGE-M3 embeddings → persist
 * {@code DocumentChunk} (+ pgvector embedding) + {@code DocumentChunkElement}. On success the document
 * and job become {@code COMPLETED} and the document becomes the current version; on failure both move
 * to {@code FAILED} with the error recorded.
 * <p>
 * Transaction design: the remote calls (conversion, embeddings) run <em>outside</em> any DB transaction, and
 * each persistence/status step commits in its own short transaction via {@link TransactionTemplate}.
 * This means a late failure (e.g. a bad embedding or a missing pgvector type) does not roll back the
 * elements already written, and the {@code FAILED} status + message are always persisted so the cause
 * is visible in {@code knowledge_documents.processing_error} / {@code document_processing_jobs}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeProcessingService {

	private static final String STATUS_PROCESSING = "PROCESSING";
	private static final String STATUS_COMPLETED = "COMPLETED";
	private static final String STATUS_FAILED = "FAILED";

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;
	private final StatusService statusService;
	private final DocumentStorageService storageService;
	private final TikaExtractionService tikaExtractionService;
	private final DocumentNormalizationService normalizationService;
	private final DocumentChunkingService chunkingService;
	private final EmbeddingService embeddingService;
	private final KnowledgeProperties properties;
	private final TransactionTemplate tx;

	/** Context captured in the opening transaction, used by the (non-transactional) remote steps. */
	private record DocContext(String objectKey, String contentType, String title) {
	}

	public ApiResponse<String> process(Long documentId) {
		var wrap = new ApiResponse<String>();
		wrap.setStatus(false);
		var start = System.currentTimeMillis();
		log.info("process document {}: START", documentId);

		DocContext ctx;
		try {
			ctx = tx.execute(status -> beginProcessing(documentId));
		} catch (Exception e) {
			log.error("process document {}: could not begin", documentId, e);
			wrap.setMessage(Utility.getErrorMessage("knowledge.document.storage_failed"));
			return wrap;
		}
		if (ctx == null) {
			wrap.setMessage(Utility.getErrorMessage("knowledge.document.item_not_found"));
			return wrap;
		}

		try {
			log.info("process document {}: downloading storage object '{}'", documentId, ctx.objectKey());
			byte[] data = readBytes(ctx.objectKey());
			log.info("process document {}: downloaded {} bytes (contentType={})", documentId, data.length, ctx.contentType());

			var extraction = tikaExtractionService.extract(data);
			log.info("process document {}: conversion returned {} element(s)", documentId,
					extraction.elements().size());

			tx.executeWithoutResult(status -> persistConversionMetadata(documentId, extraction.metadata()));

			var normalized = normalizationService.normalize(extraction, ctx.title());
			log.info("process document {}: normalized into {} elements", documentId, normalized.size());

			tx.executeWithoutResult(status -> persistElements(documentId, normalized));
			log.info("process document {}: persisted {} elements", documentId, normalized.size());

			var drafts = chunkingService.chunk(normalized);
			log.info("process document {}: chunked into {} chunks", documentId, drafts.size());

			var texts = new ArrayList<String>(drafts.size());
			for (ChunkDraft d : drafts) {
				texts.add(d.getContent());
			}
			var embeddings = embeddingService.embed(texts);
			log.info("process document {}: embedded {} chunks (dimension={})", documentId, embeddings.size(),
					embeddings.isEmpty() ? 0 : embeddings.getFirst().length);

			int chunkCount = tx.execute(status -> persistChunks(documentId, drafts, embeddings));
			log.info("process document {}: persisted {} chunks", documentId, chunkCount);

			tx.executeWithoutResult(status -> completeProcessing(documentId));
			log.info("process document {}: COMPLETED ({} elements, {} chunks) in {} ms", documentId,
					normalized.size(), chunkCount, System.currentTimeMillis() - start);

			wrap.insertNewData("Processed document: " + normalized.size() + " elements, " + chunkCount + " chunks");
			wrap.setStatus(true);
			return wrap;
		} catch (Exception e) {
			log.error("process document {}: FAILED", documentId, e);
			final String message = e.getMessage();
			try {
				tx.executeWithoutResult(status -> failProcessing(documentId, message));
			} catch (Exception inner) {
				log.error("process document {}: could not record FAILED status", documentId, inner);
			}
			wrap.setMessage(Utility.getErrorMessage("knowledge.document.storage_failed"));
			return wrap;
		}
	}

	// ---------------------------------------------------------------- transactional steps

	private DocContext beginProcessing(Long documentId) {
		var document = documentId == null ? null : this.em.find(KnowledgeDocument.class, documentId);
		if (document == null) {
			log.warn("process document {}: document not found", documentId);
			return null;
		}
		var job = latestJob(documentId);
		if (job == null) {
			log.warn("process document {}: no processing job found", documentId);
			return null;
		}
		var processing = statusService.getStatusProcessByCode(STATUS_PROCESSING);
		if (processing == null) {
			log.error("process document {}: status '{}' missing — run POST /api/init/master", documentId, STATUS_PROCESSING);
		}
		var now = Instant.now();
		job.setStatus(processing);
		job.setStartedAt(now);
		job.setAttemptCount(job.getAttemptCount() == null ? 1 : job.getAttemptCount() + 1);
		job.setUpdatedAt(now);
		document.setProcessingStatus(processing);
		document.setProcessingError(null);
		document.setUpdatedAt(now);
		this.em.flush();
		return new DocContext(document.getStorageObjectKey(), document.getContentType(), document.getDocumentTitle());
	}

	/**
	 * Records the converter's detection report on the document in its own committed transaction, before
	 * normalization and embedding run, so a later failure still leaves behind what the service saw
	 * (page count, OCR pages, duration) for diagnosis.
	 */
	private void persistConversionMetadata(Long documentId, Map<String, Object> conversionMetadata) {
		var document = this.em.find(KnowledgeDocument.class, documentId);
		document.setConversionMetadata(conversionMetadata);
		document.setUpdatedAt(Instant.now());
		this.em.flush();
	}

	private void persistElements(Long documentId, List<NormalizedElement> normalized) {
		var document = this.em.find(KnowledgeDocument.class, documentId);
		deleteExistingChunks(documentId);
		this.em.createQuery("delete from DocumentElement e where e.document.id = :id")
				.setParameter("id", documentId)
				.executeUpdate();

		var now = Instant.now();
		var persisted = new ArrayList<DocumentElement>(normalized.size());
		for (NormalizedElement ne : normalized) {
			var element = new DocumentElement();
			element.setDocument(document);
			element.setElementType(ne.getElementType());
			element.setSequenceNumber(ne.getSequenceNumber());
			element.setHeadingLevel(ne.getHeadingLevel());
			element.setPageNumber(ne.getPageNumber());
			element.setSlideNumber(ne.getSlideNumber());
			element.setElementPath(ne.getElementPath());
			element.setContent(ne.getContent());
			element.setStructuredContent(ne.getStructuredContent());
			element.setMetadata(ne.getMetadata());
			element.setCreatedAt(now);
			if (ne.getParentIndex() >= 0 && ne.getParentIndex() < persisted.size()) {
				element.setParent(persisted.get(ne.getParentIndex()));
			}
			this.em.persist(element);
			persisted.add(element);
		}
		this.em.flush();
	}

	private int persistChunks(Long documentId, List<ChunkDraft> drafts, List<float[]> embeddings) {
		if (drafts.isEmpty()) {
			log.warn("process document {}: no chunks produced", documentId);
			return 0;
		}
		if (embeddings.size() != drafts.size()) {
			throw new IllegalStateException("Embedding count " + embeddings.size()
					+ " does not match chunk count " + drafts.size());
		}
		var expectedDim = properties.getEmbedding().getDimension();

		var document = this.em.find(KnowledgeDocument.class, documentId);
		var elements = this.em.createQuery(
						"select e from DocumentElement e where e.document.id = :id order by e.sequenceNumber",
						DocumentElement.class)
				.setParameter("id", documentId)
				.getResultList();
		var bySequence = new HashMap<Integer, DocumentElement>();
		for (DocumentElement e : elements) {
			bySequence.put(e.getSequenceNumber(), e);
		}

		var now = Instant.now();
		var project = document.getProject();
		for (var i = 0; i < drafts.size(); i++) {
			var draft = drafts.get(i);
			var embedding = embeddings.get(i);
			if (embedding == null || embedding.length != expectedDim) {
				throw new IllegalStateException("Embedding dimension "
						+ (embedding == null ? "null" : embedding.length) + " != expected " + expectedDim);
			}

			var chunk = new DocumentChunk();
			chunk.setProject(project);
			chunk.setDocument(document);
			chunk.setChunkIndex(i);
			chunk.setContent(draft.getContent());
			chunk.setTokenCount(draft.getTokenCount());
			chunk.setPageStart(draft.getPageStart());
			chunk.setPageEnd(draft.getPageEnd());
			chunk.setSlideStart(draft.getSlideStart());
			chunk.setSlideEnd(draft.getSlideEnd());
			chunk.setEmbedding(embedding);
			var meta = new LinkedHashMap<String, Object>();
			meta.put("elementCount", draft.getElementSequenceIndices().size());
			chunk.setMetadata(meta);
			chunk.setCreatedAt(now);
			this.em.persist(chunk);

			var order = 0;
			for (Integer seqIndex : draft.getElementSequenceIndices()) {
				var element = seqIndex == null ? null : bySequence.get(seqIndex);
				if (element == null) {
					continue;
				}
				var link = new DocumentChunkElement();
				link.setChunk(chunk);
				link.setElement(element);
				link.setElementOrder(order++);
				link.setCreatedAt(now);
				this.em.persist(link);
			}
		}
		this.em.flush();
		return drafts.size();
	}

	private void completeProcessing(Long documentId) {
		var document = this.em.find(KnowledgeDocument.class, documentId);
		var job = latestJob(documentId);
		var completed = statusService.getStatusProcessByCode(STATUS_COMPLETED);
		var now = Instant.now();

		if (document.getLogicalDocumentId() != null) {
			this.em.createQuery("update KnowledgeDocument d set d.isCurrent = false "
							+ "where d.logicalDocumentId = :lid and d.project.id = :pid and d.id <> :id")
					.setParameter("lid", document.getLogicalDocumentId())
					.setParameter("pid", document.getProject().getId())
					.setParameter("id", documentId)
					.executeUpdate();
		}

		document.setProcessingStatus(completed);
		document.setIsCurrent(Boolean.TRUE);
		document.setProcessedAt(now);
		document.setUpdatedAt(now);
		if (job != null) {
			job.setStatus(completed);
			job.setCompletedAt(now);
			job.setUpdatedAt(now);
		}
		this.em.flush();
	}

	private void failProcessing(Long documentId, String error) {
		var document = this.em.find(KnowledgeDocument.class, documentId);
		var job = latestJob(documentId);
		var failed = statusService.getStatusProcessByCode(STATUS_FAILED);
		var now = Instant.now();
		var trimmed = error != null && error.length() > 4000 ? error.substring(0, 4000) : error;
		if (document != null) {
			document.setProcessingStatus(failed);
			document.setProcessingError(trimmed);
			document.setUpdatedAt(now);
		}
		if (job != null) {
			job.setStatus(failed);
			job.setErrorMessage(trimmed);
			job.setCompletedAt(now);
			job.setUpdatedAt(now);
		}
		this.em.flush();
	}

	// ---------------------------------------------------------------- helpers

	private byte[] readBytes(String objectKey) {
		try (var in = storageService.openStream(objectKey)) {
			return in.readAllBytes();
		} catch (Exception e) {
			throw new IllegalStateException("Could not read stored document '" + objectKey + "'", e);
		}
	}

	private void deleteExistingChunks(Long documentId) {
		this.em.createQuery("delete from DocumentChunkElement dce where dce.chunk.id in "
						+ "(select c.id from DocumentChunk c where c.document.id = :id)")
				.setParameter("id", documentId)
				.executeUpdate();
		this.em.createQuery("delete from DocumentChunk c where c.document.id = :id")
				.setParameter("id", documentId)
				.executeUpdate();
	}

	private DocumentProcessingJob latestJob(Long documentId) {
		var qj = new QDocumentProcessingJob("j");
		var rows = configBuilder.create(em, DocumentProcessingJob.class).from(DocumentProcessingJob.class, qj.getMetadata().getName())
				.where(qj.document.id.toString()).eq(documentId)
				.orderByDesc(qj.id.toString())
				.setMaxResults(1)
				.getResultList();
		return rows.isEmpty() ? null : rows.getFirst();
	}
}
