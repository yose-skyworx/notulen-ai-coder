package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.config.KnowledgeProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin wrapper over the configured Spring AI {@link EmbeddingModel} (BGE-M3 via the OpenAI-compatible
 * endpoint). Abstracts embedding generation so the model can be swapped without touching the document
 * persistence layer. Chunk texts are sent in bounded batches ({@code knowledge.embedding.batch-size})
 * to avoid overloading the endpoint or exceeding its per-request limits on large documents; result
 * order is preserved across batches.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

	private final EmbeddingModel embeddingModel;
	private final KnowledgeProperties properties;

	/** Embeds the given texts in order, in batches. Returns an empty list for empty input. */
	public List<float[]> embed(List<String> texts) {
		if (texts == null || texts.isEmpty()) {
			log.debug("embed: no texts to embed");
			return List.of();
		}
		var batchSize = Math.max(1, properties.getEmbedding().getBatchSize());
		texts = truncate(texts, properties.getEmbedding().getMaxInputChars());
		var total = texts.size();
		var result = new ArrayList<float[]>(total);
		var start = System.currentTimeMillis();
		log.info("embed: {} texts in batches of {}", total, batchSize);

		for (var from = 0; from < total; from += batchSize) {
			var to = Math.min(from + batchSize, total);
			var batch = texts.subList(from, to);
			try {
				var part = callWithRetry(batch);
				result.addAll(part);
				log.debug("embed: batch [{}, {}) -> {} vectors", from, to, part.size());
			} catch (Exception e) {
				// A batch can fail on a per-request token limit or one bad input; fall back to per-text
				// so the rest still succeeds and any offending input is isolated and logged.
				log.warn("embed: batch [{}, {}) failed ({}); retrying {} texts individually", from, to,
						e.getMessage(), batch.size());
				embedIndividually(batch, from, result);
			}
		}

		log.info("embed: received {} embeddings (dimension={}) in {} ms", result.size(),
				result.isEmpty() ? 0 : result.getFirst().length, System.currentTimeMillis() - start);
		return result;
	}

	/** Calls the embedding model with bounded retries + linear backoff, for transient 503/timeout errors. */
	private List<float[]> callWithRetry(List<String> input) {
		var attempts = Math.max(1, properties.getEmbedding().getMaxRetries());
		var delayMs = properties.getEmbedding().getRetryDelayMs();
		RuntimeException last = null;
		for (var attempt = 1; attempt <= attempts; attempt++) {
			try {
				return embeddingModel.embed(input);
			} catch (RuntimeException e) {
				last = e;
				if (attempt < attempts) {
					log.warn("embed: attempt {}/{} failed ({}); retrying in {} ms", attempt, attempts,
							e.getMessage(), delayMs * attempt);
					sleep(delayMs * attempt);
				}
			}
		}
		throw last;
	}

	private void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("embedding retry interrupted", e);
		}
	}

	/** Caps each text to {@code maxChars} characters; a safety net so no single input can overload the model. */
	private List<String> truncate(List<String> texts, int maxChars) {
		if (maxChars <= 0) {
			return texts;
		}
		var out = new ArrayList<String>(texts.size());
		var truncated = 0;
		for (String text : texts) {
			if (text != null && text.length() > maxChars) {
				out.add(text.substring(0, maxChars));
				truncated++;
			} else {
				out.add(text);
			}
		}
		if (truncated > 0) {
			log.warn("embed: truncated {} text(s) to {} chars before embedding", truncated, maxChars);
		}
		return out;
	}

	/** Embeds each text on its own; a single failure logs the offending text and aborts. */
	private void embedIndividually(List<String> batch, int offset, List<float[]> result) {
		for (var i = 0; i < batch.size(); i++) {
			var text = batch.get(i);
			try {
				result.addAll(callWithRetry(List.of(text)));
			} catch (Exception e) {
				var index = offset + i;
				var preview = text == null ? "null" : text.substring(0, Math.min(120, text.length())).replaceAll("\\s+", " ");
				log.error("embed: text #{} failed (length={}): {} | preview: {}", index,
						text == null ? 0 : text.length(), e.getMessage(), preview);
				throw e;
			}
		}
	}
}
