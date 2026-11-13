package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * A RAG chunk assembled by the chunking layer before it is persisted as a {@code DocumentChunk}:
 * the chunk text, an estimated token count, page/slide span, and the ordered sequence indices of the
 * source {@link NormalizedElement}s it was built from (used to create {@code DocumentChunkElement}
 * traceability rows). Internal processing holder, not a REST DTO.
 */
@Setter
@Getter
public class ChunkDraft {

	private String content;
	private int tokenCount;
	private Integer pageStart;
	private Integer pageEnd;
	private Integer slideStart;
	private Integer slideEnd;

	/** Sequence indices (0-based, matching normalized order) of the source elements, in order. */
	private final List<Integer> elementSequenceIndices = new ArrayList<>();
}
