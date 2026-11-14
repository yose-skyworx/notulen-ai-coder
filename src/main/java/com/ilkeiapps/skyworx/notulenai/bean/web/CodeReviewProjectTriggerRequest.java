package com.ilkeiapps.skyworx.notulenai.bean.web;

import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request bean for triggering a code review across an entire project: every enabled repository
 * registered under the project, and every enabled branch selected for each of those repositories.
 * <p>
 * Carries {@code projectId} and an optional {@code reviewDate}. The strategy, the commit, and the
 * branch list stay server-derived — accepting a branch here would let a caller point a run at one that
 * was never selected for review, and accepting a commit would bypass the one-commit-per-day rule that
 * {@code uq_crr_identity} is built on.
 * <p>
 * Distinct from {@code ProjectIdRequest}, whose field is {@code id}: this bean names the project
 * explicitly, which keeps the request body unambiguous at an endpoint whose subject is the project
 * rather than a row being deleted.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class CodeReviewProjectTriggerRequest {

	/** Foreign key to the project whose registered repositories are reviewed. */
	private Long projectId;

	/**
	 * Calendar day whose latest commit is reviewed, as {@code yyyy-MM-dd} — Jackson 3 carries
	 * {@code java.time} support in databind, so an ISO date string binds without extra configuration.
	 * <p>
	 * Absent means the previous calendar day in {@code code-review.timezone}, which is what the scheduled
	 * job targets. Naming a day is how a review is aimed at the last day that actually received a commit:
	 * a quiet branch makes the default target come back {@code NO_COMMIT}, and the useful review is then
	 * the one for the day work last landed.
	 * <p>
	 * Every repository in the fan-out reviews the same named day, so the response stays comparable across
	 * them. Re-triggering the same project and day resumes the existing runs rather than creating new
	 * ones, which is what makes a wrong date cheap to correct.
	 */
	private LocalDate reviewDate;
}
