package com.ilkeiapps.skyworx.notulenai.service;

import java.math.BigDecimal;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.bean.enums.DiffSide;
import com.ilkeiapps.skyworx.notulenai.bean.enums.FindingValidationStatus;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewSeverity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Checks an AI-produced finding against the facts of the file before it is persisted.
 * <p>
 * A finding is a claim, not a defect. The model can cite a line that does not exist, quote evidence it
 * invented, or attribute a rule to a guideline that was never loaded. Every one of those is checkable
 * against data the run already has, so it is checked.
 * <p>
 * Rejected findings are persisted with their status and a reason rather than discarded. Silently
 * dropping them would make the pipeline look more accurate than it is, and accuracy is the one property
 * of a review tool worth measuring — see {@code FindingValidationStatus}.
 * <p>
 * Checks run in a fixed order and the first failure wins, so the recorded reason is always the most
 * fundamental one: a finding about the wrong file is {@code OUT_OF_FILE} even if its line number is also
 * nonsense.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FindingValidator {

	/** Upper bound matching {@code code_review_findings.validation_detail}. */
	private static final int MAX_DETAIL = 512;

	private static final BigDecimal MIN_CONFIDENCE = BigDecimal.ZERO;
	private static final BigDecimal MAX_CONFIDENCE = BigDecimal.ONE;

	/** One finding as the model reported it, before any check has been applied. */
	public record CandidateFinding(String filePath, Integer lineNumber, Integer endLineNumber, DiffSide side,
			String ruleId, ReviewSeverity severity, String category, String title, String description,
			String evidence, String guidelineReference, String recommendation, BigDecimal confidence) {
	}

	/**
	 * @param status {@code VALID} when every check passed
	 * @param detail why validation failed, or null when it did not
	 */
	public record Validated(FindingValidationStatus status, String detail) {

		public boolean valid() {
			return status == FindingValidationStatus.VALID;
		}
	}

	/**
	 * @param filePathUnderReview the file this candidate was produced for
	 * @param snapshotSide        which side of the diff {@code snapshotLineCount} describes
	 * @param snapshotLineCount   lines in that snapshot, or 0 when it could not be read
	 * @param knownGuidelines     paths of the {@code AGENTS.md} files resolved for this run
	 */
	public Validated validate(CandidateFinding candidate, String filePathUnderReview, DiffSide snapshotSide,
			int snapshotLineCount, Set<String> knownGuidelines) {
		if (candidate == null) {
			return reject(FindingValidationStatus.MISSING_EVIDENCE, "no finding was supplied");
		}

		if (candidate.filePath() == null || !candidate.filePath().equals(filePathUnderReview)) {
			return reject(FindingValidationStatus.OUT_OF_FILE,
					"finding names '" + candidate.filePath() + "' but was produced for '" + filePathUnderReview + "'");
		}

		if (candidate.evidence() == null || candidate.evidence().isBlank()) {
			return reject(FindingValidationStatus.MISSING_EVIDENCE, "no evidence text supplied");
		}

		// Severity is an enum, so an out-of-set value cannot reach here — the only unrepresentable case
		// is null, which is what this check actually catches. That is the point of using an enum.
		if (candidate.severity() == null) {
			return reject(FindingValidationStatus.BAD_SEVERITY, "severity is missing");
		}

		var confidence = candidate.confidence();
		if (confidence == null || confidence.compareTo(MIN_CONFIDENCE) < 0 || confidence.compareTo(MAX_CONFIDENCE) > 0) {
			return reject(FindingValidationStatus.BAD_CONFIDENCE,
					"confidence " + (confidence == null ? "is missing" : confidence.toPlainString())
							+ " is outside [0.00, 1.00]");
		}

		var lineCheck = checkLines(candidate, snapshotSide, snapshotLineCount);
		if (lineCheck != null) {
			return lineCheck;
		}

		var reference = candidate.guidelineReference();
		if (reference != null && !reference.isBlank()
				&& (knownGuidelines == null || !knownGuidelines.contains(reference.trim()))) {
			return reject(FindingValidationStatus.UNKNOWN_GUIDELINE,
					"guideline '" + reference + "' was not resolved for this run");
		}

		return new Validated(FindingValidationStatus.VALID, null);
	}

	/**
	 * Validates the line reference only when the candidate points at the snapshot we actually hold.
	 * Checking an {@code OLD}-side line against the {@code NEW} file — or the reverse — would produce a
	 * confident wrong verdict, so a mismatched side leaves the line unverified instead. That is the
	 * honest outcome: the run did not read that snapshot.
	 *
	 * @return a rejection, or null when the line reference is acceptable
	 */
	private Validated checkLines(CandidateFinding candidate, DiffSide snapshotSide, int snapshotLineCount) {
		if (candidate.side() == null || candidate.side() != snapshotSide || snapshotLineCount <= 0) {
			return null;
		}

		var line = candidate.lineNumber();
		if (line == null) {
			return reject(FindingValidationStatus.INVALID_LINE,
					"no line number supplied; the file has " + snapshotLineCount + " line(s)");
		}
		if (line < 1 || line > snapshotLineCount) {
			return reject(FindingValidationStatus.INVALID_LINE,
					"line " + line + " is outside the file's 1.." + snapshotLineCount + " range");
		}

		var endLine = candidate.endLineNumber();
		if (endLine != null && (endLine < line || endLine > snapshotLineCount)) {
			return reject(FindingValidationStatus.INVALID_LINE,
					"line range " + line + ".." + endLine + " is outside the file's 1.." + snapshotLineCount
							+ " range");
		}
		return null;
	}

	private Validated reject(FindingValidationStatus status, String detail) {
		var trimmed = detail != null && detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail;
		log.debug("finding rejected as {}: {}", status, trimmed);
		return new Validated(status, trimmed);
	}
}
