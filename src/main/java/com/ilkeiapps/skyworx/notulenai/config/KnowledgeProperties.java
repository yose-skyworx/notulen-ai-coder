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
}
