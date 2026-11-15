package com.ilkeiapps.skyworx.notulenai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;

/**
 * Covers the decisions that change what a model is actually shown: which sections are treated as
 * untrusted repository text, and the order of redaction versus truncation. The token arithmetic itself is
 * deliberately crude in production, so these tests assert the guarantees rather than exact token counts.
 */
class ReviewContextBuilderTest {

	private static final String MARKER = "\n...[truncated]";
	private static final int CHARS_PER_TOKEN = 4;
	private static final String TOKEN_SHAPED = "github_pat_" + "X".repeat(40);

	private final CodeReviewProperties properties = new CodeReviewProperties();
	private final ReviewContextBuilder builder = new ReviewContextBuilder(properties);

	@Test
	void redactsBeforeTruncatingSoNoFragmentOfASecretSurvives() {
		// The secret sits at the very start of the diff. Truncating first would keep the first 85
		// characters, which is the whole token plus part of its body. Redacting first replaces it with a
		// placeholder and only then cuts, so nothing recognisable can survive the cut.
		properties.getContext().setMaxDiffTokens(25);
		var diff = TOKEN_SHAPED + "\n" + "A".repeat(300);

		var context = builder.build("src/OrderService.java", "", diff, null);

		assertFalse(context.diffText().contains("github_pat_"));
		assertFalse(context.diffText().contains("X"));
		assertTrue(context.diffText().startsWith("[REDACTED]"));
		assertTrue(context.diffText().endsWith(MARKER));
		assertTrue(context.diffText().length() <= 25 * CHARS_PER_TOKEN);
	}

	@Test
	void redactsRepositoryTextButNotTheGuideline() {
		// The asymmetry is the point. The diff and the source come from a repository under review and can
		// carry a secret by accident, so they are scrubbed. The guideline is authored in this application
		// and read from its own database — and a security standard is exactly the kind of text that has to
		// quote what a credential looks like. Redacting it would mangle the rule being taught.
		var context = builder.build("src/OrderService.java", "Never commit " + TOKEN_SHAPED,
				TOKEN_SHAPED + "\n" + "A".repeat(40), TOKEN_SHAPED + "\n" + "B".repeat(40));

		assertTrue(context.guidelineText().contains(TOKEN_SHAPED));
		assertFalse(context.diffText().contains("github_pat_"));
		assertFalse(context.sourceText().contains("github_pat_"));
	}

	@Test
	void passesTheGuidelineThroughVerbatimWithinItsOwnBudget() {
		// Selection happens before this class is called: the caller hands over the one active guideline the
		// file's role chose, so there is nothing here to filter, join or reorder.
		var guideline = "Transactions belong on public service methods, not on controllers.";

		assertEquals(guideline, builder.build("src/OrderService.java", guideline, "diff", "source")
				.guidelineText());
	}

	@Test
	void yieldsEmptySectionsRatherThanNullOnes() {
		var context = builder.build("src/OrderService.java", null, null, null);

		assertEquals("", context.guidelineText());
		assertEquals("", context.diffText());
		assertEquals("", context.sourceText());
		assertEquals(0, context.estimatedTokens());
	}

	@Test
	void respectsEachSectionsOwnBudget() {
		properties.getContext().setMaxSourceContextTokens(20);

		var context = builder.build("src/OrderService.java", "", "short diff", "S".repeat(400));

		assertTrue(context.sourceText().endsWith(MARKER));
		assertTrue(context.sourceText().length() <= 20 * CHARS_PER_TOKEN);
		assertEquals("short diff", context.diffText());
	}

	@Test
	void trimsSourceBeforeDiffBeforeGuidelinesWhenTheOverallCeilingIsExceeded() {
		// The per-section budgets sum to more than the ceiling, which is the case the overall ceiling
		// exists for. Surrounding source is the least valuable section, so it absorbs the whole overrun
		// and the diff and the guideline — the change and the rules it is judged against — stay intact.
		var budget = properties.getContext();
		budget.setMaxGuidelineTokens(500);
		budget.setMaxDiffTokens(500);
		budget.setMaxSourceContextTokens(500);
		budget.setMaxInputTokens(250);

		var context = builder.build("src/OrderService.java", "G".repeat(400), "D".repeat(400), "S".repeat(400));

		assertTrue(context.guidelineText().endsWith("G".repeat(400)));
		assertFalse(context.guidelineText().endsWith(MARKER));
		assertEquals("D".repeat(400), context.diffText());
		assertTrue(context.sourceText().endsWith(MARKER));
		assertTrue(context.sourceText().length() < 400);
		assertEquals(250, context.estimatedTokens());
	}
}
