package com.ilkeiapps.skyworx.notulenai.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.NormalizedElement;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.entity.DocumentElement;
import com.ilkeiapps.skyworx.notulenai.entity.DocumentProcessingJob;
import com.ilkeiapps.skyworx.notulenai.entity.KnowledgeDocument;
import com.ilkeiapps.skyworx.notulenai.entity.QDocumentProcessingJob;
import com.ilkeiapps.skyworx.notulenai.utility.Utility;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Document-processing pipeline. Phase 1 implements the extraction half: download from MinIO →
 * Apache Tika extraction → normalization → persist {@code DocumentElement} records, advancing the
 * document and job status through the {@code StatusProcess} master (PENDING → PROCESSING; FAILED on
 * error). Chunking, embedding generation, and the final COMPLETED transition are added in Phase 2;
 * until then a successfully-extracted document remains in {@code PROCESSING}.
 * <p>
 * Extraction and normalization (the likely failure points) run before any element write, so a failure
 * never leaves partial elements behind; existing elements are replaced only after a successful
 * normalization, making reprocessing idempotent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeProcessingService {

	private static final String STATUS_PROCESSING = "PROCESSING";
	private static final String STATUS_FAILED = "FAILED";

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;
	private final StatusService statusService;
	private final MinioStorageService storageService;
	private final TikaExtractionService tikaExtractionService;
	private final DocumentNormalizationService normalizationService;

	@Transactional
	public ApiResponse<String> extractAndNormalize(Long documentId) {
		var wrap = new ApiResponse<String>();
		wrap.setStatus(false);

		var document = documentId == null ? null : this.em.find(KnowledgeDocument.class, documentId);
		if (document == null) {
			wrap.setMessage(Utility.getErrorMessage("knowledge.document.item_not_found"));
			return wrap;
		}
		var job = latestJob(documentId);
		if (job == null) {
			wrap.setMessage(Utility.getErrorMessage("knowledge.document.job_not_found"));
			return wrap;
		}

		var processing = statusService.getStatusProcessByCode(STATUS_PROCESSING);
		var now = Instant.now();
		job.setStatus(processing);
		job.setStartedAt(now);
		job.setAttemptCount(job.getAttemptCount() == null ? 1 : job.getAttemptCount() + 1);
		job.setUpdatedAt(now);
		document.setProcessingStatus(processing);
		document.setProcessingError(null);
		document.setUpdatedAt(now);
		this.em.flush();

		try {
			byte[] data;
			try (var in = storageService.openStream(document.getStorageObjectKey())) {
				data = in.readAllBytes();
			}

			var extraction = tikaExtractionService.extract(data, document.getContentType());
			var title = extraction.title() != null && !extraction.title().isBlank()
					? extraction.title() : document.getDocumentTitle();
			var normalized = normalizationService.normalize(extraction.xhtml(), title);

			replaceElements(document, normalized);

			wrap.insertNewData("Extracted " + normalized.size() + " document elements");
			wrap.setStatus(true);
			return wrap;
		} catch (Exception e) {
			log.error("extraction failed for document {}", documentId, e);
			markFailed(document, job, e.getMessage());
			wrap.setMessage(Utility.getErrorMessage("knowledge.document.storage_failed"));
			return wrap;
		}
	}

	private void replaceElements(KnowledgeDocument document, List<NormalizedElement> normalized) {
		this.em.createQuery("delete from DocumentElement e where e.document.id = :id")
				.setParameter("id", document.getId())
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
			// Parents always precede children in sequence order, so the parent is already persisted.
			if (ne.getParentIndex() >= 0 && ne.getParentIndex() < persisted.size()) {
				element.setParent(persisted.get(ne.getParentIndex()));
			}
			this.em.persist(element);
			persisted.add(element);
		}
		this.em.flush();
	}

	private void markFailed(KnowledgeDocument document, DocumentProcessingJob job, String error) {
		var failed = statusService.getStatusProcessByCode(STATUS_FAILED);
		var now = Instant.now();
		var trimmed = error != null && error.length() > 4000 ? error.substring(0, 4000) : error;
		document.setProcessingStatus(failed);
		document.setProcessingError(trimmed);
		document.setUpdatedAt(now);
		job.setStatus(failed);
		job.setErrorMessage(trimmed);
		job.setCompletedAt(now);
		job.setUpdatedAt(now);
		this.em.flush();
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
