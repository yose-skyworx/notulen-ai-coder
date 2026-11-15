package com.ilkeiapps.skyworx.notulenai.service;

import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;
import com.ilkeiapps.skyworx.notulenai.utility.SecretRedactor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Assembles the text one file review is built from — the guideline that governs it, its diff, and a
 * slice of surrounding source — inside the configured token budget.
 * <p>
 * Two rules drive the implementation:
 * <ol>
 * <li><b>Redact repository text before truncating it.</b> Cutting first can split a credential in half,
 * and the surviving half is still recognisable as one. Redacting first means truncation only ever
 * removes text that is already safe to send. Only the diff and the source snapshot are repository text;
 * the guideline is ours — see {@link #build}.</li>
 * <li><b>Only the governing guideline.</b> The caller passes the text of the one active guideline the
 * file's review role selected — see {@code CodeReviewGuidelineService} — not a library. A service is
 * judged against the service standard and nothing else, so unrelated rules never consume budget and
 * never invite a finding that cites a rule this file was never under.</li>
 * </ol>
 * <p>
 * The token estimate is deliberately crude — four characters per token. It is a budget guard, not a
 * tokenizer, and being within ten percent either way is enough to keep a prompt off the model's context
 * limit. A real tokenizer would add a dependency to protect against an error this size does not cause.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewContextBuilder {

	private static final int CHARS_PER_TOKEN = 4;
	private static final String TRUNCATION_MARKER = "\n...[truncated]";

	private final CodeReviewProperties properties;

	/**
	 * The three context sections plus their combined estimated size. Empty rather than null when a
	 * section has nothing to contribute, so callers can concatenate without null checks.
	 */
	public record ReviewContext(String guidelineText, String diffText, String sourceText, int estimatedTokens) {
	}

	/**
	 * @param filePath      the file under review, named in the log line when the budget is exceeded
	 * @param guidelineText the content of the active guideline for this file's role
	 * @param diff          the file's unified diff from the commit, or null for a whole-branch review
	 * @param source        the file's full contents at the reviewed commit, or null when unavailable
	 */
	public ReviewContext build(String filePath, String guidelineText, String diff, String source) {
		var budget = properties.getContext();

		// Not redacted, unlike the two sections below. The guideline is authored in this application and
		// read from its own database, not fetched from the repository under review, so there is no
		// accidental secret to strip — and a standard that documents what a credential looks like is
		// exactly what SecretRedactor would mangle.
		var guideline = truncateToTokens(guidelineText, budget.getMaxGuidelineTokens());
		var diffText = redactAndFit(diff, budget.getMaxDiffTokens());
		var sourceText = redactAndFit(source, budget.getMaxSourceContextTokens());

		var total = estimateTokens(guideline) + estimateTokens(diffText) + estimateTokens(sourceText);
		var ceiling = budget.getMaxInputTokens();
		if (total > ceiling) {
			// The per-section budgets are individually configurable, so their sum can exceed the overall
			// ceiling. Cut in reverse order of value to the review: surrounding source is supplementary,
			// the diff is the change under examination, the guidelines are the rules it is judged against.
			log.warn("context for {}: {} estimated tokens exceed the {} ceiling, trimming", filePath, total, ceiling);

			sourceText = trimBy(sourceText, total - ceiling);
			total = estimateTokens(guideline) + estimateTokens(diffText) + estimateTokens(sourceText);

			if (total > ceiling) {
				diffText = trimBy(diffText, total - ceiling);
				total = estimateTokens(guideline) + estimateTokens(diffText) + estimateTokens(sourceText);
			}
			if (total > ceiling) {
				guideline = trimBy(guideline, total - ceiling);
				total = estimateTokens(guideline) + estimateTokens(diffText) + estimateTokens(sourceText);
			}
		}

		return new ReviewContext(guideline, diffText, sourceText, total);
	}

	private String redactAndFit(String text, int maxTokens) {
		return truncateToTokens(SecretRedactor.redact(text), maxTokens);
	}

	/** Removes roughly {@code tokensToRemove} tokens from the end of the text. */
	private String trimBy(String text, int tokensToRemove) {
		if (text == null || text.isEmpty() || tokensToRemove <= 0) {
			return text;
		}
		return truncateToTokens(text, Math.max(0, estimateTokens(text) - tokensToRemove));
	}

	private static String truncateToTokens(String text, int maxTokens) {
		if (text == null) {
			return "";
		}
		if (maxTokens <= 0) {
			return "";
		}
		var maxChars = maxTokens * CHARS_PER_TOKEN;
		if (text.length() <= maxChars) {
			return text;
		}
		// The marker is budgeted for, so the trimmed result still satisfies the limit it was trimmed to.
		var keep = Math.max(0, maxChars - TRUNCATION_MARKER.length());
		return text.substring(0, keep) + TRUNCATION_MARKER;
	}

	private static int estimateTokens(String text) {
		if (text == null || text.isBlank()) {
			return 0;
		}
		return (int) Math.ceil(text.strip().length() / (double) CHARS_PER_TOKEN);
	}
}
