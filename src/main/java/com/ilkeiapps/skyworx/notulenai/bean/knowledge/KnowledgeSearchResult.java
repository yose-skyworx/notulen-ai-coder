package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

import lombok.Getter;
import lombok.Setter;

/**
 * A single project-scoped semantic-search hit: the matched chunk's text and its source provenance
 * (document identity/version and page/slide range) plus the cosine similarity score. Produced by
 * {@code DocumentRetrievalService} from a native pgvector query and returned to clients as-is (never
 * a managed entity). Also carried on the Embabel blackboard as the grounding context for RAG.
 */
@Setter
@Getter
public class KnowledgeSearchResult {

	private Long chunkId;
	private Integer chunkIndex;
	private String content;
	/** Cosine similarity in {@code [-1, 1]}; higher is closer. Computed as {@code 1 - cosine_distance}. */
	private Double score;

	private Long documentId;
	private Long logicalDocumentId;
	private Integer versionNumber;
	private String documentTitle;

	private Integer pageStart;
	private Integer pageEnd;
	private Integer slideStart;
	private Integer slideEnd;

	public KnowledgeSearchResult() {
	}
}
