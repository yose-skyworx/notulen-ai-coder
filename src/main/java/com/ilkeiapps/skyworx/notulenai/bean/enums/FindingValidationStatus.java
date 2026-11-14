package com.ilkeiapps.skyworx.notulenai.bean.enums;

/**
 * Outcome of validating one AI-produced finding against the real file snapshot and diff. A finding that
 * fails validation is still persisted with this status and a {@code validation_detail} message rather
 * than being discarded, so the diagnostic survives and the model's reliability stays measurable.
 */
public enum FindingValidationStatus {

	/** Passed every check; safe to surface as a review finding. */
	VALID,
	/** Line or line range does not exist in the referenced snapshot. */
	INVALID_LINE,
	/** Finding names a file other than the one under review. */
	OUT_OF_FILE,
	/** No evidence text supplied, so the finding cannot be checked or acted on. */
	MISSING_EVIDENCE,
	/** Severity outside the allowed set. */
	BAD_SEVERITY,
	/** Confidence outside {@code [0.0, 1.0]}. */
	BAD_CONFIDENCE,
	/** Guideline reference does not match any guideline resolved for this run. */
	UNKNOWN_GUIDELINE
}
