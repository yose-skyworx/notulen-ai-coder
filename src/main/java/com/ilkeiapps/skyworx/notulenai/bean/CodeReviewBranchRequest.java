package com.ilkeiapps.skyworx.notulenai.bean;

import java.util.List;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request/input bean for selecting the branches of a repository that take part in review.
 * <p>
 * The supplied list <em>replaces</em> the repository's whole branch selection, so a client never has
 * to track branch ids. Replacement is applied by flag, not by deletion: branches still named are
 * enabled, branches no longer named are disabled, and branches named for the first time are inserted.
 * Rows are never removed because {@code code_review_runs.branch_id} references them — deleting a
 * deselected branch would orphan the history of every run that reviewed it. An empty or absent list
 * therefore disables the whole selection, leaving the repository with nothing to review.
 * <p>
 * {@code @FieldNameConstants} exposes {@code CodeReviewBranchRequest.Fields.*} for
 * {@code IlKeiValidator}.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class CodeReviewBranchRequest {

	/** Foreign key to the repository whose branch selection is being replaced. */
	private Long repositoryId;

	/** Full branch names, e.g. {@code dev}. Replaces the existing selection. */
	private List<String> branchNames;

	/** Whether the selected branches are reviewed by the scheduler. {@code null} means enabled. */
	private Boolean enabled;
}
