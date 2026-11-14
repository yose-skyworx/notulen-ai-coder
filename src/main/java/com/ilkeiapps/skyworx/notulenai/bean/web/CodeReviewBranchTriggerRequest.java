package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request bean for triggering a whole-branch code review: every file one registered branch holds at its
 * current head, rather than the diff of one commit that reached it on one day.
 * <p>
 * Carries two ids and nothing else. The strategy is server-derived, so a caller cannot ask for this
 * endpoint and get a commit review or the other way round.
 * <p>
 * A branch <b>name</b> was deliberately not accepted. {@code code_review_runs.branch_id} is {@code NOT
 * NULL} and part of {@code uq_crr_identity}, so a name would have to be resolved to a registered branch
 * row before a run could be claimed — and resolving it by creating one would let a request silently add
 * review configuration nobody chose. Naming the row also makes the ownership check the query's job: the
 * branch must belong to the repository, so one id cannot be substituted for another's.
 * <p>
 * The ids come from {@code GET /api/code-review/repository/list} and
 * {@code GET /api/code-review/branch/list}.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class CodeReviewBranchTriggerRequest {

	/** Foreign key to the registered repository the branch belongs to. */
	private Long repositoryId;

	/** Foreign key to the branch selected under that repository. */
	private Long branchId;
}
