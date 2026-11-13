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
		/** Safety cap on the total characters of grounding context sent to the LLM. */
		private int maxContextChars = 12000;
		/** Sampling temperature for answer generation; low for grounded, deterministic answers. */
		private double temperature = 0.2;
	}
}
