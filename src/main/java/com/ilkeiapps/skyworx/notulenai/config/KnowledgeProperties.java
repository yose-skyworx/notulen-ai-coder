package com.ilkeiapps.skyworx.notulenai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Typed configuration for the knowledge-base module, bound from {@code knowledge.*}: chunking
 * targets, embedding dimension, processing retry policy, and the upload size limit. These are tunable
 * defaults, not hardcoded constants.
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeProperties {

	private Chunk chunk = new Chunk();
	private Embedding embedding = new Embedding();
	private Processing processing = new Processing();
	private Upload upload = new Upload();
	private Rag rag = new Rag();
	private Expansion expansion = new Expansion();

	@Setter
	@Getter
	public static class Chunk {
		private int targetTokens = 700;
		private int overlapTokens = 100;
		private int minTokens = 150;
		private int maxTokens = 1000;
	}

	@Setter
	@Getter
	public static class Embedding {
		private int dimension = 1024;
		/** Max number of chunk texts sent to the embedding model per request. */
		private int batchSize = 32;
		/** Hard cap on characters per text sent to the embedding model (safety net against oversized inputs). */
		private int maxInputChars = 8000;
		/** Attempts per embedding call before giving up (retries transient 503/timeout "no inference slot"). */
		private int maxRetries = 4;
		/** Base delay between retries in ms; grows linearly with the attempt number. */
		private long retryDelayMs = 3000;
	}

	@Setter
	@Getter
	public static class Processing {
		private int maxAttempts = 3;
	}

	@Setter
	@Getter
	public static class Upload {
		private long maxFileSizeBytes = 41943040L;
	}

	@Setter
	@Getter
	public static class Rag {
		/** Default number of chunks retrieved for grounding when the request does not override it. */
		private int topK = 5;
		/** Hard cap on top-K regardless of the client-supplied value. */
		private int maxTopK = 20;
		/** Minimum cosine similarity a chunk must reach to be used (set &le; -1 to disable filtering). */
		private double minScore = 0.0;
		/**
		 * Passages handed to the LLM, one per distinct chunk. Kept at 1: the deployed chat model
		 * ({@code lfm-1.2b}) answers correctly from a single short passage but starts answering from the
		 * wrong one as soon as a second, distracting passage is present. Raise only alongside a more
		 * capable model.
		 */
		private int maxPassages = 1;
		/** Character budget for one selected passage; bounds the prompt to the model's usable window. */
		private int passageBudgetChars = 600;
		/** Lines kept after a matched line so table rows keep their values and explanation. */
		private int passageWindowLines = 3;
		/**
		 * Minimum lexical score a line must reach to become a passage. When no line in any retrieved
		 * chunk reaches it, the answer is reported as insufficient without calling the LLM — the model
		 * invents an answer rather than admitting the context does not contain one.
		 */
		private double minPassageScore = 1.0;
		/**
		 * Sampling temperature for answer generation. 0.0 (greedy): the deployed model's accuracy on this
		 * task degrades noticeably as soon as sampling is enabled.
		 */
		private double temperature = 0.0;
		/**
		 * When true, the answer action logs the prompt sent to the LLM and the generated text.
		 * Diagnostic only; verbose. No extra LLM call.
		 */
		private boolean debugLog = false;
	}

	/**
	 * Read-time context expansion, applied to retrieved chunks before they are reranked. Operates on
	 * already-ingested chunks, so changing these values takes effect on the next question with no
	 * re-ingest and no re-embedding.
	 */
	@Setter
	@Getter
	public static class Expansion {
		/** Master switch. Turn off to compare answer quality with and without expansion. */
		private boolean enabled = true;
		/**
		 * A hit shorter than this is treated as not self-contained and gets its sibling elements appended.
		 * Mirrors {@code knowledge.chunk.min-tokens}: a chunk the chunker left below its own floor is the
		 * one most likely to be missing the row that answers the question.
		 */
		private int expandBelowTokens = 150;
		/**
		 * Character ceiling for the sibling content appended to one hit. Deliberately separate from
		 * {@code knowledge.rag.passage-budget-chars}, which bounds what reaches the model — this bounds
		 * what the reranker is allowed to choose from.
		 */
		private int maxSiblingChars = 800;
	}
}
