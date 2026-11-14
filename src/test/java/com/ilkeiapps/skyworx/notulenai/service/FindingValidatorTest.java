package com.ilkeiapps.skyworx.notulenai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ilkeiapps.skyworx.notulenai.bean.enums.DiffSide;
import com.ilkeiapps.skyworx.notulenai.bean.enums.FindingValidationStatus;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewSeverity;
import com.ilkeiapps.skyworx.notulenai.service.FindingValidator.CandidateFinding;

/**
 * Covers the checks that keep model output honest. The interesting cases are not the valid finding but
 * the two ways validation can be <em>wrong</em>: reporting a line as invalid when the validator was
 * looking at the wrong side of the diff, and reporting the least useful of several problems.
 */
class FindingValidatorTest {

	private static final String PATH = "src/main/java/com/example/OrderService.java";
	private static final Set<String> GUIDELINES = Set.of("AGENTS.md", "src/main/java/AGENTS.md");

	private final FindingValidator validator = new FindingValidator();

	private static CandidateFinding candidate(String path, Integer line, Integer endLine, DiffSide side,
			ReviewSeverity severity, String evidence, String guideline, BigDecimal confidence) {
		return new CandidateFinding(path, line, endLine, side, null, severity, "correctness",
				"Unchecked null", "The value is dereferenced before the null check.", evidence, guideline,
				"Move the null check above the dereference.", confidence);
	}

	private static CandidateFinding valid() {
		return candidate(PATH, 42, 44, DiffSide.NEW, ReviewSeverity.HIGH, "if (order.getTotal() > 0)",
				"src/main/java/AGENTS.md", new BigDecimal("0.90"));
	}

	private FindingValidationStatus check(CandidateFinding finding) {
		return validator.validate(finding, PATH, DiffSide.NEW, 100, GUIDELINES).status();
	}

	@Test
	void acceptsAFullyGroundedFinding() {
		var verdict = validator.validate(valid(), PATH, DiffSide.NEW, 100, GUIDELINES);
		assertTrue(verdict.valid());
		assertEquals(FindingValidationStatus.VALID, verdict.status());
		assertNull(verdict.detail(), "a valid finding carries no rejection reason");
	}

	@Test
	void rejectsAFindingAboutADifferentFile() {
		var other = candidate("src/main/java/com/example/OtherService.java", 10, null, DiffSide.NEW,
				ReviewSeverity.LOW, "evidence here", null, new BigDecimal("0.50"));
		assertEquals(FindingValidationStatus.OUT_OF_FILE, check(other));
	}

	@Test
	void rejectsAFindingWithNoEvidence() {
		assertEquals(FindingValidationStatus.MISSING_EVIDENCE,
				check(candidate(PATH, 10, null, DiffSide.NEW, ReviewSeverity.LOW, "   ", null,
						new BigDecimal("0.50"))));
	}

	@Test
	void rejectsAMissingSeverity() {
		assertEquals(FindingValidationStatus.BAD_SEVERITY,
				check(candidate(PATH, 10, null, DiffSide.NEW, null, "evidence", null, new BigDecimal("0.50"))));
	}

	@Test
	void rejectsConfidenceOutsideTheUnitInterval() {
		assertEquals(FindingValidationStatus.BAD_CONFIDENCE,
				check(candidate(PATH, 10, null, DiffSide.NEW, ReviewSeverity.LOW, "evidence", null,
						new BigDecimal("1.50"))));
		assertEquals(FindingValidationStatus.BAD_CONFIDENCE,
				check(candidate(PATH, 10, null, DiffSide.NEW, ReviewSeverity.LOW, "evidence", null, null)));
	}

