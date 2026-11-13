package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.KnowledgeSearchResult;
import com.ilkeiapps.skyworx.notulenai.config.KnowledgeProperties;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Project-scoped semantic retrieval over {@code document_chunks}. The query is embedded with the same
 * BGE-M3 model used at ingestion, then matched with a native pgvector cosine query
 * ({@code <=>} operator). Project isolation, current-version, and successful-processing filters are
 * applied <em>inside the SQL</em> (never as a post-filter), so a caller can only ever see chunks of
 * the current, COMPLETED documents of the one project it passes.
 * <p>
 * The embedding call is a remote HTTP request and therefore runs <em>outside</em> any database
 * transaction; the subsequent read-only native query runs on its own short unit of work. Returns
 * detached {@link KnowledgeSearchResult} DTOs, never managed entities.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentRetrievalService {

	private static final String STATUS_COMPLETED = "COMPLETED";

	private static final String SEARCH_SQL = """
			SELECT c.id              AS chunk_id,
			       c.chunk_index     AS chunk_index,
			       c.content         AS content,
			       c.page_start      AS page_start,
			       c.page_end        AS page_end,
			       c.slide_start     AS slide_start,
			       c.slide_end       AS slide_end,
			       d.id              AS document_id,
			       d.logical_document_id AS logical_document_id,
			       d.version_number  AS version_number,
			       d.document_title  AS document_title,
			       1 - (c.embedding <=> CAST(:qv AS vector)) AS score
			FROM document_chunks c
			JOIN knowledge_documents d ON d.id = c.document_id
			WHERE c.project_id = :projectId
			  AND d.is_current = true
			  AND d.processing_status_id = :statusId
			ORDER BY c.embedding <=> CAST(:qv AS vector) ASC
			LIMIT :topK
			""";

	private final EntityManager em;
	private final EmbeddingService embeddingService;
	private final StatusService statusService;
	private final KnowledgeProperties properties;

	/**
	 * Retrieves the most similar chunks of the project's current, successfully-processed documents.
	 *
	 * @param projectId the already-authorized project to search within (required)
	 * @param query     the free-text query (required, non-blank)
	 * @param topK      optional caller override; null uses the configured default, clamped to the max
	 * @return ordered hits (closest first), possibly empty; never null
	 */
	public List<KnowledgeSearchResult> retrieve(Long projectId, String query, Integer topK) {
		if (projectId == null || query == null || query.isBlank()) {
			return List.of();
		}

		var completed = statusService.getStatusProcessByCode(STATUS_COMPLETED);
		if (completed == null) {
			log.warn("retrieve: COMPLETED process status not found; cannot run project-scoped search");
			return List.of();
		}

		// Remote embedding call — deliberately outside any database transaction.
		var vectors = embeddingService.embed(List.of(query));
		if (vectors.isEmpty() || vectors.getFirst() == null || vectors.getFirst().length == 0) {
			log.warn("retrieve: query produced no embedding; project={}", projectId);
			return List.of();
		}

		var limit = resolveTopK(topK);
		var vectorLiteral = toVectorLiteral(vectors.getFirst());

		@SuppressWarnings("unchecked")
		List<Object[]> rows = em.createNativeQuery(SEARCH_SQL)
				.setParameter("qv", vectorLiteral)
				.setParameter("projectId", projectId)
				.setParameter("statusId", completed.getId())
				.setParameter("topK", limit)
				.getResultList();

		var minScore = properties.getRag().getMinScore();
		var results = new ArrayList<KnowledgeSearchResult>(rows.size());
		for (var row : rows) {
			var score = asDouble(row[11]);
			if (score != null && score < minScore) {
				continue;
			}
			var item = new KnowledgeSearchResult();
			item.setChunkId(asLong(row[0]));
			item.setChunkIndex(asInteger(row[1]));
			item.setContent((String) row[2]);
			item.setPageStart(asInteger(row[3]));
			item.setPageEnd(asInteger(row[4]));
			item.setSlideStart(asInteger(row[5]));
			item.setSlideEnd(asInteger(row[6]));
			item.setDocumentId(asLong(row[7]));
			item.setLogicalDocumentId(asLong(row[8]));
			item.setVersionNumber(asInteger(row[9]));
			item.setDocumentTitle((String) row[10]);
			item.setScore(score);
			results.add(item);
		}

		log.info("retrieve: project={} topK={} -> {} hit(s) (minScore={})", projectId, limit, results.size(),
				minScore);
		return results;
	}

	private int resolveTopK(Integer requested) {
		var rag = properties.getRag();
		var value = requested != null ? requested : rag.getTopK();
		if (value < 1) {
			value = rag.getTopK();
		}
		return Math.min(value, rag.getMaxTopK());
	}

	/** Formats a {@code float[]} as a pgvector text literal, e.g. {@code [0.12,0.34]}, bound and cast as vector. */
	private String toVectorLiteral(float[] vector) {
		var sb = new StringBuilder(vector.length * 8 + 2);
		sb.append('[');
		for (var i = 0; i < vector.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(vector[i]);
		}
		sb.append(']');
		return sb.toString();
	}

	private Long asLong(Object value) {
		return value instanceof Number n ? n.longValue() : null;
	}

	private Integer asInteger(Object value) {
		return value instanceof Number n ? n.intValue() : null;
	}

	private Double asDouble(Object value) {
		return value instanceof Number n ? n.doubleValue() : null;
	}
}
