package com.ilkeiapps.skyworx.notulenai.bean.enums;

/**
 * The outcome of one whole-branch PMD review, stored on {@code code_review_branch_results.status}.
 * <p>
 * Deliberately small: a branch trigger either produced a result or failed before it could. {@code COMPLETED}
 * carries the findings JSON in {@code result}; {@code FAILED} is written when resolving the branch on GitHub
 * failed after the repository and branch were already resolved, so the attempt is recorded with no result.
 * Persisted as its name ({@code @Enumerated(EnumType.STRING)}), never its ordinal, so adding a member later
 * cannot reinterpret existing rows.
 */
public enum BranchReviewStatus {

	COMPLETED, FAILED
}
