package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

import lombok.Getter;
import lombok.Setter;

/**
 * Input to the project-knowledge RAG agent: the authorized project, the user's question, and the
 * number of chunks to retrieve. The service layer validates project access before constructing this
 * and handing it to {@code AgentInvocation.invoke(...)}; the agent itself does no authorization.
 */
@Setter
@Getter
public class RagQuery {

	private Long projectId;
	private String question;
	private Integer topK;

	public RagQuery() {
	}

	public RagQuery(Long projectId, String question, Integer topK) {
		this.projectId = projectId;
		this.question = question;
		this.topK = topK;
	}
}