	@Test
	void rejectsLineNumbersOutsideTheSnapshot() {
		assertEquals(FindingValidationStatus.INVALID_LINE,
				check(candidate(PATH, 0, null, DiffSide.NEW, ReviewSeverity.LOW, "evidence", null,
						new BigDecimal("0.50"))));
		assertEquals(FindingValidationStatus.INVALID_LINE,
				check(candidate(PATH, 101, null, DiffSide.NEW, ReviewSeverity.LOW, "evidence", null,
						new BigDecimal("0.50"))));
		assertEquals(FindingValidationStatus.INVALID_LINE,
				check(candidate(PATH, null, null, DiffSide.NEW, ReviewSeverity.LOW, "evidence", null,
						new BigDecimal("0.50"))));
	}

	@Test
	void rejectsAnInvertedOrOverrunningLineRange() {
		assertEquals(FindingValidationStatus.INVALID_LINE,
				check(candidate(PATH, 50, 40, DiffSide.NEW, ReviewSeverity.LOW, "evidence", null,
						new BigDecimal("0.50"))));
		assertEquals(FindingValidationStatus.INVALID_LINE,
				check(candidate(PATH, 50, 120, DiffSide.NEW, ReviewSeverity.LOW, "evidence", null,
						new BigDecimal("0.50"))));
	}

	@Test
	void rejectsACitationOfAGuidelineThatWasNeverLoaded() {
		assertEquals(FindingValidationStatus.UNKNOWN_GUIDELINE,
				check(candidate(PATH, 10, null, DiffSide.NEW, ReviewSeverity.LOW, "evidence",
						"docs/AGENTS.md", new BigDecimal("0.50"))));
	}

	@Test
	void allowsAFindingWithNoGuidelineCitation() {
		// Not every finding is guideline-derived; requiring a citation would reject legitimate ones.
		assertTrue(validator.validate(candidate(PATH, 10, null, DiffSide.NEW, ReviewSeverity.LOW, "evidence",
				null, new BigDecimal("0.50")), PATH, DiffSide.NEW, 100, GUIDELINES).valid());
	}

	@Test
	void doesNotJudgeAnOldSideLineAgainstTheNewSnapshot() {
		// The run holds the NEW snapshot here. Line 500 does not exist in it, but this finding points at
		// the OLD side, so the honest verdict is "unverified", not "invalid".
		var oldSide = candidate(PATH, 500, null, DiffSide.OLD, ReviewSeverity.MEDIUM, "removed code",
				null, new BigDecimal("0.75"));
		var verdict = validator.validate(oldSide, PATH, DiffSide.NEW, 100, GUIDELINES);
		assertTrue(verdict.valid(), "a mismatched side must not produce a false INVALID_LINE");
	}

	@Test
	void judgesAnOldSideLineAgainstAnOldSnapshot() {
		var oldSide = candidate(PATH, 500, null, DiffSide.OLD, ReviewSeverity.MEDIUM, "removed code",
				null, new BigDecimal("0.75"));
		assertEquals(FindingValidationStatus.INVALID_LINE,
				validator.validate(oldSide, PATH, DiffSide.OLD, 100, GUIDELINES).status());
	}

	@Test
	void reportsTheMostFundamentalProblemFirst() {
		// Wrong file AND an impossible line AND no evidence: the file mismatch is the root problem.
		var wrong = candidate("Other.java", 9999, null, DiffSide.NEW, null, "", "nope/AGENTS.md",
				new BigDecimal("9.00"));
		var verdict = validator.validate(wrong, PATH, DiffSide.NEW, 100, GUIDELINES);
		assertEquals(FindingValidationStatus.OUT_OF_FILE, verdict.status());
		assertNotNull(verdict.detail());
	}

	@Test
	void boundsTheRejectionDetail() {
		var longPath = "x".repeat(900) + ".java";
		var verdict = validator.validate(candidate(longPath, 1, null, DiffSide.NEW, ReviewSeverity.LOW,
				"evidence", null, new BigDecimal("0.5")), PATH, DiffSide.NEW, 100, GUIDELINES);
		assertEquals(FindingValidationStatus.OUT_OF_FILE, verdict.status());
		assertTrue(verdict.detail().length() <= 512, "detail must fit the varchar(512) column");
	}
}
