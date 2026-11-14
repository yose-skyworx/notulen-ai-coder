package com.ilkeiapps.skyworx.notulenai.bean;

import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request/input bean for manually triggering a review run, which git.md §4.2 requires for testing and
 * operational purposes.
 * <p>
 * Carries no {@code strategy}: a manual trigger is recorded as {@code MANUAL} by the service, which is
 * what keeps it from colliding with the {@code DAILY} run for the same repository, branch and review
 * date under the {@code uq_crr_identity} duplicate-run guard.
 * <p>
 * {@code @FieldNameConstants} exposes {@code CodeReviewTriggerRequest.Fields.*} for
 * {@code IlKeiValidator}.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class CodeReviewTriggerRequest {

	/** Foreign key to the repository to review. */
	private Long repositoryId;

	/**
	 * Branch to review. Absent means every enabled branch selected for the repository, which is what
	 * the scheduler does; naming one reviews just that branch.
	 */
	private String branchName;

	/**
	 * Calendar day whose latest commit is reviewed, as {@code yyyy-MM-dd}. Absent means the previous
	 * calendar day in the configured timezone, matching the scheduled job's target.
	 */
	private LocalDate reviewDate;
}
