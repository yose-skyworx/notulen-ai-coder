package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.KnowledgeSearchResult;
import com.ilkeiapps.skyworx.notulenai.config.KnowledgeProperties;
import com.ilkeiapps.skyworx.notulenai.utility.QueryTerms;

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

	/**
	 * Content stripped to lowercase ASCII alphanumerics, matching how the reranker normalizes a line. This
	 * lets a lexical match reach a term the PDF wrapped mid-word — {@code MODEKECOCOK AN} becomes
	 * {@code modekecocokan} — so the stored chunk matches a {@code modekecocokan} the user typed.
	 */
	private static final String NORMALIZED_CONTENT = "regexp_replace(lower(c.content), '[^a-z0-9]', '', 'g')";

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

	/**
	 * Lexical fallback: the same projection and scope as {@link #SEARCH_SQL}, but selected by a normalized
	 * {@code LIKE} on the chunk content rather than vector distance, for chunks the embedding search ranked
	 * outside {@code top-k}. {@code %s} is replaced with an OR of one {@code NORMALIZED_CONTENT LIKE :tN}
	 * predicate per salient query term (all parameter-bound). Still ordered by vector distance so the
	 * closest lexical matches come first, and still scored by cosine so the reranker can break ties.
	 */
	private static final String LEXICAL_SQL_TEMPLATE = """
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
			  AND (%s)
			ORDER BY c.embedding <=> CAST(:qv AS vector) ASC
			LIMIT :lexLimit
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
		var seen = new LinkedHashSet<Long>();
		for (var row : rows) {
			var score = asDouble(row[11]);
			if (score != null && score < minScore) {
				continue;
			}
			var item = mapRow(row);
			results.add(item);
			seen.add(item.getChunkId());
		}

		// Lexical fallback: the embedding search can miss a chunk that plainly contains a salient term — a
		// specific parameter or acronym the question is about — because generic surrounding text out-scores it
		// by cosine. Pull those chunks in by a normalized content match and append the ones vector search did
		// not already return; the reranker then decides whether any line in them actually answers the question.
		var appended = appendLexicalHits(projectId, completed.getId(), vectorLiteral, query, limit, results, seen);

		log.info("retrieve: project={} topK={} -> {} hit(s) ({} lexical, minScore={})", projectId, limit,
				results.size(), appended, minScore);
		if (properties.getRag().isDebugLog()) {
			for (var i = 0; i < results.size(); i++) {
				var hit = results.get(i);
				log.info("retrieve: [{}] chunkId={} score={} preview={}", i + 1, hit.getChunkId(), hit.getScore(),
						preview(hit.getContent()));
			}
		}
		return results;
	}

	/**
	 * Runs the lexical fallback and appends the chunks vector search did not already return, in place.
	 * Returns how many were added. Lexical hits are kept regardless of {@code minScore}: a low cosine is
	 * exactly why vector search missed them, so filtering them by cosine again would defeat the fallback.
	 */
	private int appendLexicalHits(Long projectId, Integer statusId, String vectorLiteral, String query, int limit,
			List<KnowledgeSearchResult> results, LinkedHashSet<Long> seen) {
		var terms = QueryTerms.significant(query);
		if (terms.isEmpty()) {
			return 0;
		}

		var predicates = new StringBuilder();
		for (var i = 0; i < terms.size(); i++) {
			if (i > 0) {
				predicates.append(" OR ");
			}
			predicates.append(NORMALIZED_CONTENT).append(" LIKE :t").append(i);
		}

		var nativeQuery = em.createNativeQuery(String.format(LEXICAL_SQL_TEMPLATE, predicates))
				.setParameter("qv", vectorLiteral)
				.setParameter("projectId", projectId)
				.setParameter("statusId", statusId)
				.setParameter("lexLimit", limit);
		for (var i = 0; i < terms.size(); i++) {
			// Terms are already lowercase ASCII alphanumerics, so the pattern needs no further escaping.
			nativeQuery.setParameter("t" + i, "%" + terms.get(i) + "%");
		}

		@SuppressWarnings("unchecked")
		List<Object[]> rows = nativeQuery.getResultList();
		var added = 0;
		for (var row : rows) {
			var item = mapRow(row);
			if (seen.add(item.getChunkId())) {
				results.add(item);
				added++;
			}
		}
		return added;
	}

	private KnowledgeSearchResult mapRow(Object[] row) {
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
		item.setScore(asDouble(row[11]));
		return item;
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

	/** First 120 characters of a chunk on one line, for the debug dump of retrieved hits. */
	private String preview(String content) {
		if (content == null) {
			return "";
		}
		var oneLine = content.replace('\n', ' ').strip();
		return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
	}
}
