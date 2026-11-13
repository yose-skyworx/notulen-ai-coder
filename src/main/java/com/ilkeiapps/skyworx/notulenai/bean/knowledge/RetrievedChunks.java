package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * Blackboard intermediate produced by the agent's retrieval action and consumed by the
 * answer-generation action: the ordered semantic-search hits that ground the generated answer.
 */
@Setter
@Getter
public class RetrievedChunks {

	private List<KnowledgeSearchResult> results = new ArrayList<>();

	public RetrievedChunks() {
	}

	public RetrievedChunks(List<KnowledgeSearchResult> results) {
		this.results = results != null ? results : new ArrayList<>();
	}
}
