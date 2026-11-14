package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;

/**
 * The compact grounding context the RAG agent sends to the LLM: the passages {@code PassageRerankerService}
 * selected out of the retrieved chunks, in prompt order.
 * <p>
 * Vector search returns whole chunks (a chunk here can be a 200-line dump of a document section), which is
 * far more than the deployed chat model can read reliably, so the passages are line-window excerpts rather
 * than chunks. Each carries the chunk it was cut from, so citations are rebuilt from real retrieved chunks.
 * Internal to the agent — never serialized to clients.
 */
@Getter
public class RerankedContext {

	private final List<Passage> passages;

	public RerankedContext(List<Passage> passages) {
		this.passages = passages != null ? passages : new ArrayList<>();
	}

	public static RerankedContext empty() {
		return new RerankedContext(List.of());
	}

	public boolean isEmpty() {
		return passages.isEmpty();
	}

	/** The numbered passages as they appear in the prompt, e.g. {@code [3] FDS (v1)\n8\nMODEKECOCOK AN\n…}. */
	public String toPromptContext() {
		var sb = new StringBuilder();
		for (Passage passage : passages) {
			if (sb.length() > 0) {
				sb.append("\n\n");
			}
			sb.append('[').append(passage.sourceNumber()).append(']');
			var title = passage.source().getDocumentTitle();
			if (title != null && !title.isBlank()) {
				sb.append(' ').append(title);
			}
			sb.append('\n').append(passage.text());
		}
		return sb.toString();
	}

	/**
	 * One selected passage: the chunk it was cut from (for citation metadata), the passage text, the score
	 * that ranked it, and the 1-based position of that chunk in the retrieved list — which is the source
	 * number the prompt shows for it, not the chunk's own id.
	 */
	public record Passage(int sourceNumber, KnowledgeSearchResult source, String text, double score) {
	}
}
