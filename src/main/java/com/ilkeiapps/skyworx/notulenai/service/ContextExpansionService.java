package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.KnowledgeSearchResult;
import com.ilkeiapps.skyworx.notulenai.config.KnowledgeProperties;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Makes a retrieved chunk aware of where it sits in its document, at read time and without re-ingesting
 * anything. Vector search returns a chunk's text alone: for this corpus that is usually one small table
 * with no heading attached, so neither the reranker nor the model can tell which section it came from, and
 * the row that actually answers the question is often in a sibling element that landed in a different
 * chunk. This service rewrites each hit's {@code content} in place to carry the ancestor heading path and,
 * for hits that are too short to be self-contained, the sibling elements sharing their parent.
 * <p>
 * Rewriting {@code content} rather than adding a field is deliberate. {@link PassageRerankerService} scores
 * the <em>lines</em> of {@code content}, so a prefixed heading becomes matchable — a question naming a menu
 * can now select the chunk that belongs to it. The prefix does not by itself reach the model: selection
 * returns the matched line plus a small window around it, and a chunk whose matched line is a GFM table row
 * returns that row alone, dozens of lines below the prefix. {@link PassageFormatterService} therefore reads
 * the bracketed first line back off the chunk and attaches it to the passage at prompt time.
 * {@code Citation.from()} copies identifiers and provenance but never {@code content}, so the enrichment
 * still cannot reach an API response.
 * <p>
 * Sibling rows are injected <em>before</em> reranking, not after, because with one element per chunk the
 * row a question asks about is frequently not in any retrieved chunk at all — no amount of post-selection
 * enrichment can surface it. The distraction risk is bounded by machinery that already exists: the reranker
 * still hands the model only the matched line plus {@code passage-window-lines} neighbours, within
 * {@code passage-budget-chars}.
 * <p>
 * Two indexed round trips per question, never one per hit. Both re-assert {@code project_id} and
 * {@code is_current} through {@code knowledge_documents}, because {@code document_elements} carries no
 * {@code project_id} of its own — expanding through {@code parent_id} leaves {@code document_chunks}
 * behind, and omitting either predicate would leak another project's documents or a superseded version.
 * <p>
 * Read-only database work with no remote call, so it runs in its own short transaction and must be invoked
 * after the query embedding has already been fetched.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextExpansionService {

	/**
	 * Same characters-per-token proxy {@code DocumentChunkingService} uses, so "shorter than
	 * {@code expand-below-tokens}" means the same thing here as "below {@code knowledge.chunk.min-tokens}"
	 * meant when the chunk was built.
	 */
	private static final int CHARS_PER_TOKEN = 4;

	/**
	 * Ancestor path of each hit's first source element. {@code document_elements} is joined back to
	 * {@code knowledge_documents} purely to re-assert scope; {@code element_order} decides which of a
	 * chunk's several source elements labels it, and the first-seen row per chunk wins in
	 * {@link #readPaths}.
	 */
	private static final String PATH_SQL = """
			SELECT ce.chunk_id, e.element_path
			FROM document_chunk_elements ce
			JOIN document_elements e ON e.id = ce.element_id
			JOIN knowledge_documents d ON d.id = e.document_id
			WHERE ce.chunk_id IN (:chunkIds)
			  AND d.project_id = :projectId
			  AND d.is_current = true
			ORDER BY ce.chunk_id, ce.element_order
			""";

	/**
	 * Siblings of each short hit's source elements: the other children of the same parent, minus the
	 * elements the chunk already holds. The {@code par} join excludes two cases that would otherwise
	 * flood the passage — a source element with no parent, and one hanging directly off the synthetic
	 * {@code DOCUMENT} root, whose "siblings" are the rest of the document.
	 * <p>
	 * A chunk whose several source elements share one parent yields that sibling once per element. The
	 * multiplication is bounded (source elements per chunk are few, and 130 of the current 160 chunks hold
	 * exactly one) and collapsed in {@link #readSiblings} by {@code (chunk_id, sequence_number)}, which
	 * costs less than a {@code DISTINCT} over a {@code text} column and keeps the ordering explicit.
	 */
	private static final String SIBLING_SQL = """
			SELECT ce.chunk_id, sib.sequence_number, sib.content
			FROM document_chunk_elements ce
			JOIN document_elements src ON src.id = ce.element_id
			JOIN document_elements par ON par.id = src.parent_id
			JOIN document_elements sib ON sib.parent_id = par.id
			JOIN knowledge_documents d ON d.id = sib.document_id
			WHERE ce.chunk_id IN (:chunkIds)
			  AND d.project_id = :projectId
			  AND d.is_current = true
			  AND par.element_type <> 'DOCUMENT'
			  AND sib.element_type <> 'DOCUMENT'
			  AND sib.content IS NOT NULL
			  AND NOT EXISTS (
			        SELECT 1 FROM document_chunk_elements own
			        WHERE own.chunk_id = ce.chunk_id AND own.element_id = sib.id)
			ORDER BY ce.chunk_id, sib.sequence_number
			""";

	private final EntityManager em;
	private final KnowledgeProperties properties;

	/**
	 * Enriches the retrieved hits in place. Does nothing when expansion is disabled or there are no hits,
	 * so a caller can invoke it unconditionally.
	 *
	 * @param projectId the project the hits were retrieved from; re-asserted on every query
	 * @param hits      the retrieval results to enrich (may be empty; never null)
	 */
	@Transactional(readOnly = true)
	public void enrich(Long projectId, List<KnowledgeSearchResult> hits) {
		var cfg = properties.getExpansion();
		if (!cfg.isEnabled() || projectId == null || hits == null || hits.isEmpty()) {
			return;
		}

		var chunkIds = chunkIds(hits);
		if (chunkIds.isEmpty()) {
			return;
		}

		var paths = readPaths(projectId, chunkIds);
		var shortIds = shortChunkIds(hits, cfg.getExpandBelowTokens());
		var siblings = shortIds.isEmpty() ? Map.<Long, List<String>>of() : readSiblings(projectId, shortIds);

		var budget = Math.max(0, cfg.getMaxSiblingChars());
		var prefixed = 0;
		var expanded = 0;
		for (KnowledgeSearchResult hit : hits) {
			var original = hit.getContent();
			if (original == null) {
				continue;
			}
			var enriched = prependPath(original, paths.get(hit.getChunkId()));
			if (!enriched.equals(original)) {
				prefixed++;
			}
			var withSiblings = appendSiblings(enriched, siblings.get(hit.getChunkId()), budget);
			if (withSiblings.length() > enriched.length()) {
				expanded++;
			}
			hit.setContent(withSiblings);
		}

		log.info("expand: project={} {} hit(s) -> {} prefixed with its section, {} completed with siblings "
				+ "(belowTokens={}, siblingBudget={} chars)", projectId, hits.size(), prefixed, expanded,
				cfg.getExpandBelowTokens(), budget);
	}

	/**
	 * Reads the ancestor path of each chunk's first source element. Rows arrive ordered by
	 * {@code (chunk_id, element_order)}, so the first row seen for a chunk is the one that labels it and
	 * later rows are its other source elements.
	 */
	private Map<Long, String> readPaths(Long projectId, List<Long> chunkIds) {
		var paths = new LinkedHashMap<Long, String>();

		@SuppressWarnings("unchecked")
		List<Object[]> rows = em.createNativeQuery(PATH_SQL)
				.setParameter("chunkIds", chunkIds)
				.setParameter("projectId", projectId)
				.getResultList();

		for (Object[] row : rows) {
			var chunkId = asLong(row[0]);
			var path = (String) row[1];
			if (chunkId == null || path == null || path.isBlank()) {
				continue;
			}
			paths.putIfAbsent(chunkId, path);
		}
		return paths;
	}

	/**
	 * Reads the sibling elements of each short hit, grouped per chunk and in {@code sequence_number} order.
	 * Ordering is asserted in SQL rather than trusted from the row order: {@code document_elements} comes
	 * back in heap order without it, which silently scrambles any positional comparison.
	 */
	private Map<Long, List<String>> readSiblings(Long projectId, List<Long> chunkIds) {
		var byChunk = new HashMap<Long, List<String>>();
		var seen = new HashMap<Long, Set<Integer>>();

		@SuppressWarnings("unchecked")
		List<Object[]> rows = em.createNativeQuery(SIBLING_SQL)
				.setParameter("chunkIds", chunkIds)
				.setParameter("projectId", projectId)
				.getResultList();

		for (Object[] row : rows) {
			var chunkId = asLong(row[0]);
			var sequence = asInteger(row[1]);
			var content = ((String) row[2]).strip();
			if (chunkId == null || sequence == null || content.isEmpty()) {
				continue;
			}
			if (!seen.computeIfAbsent(chunkId, k -> new HashSet<>()).add(sequence)) {
				continue;
			}
			byChunk.computeIfAbsent(chunkId, k -> new ArrayList<>()).add(content);
		}
		return byChunk;
	}

	/**
	 * Renders {@code /E. MASTER DATA/10. Token Core} as {@code [E. MASTER DATA > 10. Token Core]} on its own
	 * line ahead of the chunk text. Skipped when the chunk already names its own leaf heading — 30 of the
	 * current 160 chunks do — so the heading is never stated twice in one passage.
	 * <p>
	 * The bracketed first line is a contract: {@link PassageFormatterService} recognizes it by shape and
	 * re-attaches it to the selected passage, which is the only way it reaches the prompt.
	 */
	private String prependPath(String content, String path) {
		if (path == null || path.isBlank()) {
			return content;
		}
		var segments = new ArrayList<String>();
		for (var segment : path.split("/")) {
			var trimmed = segment.strip();
			if (!trimmed.isEmpty()) {
				segments.add(trimmed);
			}
		}
		if (segments.isEmpty()) {
			return content;
		}
		var leaf = segments.getLast().toLowerCase(Locale.ROOT);
		if (content.toLowerCase(Locale.ROOT).contains(leaf)) {
			return content;
		}
		return "[" + String.join(" > ", segments) + "]\n" + content;
	}

	/**
	 * Appends sibling elements in order until {@code budget} characters of <em>sibling</em> text have been
	 * added — the chunk's own text and its new heading do not count against it. Returns {@code content}
	 * unchanged when nothing fits or there is nothing to add.
	 */
	private String appendSiblings(String content, List<String> siblings, int budget) {
		if (siblings == null || siblings.isEmpty() || budget <= 0) {
			return content;
		}
		var sb = new StringBuilder(content);
		var start = sb.length();
		var added = false;
		for (String sibling : siblings) {
			if (sb.length() - start + sibling.length() + 1 > budget) {
				break;
			}
			sb.append('\n').append(sibling);
			added = true;
		}
		return added ? sb.toString() : content;
	}

	private List<Long> chunkIds(List<KnowledgeSearchResult> hits) {
		var ids = new ArrayList<Long>(hits.size());
		for (KnowledgeSearchResult hit : hits) {
			if (hit.getChunkId() != null) {
				ids.add(hit.getChunkId());
			}
		}
		return ids;
	}

	/** Ids of the hits too short to be self-contained, and therefore worth completing with their siblings. */
	private List<Long> shortChunkIds(List<KnowledgeSearchResult> hits, int belowTokens) {
		var ids = new ArrayList<Long>();
		for (KnowledgeSearchResult hit : hits) {
			if (hit.getChunkId() != null && estimateTokens(hit.getContent()) < belowTokens) {
				ids.add(hit.getChunkId());
			}
		}
		return ids;
	}

	private int estimateTokens(String text) {
		if (text == null || text.isBlank()) {
			return 0;
		}
		return (int) Math.ceil(text.strip().length() / (double) CHARS_PER_TOKEN);
	}

	private Long asLong(Object value) {
		return value instanceof Number n ? n.longValue() : null;
	}

	private Integer asInteger(Object value) {
		return value instanceof Number n ? n.intValue() : null;
	}
}
