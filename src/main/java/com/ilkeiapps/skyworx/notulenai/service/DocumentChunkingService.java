package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.ChunkDraft;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.NormalizedElement;
import com.ilkeiapps.skyworx.notulenai.config.KnowledgeProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Structure-aware chunking over normalized {@link NormalizedElement}s. Produces RAG-ready
 * {@link ChunkDraft}s that respect element and heading boundaries, keep tables intact (as their own
 * chunks), split oversized elements, apply a configurable word-based overlap between consecutive
 * chunks, and carry page/slide provenance plus source-element traceability.
 * <p>
 * Token counts are estimated by word count (a cheap proxy; BGE-M3 uses sub-word tokens). Sizes come
 * from {@link KnowledgeProperties.Chunk} and are not hardcoded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentChunkingService {

	/** Rough token estimate: ~4 characters per token. Robust to whitespace-poor content (e.g. tables of contents). */
	private static final int CHARS_PER_TOKEN = 4;

	private final KnowledgeProperties properties;

	public List<ChunkDraft> chunk(List<NormalizedElement> elements) {
		var cfg = properties.getChunk();
		log.debug("chunking {} elements (target={}, overlap={}, min={}, max={} tokens)", elements.size(),
				cfg.getTargetTokens(), cfg.getOverlapTokens(), cfg.getMinTokens(), cfg.getMaxTokens());
		var chunks = new ArrayList<ChunkDraft>();
		var current = new Accumulator();
		String overlapCarry = "";

		for (NormalizedElement ne : elements) {
			var type = ne.getElementType();
			var text = ne.getContent();
			if ("DOCUMENT".equals(type) || "TABLE_ROW".equals(type) || text == null || text.isBlank()) {
				// Skip the synthetic root and table rows (the TABLE element already carries the full text).
				continue;
			}

			if ("TABLE".equals(type)) {
				overlapCarry = flush(current, chunks, overlapCarry, cfg);
				current = new Accumulator();
				emitOversizedOrSingle(ne, chunks, cfg);
				continue;
			}

			var tokens = estimateTokens(text);

			if ("HEADING".equals(type) && current.tokens >= cfg.getMinTokens()) {
				overlapCarry = flush(current, chunks, overlapCarry, cfg);
				current = new Accumulator();
			}

			if (tokens > cfg.getMaxTokens()) {
				overlapCarry = flush(current, chunks, overlapCarry, cfg);
				current = new Accumulator();
				emitOversizedOrSingle(ne, chunks, cfg);
				continue;
			}

			if (current.tokens + tokens > cfg.getTargetTokens() && current.tokens >= cfg.getMinTokens()) {
				overlapCarry = flush(current, chunks, overlapCarry, cfg);
				current = new Accumulator();
			}

			current.add(ne, tokens);
		}

		flush(current, chunks, overlapCarry, cfg);
		reindexAndCount(chunks);
		log.info("chunking produced {} chunks from {} elements", chunks.size(), elements.size());
		return chunks;
	}

	/** Emits an element that is either oversized (split into character windows) or fits in one chunk. */
	private void emitOversizedOrSingle(NormalizedElement ne, List<ChunkDraft> chunks, KnowledgeProperties.Chunk cfg) {
		var content = ne.getContent();
		if (estimateTokens(content) <= cfg.getMaxTokens()) {
			var draft = new ChunkDraft();
			draft.setContent(content);
			draft.getElementSequenceIndices().add(ne.getSequenceNumber());
			applyProvenance(draft, ne);
			chunks.add(draft);
			return;
		}
		var windowChars = Math.max(1, cfg.getTargetTokens() * CHARS_PER_TOKEN);
		var stepChars = Math.max(1, (cfg.getTargetTokens() - cfg.getOverlapTokens()) * CHARS_PER_TOKEN);
		for (var start = 0; start < content.length(); start += stepChars) {
			var end = Math.min(content.length(), start + windowChars);
			var draft = new ChunkDraft();
			draft.setContent(content.substring(start, end).strip());
			draft.getElementSequenceIndices().add(ne.getSequenceNumber());
			applyProvenance(draft, ne);
			chunks.add(draft);
			if (end == content.length()) {
				break;
			}
		}
	}

	/** Finalizes the accumulator into a chunk (prefixing any overlap carry) and returns the next carry. */
	private String flush(Accumulator acc, List<ChunkDraft> chunks, String overlapCarry,
			KnowledgeProperties.Chunk cfg) {
		if (acc.elements.isEmpty()) {
			return overlapCarry;
		}
		var parts = new ArrayList<String>();
		if (!overlapCarry.isBlank()) {
			parts.add(overlapCarry);
		}
		for (NormalizedElement ne : acc.elements) {
			parts.add(ne.getContent());
		}
		var content = String.join("\n", parts);

		var draft = new ChunkDraft();
		draft.setContent(content);
		for (NormalizedElement ne : acc.elements) {
			draft.getElementSequenceIndices().add(ne.getSequenceNumber());
			mergeProvenance(draft, ne);
		}
		chunks.add(draft);

		return tailChars(content, cfg.getOverlapTokens() * CHARS_PER_TOKEN);
	}

	private void reindexAndCount(List<ChunkDraft> chunks) {
		for (ChunkDraft c : chunks) {
			c.setTokenCount(estimateTokens(c.getContent()));
		}
	}

	private void applyProvenance(ChunkDraft draft, NormalizedElement ne) {
		draft.setPageStart(ne.getPageNumber());
		draft.setPageEnd(ne.getPageNumber());
		draft.setSlideStart(ne.getSlideNumber());
		draft.setSlideEnd(ne.getSlideNumber());
	}

	private void mergeProvenance(ChunkDraft draft, NormalizedElement ne) {
		draft.setPageStart(min(draft.getPageStart(), ne.getPageNumber()));
		draft.setPageEnd(max(draft.getPageEnd(), ne.getPageNumber()));
		draft.setSlideStart(min(draft.getSlideStart(), ne.getSlideNumber()));
		draft.setSlideEnd(max(draft.getSlideEnd(), ne.getSlideNumber()));
	}

	private Integer min(Integer a, Integer b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return Math.min(a, b);
	}

	private Integer max(Integer a, Integer b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return Math.max(a, b);
	}

	/** Last {@code maxChars} characters of {@code content}, starting at a word boundary when possible. */
	private String tailChars(String content, int maxChars) {
		if (maxChars <= 0 || content == null || content.length() <= maxChars) {
			return content == null ? "" : content;
		}
		var tail = content.substring(content.length() - maxChars);
		var space = tail.indexOf(' ');
		return space >= 0 && space < tail.length() - 1 ? tail.substring(space + 1) : tail;
	}

	private int estimateTokens(String text) {
		if (text == null || text.isBlank()) {
			return 0;
		}
		return (int) Math.ceil(text.strip().length() / (double) CHARS_PER_TOKEN);
	}

	/** Accumulates elements for the chunk currently being built. */
	private static final class Accumulator {
		final List<NormalizedElement> elements = new ArrayList<>();
		int tokens = 0;

		void add(NormalizedElement ne, int tokenCount) {
			elements.add(ne);
			tokens += tokenCount;
		}
	}
}
