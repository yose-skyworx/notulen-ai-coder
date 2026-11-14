package com.ilkeiapps.skyworx.notulenai.service;

import java.util.List;

import com.ilkeiapps.skyworx.notulenai.bean.enums.FileChangeStatus;
import com.ilkeiapps.skyworx.notulenai.service.FindingValidator.CandidateFinding;
import com.ilkeiapps.skyworx.notulenai.service.ReviewContextBuilder.ReviewContext;

/**
 * The inference seam of the review pipeline: given one file's assembled context, report what the model
 * found.
 * <p>
 * Isolating inference behind this interface is what lets every other stage — commit selection,
 * classification, guideline resolution, context building, validation, persistence — run and be tested
 * with no model in the loop. It also keeps the model's output at arm's length: an implementation returns
 * {@link CandidateFinding} values, which {@link FindingValidator} then checks against the real file
 * before anything is persisted. Nothing downstream trusts an implementation's return value.
 * <p>
 * Exactly one implementation is active, selected by {@code code-review.inference.enabled}. If the
 * property is set to a value neither implementation matches, Spring fails to start rather than silently
 * picking one — an unconfigured inference layer must not look like a review that found nothing.
 */
public interface ReviewAgent {

	/**
	 * @return the findings the model reported, in the order it reported them; empty when it found
	 *         nothing. Never null.
	 */
	List<CandidateFinding> review(ReviewTarget target);

	/**
	 * One file to review.
	 *
	 * @param context the guideline, diff, and source sections, already redacted and already inside the
	 *                configured token budget
	 */
	record ReviewTarget(String filePath, String language, FileChangeStatus changeStatus, ReviewContext context) {
	}
}
