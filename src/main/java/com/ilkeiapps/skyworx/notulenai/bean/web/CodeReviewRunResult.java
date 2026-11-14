package com.ilkeiapps.skyworx.notulenai.bean.web;

import java.util.List;

import com.ilkeiapps.skyworx.notulenai.entityview.codereview.CodeReviewRunDetailView;
import com.ilkeiapps.skyworx.notulenai.entityview.codereview.CodeReviewRunFileListView;

/**
 * One review run together with the per-file breakdown of what it covered, which is what the trigger
 * returns per repository×branch pair.
 * <p>
 * The run view carries {@code totalFiles}, {@code analyzedFiles} and {@code skippedFiles} as counts. On
 * their own those numbers cannot be reconciled — a run reporting {@code totalFiles=8, analyzedFiles=0}
 * does not say whether eight files were reviewed and found clean or whether all eight were excluded as
 * lock files and binaries. The list answers that, and {@code skipReason} on each entry says why a file
 * was not reviewed.
 * <p>
 * Composed here rather than nested inside {@link CodeReviewRunDetailView}: this codebase maps no
 * collection attribute in any Entity View, and Blaze validates view mappings when the
 * {@code EntityViewManager} is built at startup. Introducing the first one on the endpoint whose entire
 * purpose is to report results would trade a slightly flatter response for a failure mode that stops the
 * application booting. Two queries per run, both using patterns already proven here, is the cheaper
 * trade.
 *
 * @param run   the run's own record, including its terminal status
 * @param files every file the commit touched, in the order GitHub reported them; empty when the run
 *              ended before files were persisted, such as {@code NO_COMMIT} or a failed commit fetch
 */
public record CodeReviewRunResult(CodeReviewRunDetailView run, List<CodeReviewRunFileListView> files) {
}
