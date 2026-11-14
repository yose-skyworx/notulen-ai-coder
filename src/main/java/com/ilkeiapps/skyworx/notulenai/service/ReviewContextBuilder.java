package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;
import com.ilkeiapps.skyworx.notulenai.service.AgentsGuidelineResolver.Guideline;
import com.ilkeiapps.skyworx.notulenai.utility.SecretRedactor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Assembles the text one file review is built from — the guidelines that govern it, its diff, and a
 * slice of surrounding source — inside the configured token budget.
 * <p>
 * Two rules drive the implementation:
 * <ol>
 * <li><b>Redact before truncating.</b> Cutting first can split a credential in half, and the surviving
 * half is still recognisable as one. Redacting first means truncation only ever removes text that is
 * already safe to send.</li>
 * <li><b>Only the governing guidelines.</b> An {@code AGENTS.md} applies to its own directory and
 * everything below it, so a commit touching both {@code backend/} and {@code frontend/} must not send
 * one area's conventions as context for the other. Passing unrelated rules wastes budget and invites
 * findings that cite a rule the file was never under.</li>
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
	 * @param filePath   the file under review, used to select which guidelines govern it
	 * @param guidelines every guideline resolved for the run, in root-first order
	 * @param diff       the file's unified diff from the commit
	 * @param source     the file's full contents at the reviewed commit, or null when unavailable
	 */
	public ReviewContext build(String filePath, List<Guideline> guidelines, String diff, String source) {
		var budget = properties.getContext();

		var guidelineText = redactAndFit(joinGoverning(filePath, guidelines), budget.getMaxGuidelineTokens());
		var diffText = redactAndFit(diff, budget.getMaxDiffTokens());
		var sourceText = redactAndFit(source, budget.getMaxSourceContextTokens());

		var total = estimateTokens(guidelineText) + estimateTokens(diffText) + estimateTokens(sourceText);
		var ceiling = budget.getMaxInputTokens();
		if (total > ceiling) {
			// The per-section budgets are individually configurable, so their sum can exceed the overall
			// ceiling. Cut in reverse order of value to the review: surrounding source is supplementary,
			// the diff is the change under examination, the guidelines are the rules it is judged against.
			log.warn("context for {}: {} estimated tokens exceed the {} ceiling, trimming", filePath, total, ceiling);

			sourceText = trimBy(sourceText, total - ceiling);
			total = estimateTokens(guidelineText) + estimateTokens(diffText) + estimateTokens(sourceText);

			if (total > ceiling) {
				diffText = trimBy(diffText, total - ceiling);
				total = estimateTokens(guidelineText) + estimateTokens(diffText) + estimateTokens(sourceText);
			}
			if (total > ceiling) {
				guidelineText = trimBy(guidelineText, total - ceiling);
				total = estimateTokens(guidelineText) + estimateTokens(diffText) + estimateTokens(sourceText);
			}
		}

		return new ReviewContext(guidelineText, diffText, sourceText, total);
	}

	/**
	 * Concatenates the guidelines that govern {@code filePath}, keeping the incoming root-first order so
	 * general rules precede the specific ones. A guideline governs a file when the file sits in the
	 * guideline's directory or below it; the root guideline's directory is empty and so governs all.
	 */
	private String joinGoverning(String filePath, List<Guideline> guidelines) {
		if (guidelines == null || guidelines.isEmpty() || filePath == null) {
			return "";
		}

		var governing = new ArrayList<Guideline>();
		for (Guideline guideline : guidelines) {
			if (filePath.startsWith(directoryOf(guideline.path()))) {
				governing.add(guideline);
			}
		}
		if (governing.isEmpty()) {
			return "";
		}

		var joined = new StringBuilder();
		for (Guideline guideline : governing) {
			joined.append("### ").append(guideline.path()).append('\n')
					.append(guideline.content().strip()).append("\n\n");
		}
		return joined.toString().strip();
	}

	private static String directoryOf(String guidelinePath) {
		var slash = guidelinePath.lastIndexOf('/');
		return slash < 0 ? "" : guidelinePath.substring(0, slash + 1);
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
