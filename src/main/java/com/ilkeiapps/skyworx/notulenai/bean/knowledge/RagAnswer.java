package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * The goal of the project-knowledge RAG agent and the API response payload: the original question, the
 * grounded answer, whether the knowledge base contained enough information to answer, and the source
 * citations backing the answer. Assembled by the agent's answer action from the LLM output plus the
 * real retrieved chunks; it never exposes a managed entity.
 */
@Setter
@Getter
public class RagAnswer {

	private String question;
	private String answer;
	/** True when the retrieved context was sufficient to answer; false means "not enough information". */
	private boolean grounded;
	private List<Citation> citations = new ArrayList<>();

	public RagAnswer() {
	}

	/** Answer for the case where no relevant context was retrieved or the context was insufficient. */
	public static RagAnswer insufficient(String question, String message) {
		var a = new RagAnswer();
		a.question = question;
		a.answer = message;
		a.grounded = false;
		a.citations = new ArrayList<>();
		return a;
	}
}
