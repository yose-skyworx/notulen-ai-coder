package com.ilkeiapps.skyworx.notulenai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;
import com.ilkeiapps.skyworx.notulenai.service.AgentsGuidelineResolver.Guideline;

/**
 * Covers the two decisions that change what a model is actually shown: which guidelines count as
 * governing, and the order of redaction versus truncation. The token arithmetic itself is deliberately
 * crude in production, so these tests assert the guarantees rather than exact token counts.
 */
class ReviewContextBuilderTest {

	private static final String MARKER = "\n...[truncated]";
	private static final int CHARS_PER_TOKEN = 4;

	private final CodeReviewProperties properties = new CodeReviewProperties();
	private final ReviewContextBuilder builder = new ReviewContextBuilder(properties);

	private static Guideline guideline(String path, String content) {
		return new Guideline(path, "a".repeat(40), content, "b".repeat(64));
	}

	@Test
	void redactsBeforeTruncatingSoNoFragmentOfASecretSurvives() {
		// The secret sits at the very start of the diff. Truncating first would keep the first 85
		// characters, which is the whole token plus part of its body. Redacting first replaces it with a
		// placeholder and only then cuts, so nothing recognisable can survive the cut.
		properties.getContext().setMaxDiffTokens(25);
		var diff = "github_pat_" + "X".repeat(40) + "\n" + "A".repeat(300);

		var context = builder.build("src/OrderService.java", List.of(), diff, null);

		assertFalse(context.diffText().contains("github_pat_"));
		assertFalse(context.diffText().contains("X"));
		assertTrue(context.diffText().startsWith("[REDACTED]"));
		assertTrue(context.diffText().endsWith(MARKER));
		assertTrue(context.diffText().length() <= 25 * CHARS_PER_TOKEN);
	}

	@Test
	void sendsOnlyTheGuidelinesThatGovernTheFile() {
		var guidelines = List.of(
				guideline("AGENTS.md", "root rules"),
				guideline("backend/AGENTS.md", "backend rules"),
				guideline("backendextra/AGENTS.md", "unrelated prefix rules"),
				guideline("frontend/AGENTS.md", "frontend rules"));

		var context = builder.build("backend/src/main/java/OrderService.java", guidelines, "diff", "source");

		assertTrue(context.guidelineText().contains("### AGENTS.md"));
		assertTrue(context.guidelineText().contains("root rules"));
		assertTrue(context.guidelineText().contains("### backend/AGENTS.md"));
		assertTrue(context.guidelineText().contains("backend rules"));
		assertFalse(context.guidelineText().contains("frontend"));
		// A directory whose name merely starts with the same letters does not govern the file. Matching on
		// the bare prefix rather than the prefix including the separator would pull it in.
		assertFalse(context.guidelineText().contains("backendextra"));
		assertFalse(context.guidelineText().contains("unrelated prefix rules"));
		// Root first: general rules must precede the specific ones that refine them.
		assertTrue(context.guidelineText().indexOf("### AGENTS.md") < context.guidelineText()
				.indexOf("### backend/AGENTS.md"));
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

		var context = builder.build("src/OrderService.java", List.of(), "short diff", "S".repeat(400));

		assertTrue(context.sourceText().endsWith(MARKER));
		assertTrue(context.sourceText().length() <= 20 * CHARS_PER_TOKEN);
		assertEquals("short diff", context.diffText());
	}

	@Test
	void trimsSourceBeforeDiffBeforeGuidelinesWhenTheOverallCeilingIsExceeded() {
		// The per-section budgets sum to more than the ceiling, which is the case the overall ceiling
		// exists for. Surrounding source is the least valuable section, so it absorbs the whole overrun
		// and the diff and the guidelines — the change and the rules it is judged against — stay intact.
		var budget = properties.getContext();
		budget.setMaxGuidelineTokens(500);
		budget.setMaxDiffTokens(500);
		budget.setMaxSourceContextTokens(500);
		budget.setMaxInputTokens(250);

		var context = builder.build("src/OrderService.java", List.of(guideline("AGENTS.md", "G".repeat(400))),
				"D".repeat(400), "S".repeat(400));

		assertTrue(context.guidelineText().endsWith("G".repeat(400)));
		assertFalse(context.guidelineText().endsWith(MARKER));
		assertEquals("D".repeat(400), context.diffText());
		assertTrue(context.sourceText().endsWith(MARKER));
		assertTrue(context.sourceText().length() < 400);
		assertEquals(250, context.estimatedTokens());
	}
}
