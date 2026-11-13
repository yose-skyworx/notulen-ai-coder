package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

import lombok.Getter;
import lombok.Setter;

/**
 * A source reference backing a RAG answer, assembled from an actually-retrieved chunk (never from the
 * LLM). Carries the document identity/version, the chunk, its page/slide provenance, and the
 * similarity score so a client can show and link back to the grounding passage.
 */
@Setter
@Getter
public class Citation {

	private Long documentId;
	private Long logicalDocumentId;
	private Integer versionNumber;
	private String documentTitle;

	private Long chunkId;
	private Integer chunkIndex;

	private Integer pageStart;
	private Integer pageEnd;
	private Integer slideStart;
	private Integer slideEnd;

	private Double score;

	public Citation() {
	}

	public static Citation from(KnowledgeSearchResult r) {
		var c = new Citation();
		c.documentId = r.getDocumentId();
		c.logicalDocumentId = r.getLogicalDocumentId();
		c.versionNumber = r.getVersionNumber();
		c.documentTitle = r.getDocumentTitle();
		c.chunkId = r.getChunkId();
		c.chunkIndex = r.getChunkIndex();
		c.pageStart = r.getPageStart();
		c.pageEnd = r.getPageEnd();
		c.slideStart = r.getSlideStart();
		c.slideEnd = r.getSlideEnd();
		c.score = r.getScore();
		return c;
	}
}
