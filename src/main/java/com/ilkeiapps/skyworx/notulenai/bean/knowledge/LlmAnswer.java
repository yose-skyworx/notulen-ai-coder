package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * Structured output the RAG answer action asks the LLM to produce: the grounded answer text, whether
 * the context was sufficient, and the 1-based numbers of the context sources the model actually used.
 * <p>
 * Only {@code citedSources} (the source numbers) is trusted from the model; the agent rebuilds the
 * real {@link Citation} metadata (document title, page/slide, score) from the actually-retrieved
 * chunks, so a page number or title can never be fabricated. Requires a model capable of reliable
 * structured output; small models that cannot emit valid JSON will fail binding.
 */
@Setter
@Getter
public class LlmAnswer {

	private String answer;
	/** True when the numbered context was sufficient to answer; false means "not enough information". */
	private boolean grounded;
	/** 1-based source numbers the model used, matching the {@code [n]} markers in the prompt context. */
	private List<Integer> citedSources = new ArrayList<>();

	public LlmAnswer() {
	}
}
