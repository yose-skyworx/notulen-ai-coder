package com.ilkeiapps.skyworx.notulenai.bean.web;

import java.util.List;

import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewRoleType;

/**
 * A whole-branch review by the deterministic {@code PMD} mechanism. The branch has been resolved to a
 * single head commit, every file it holds at that commit has been listed, each file has been classified by
 * the review role it plays — service, controller, or entity — and every classified file's content has been
 * fetched at that commit and run through the PMD rule set. No model is in the loop: the findings are
 * deterministic static analysis, not an LLM's judgement.
 * <p>
 * {@code headSha} is the commit the listing was pinned to, so the files describe one immutable snapshot
 * rather than a branch that may have moved, and the finding line numbers index that same snapshot.
 * {@code truncated} is GitHub's own cap on a recursive tree (100,000 entries or 7 MB) surfaced rather than
 * swallowed: when true, the branch holds more files than {@code files} names.
 * <p>
 * {@code files} lists only the files that were reviewed — those that classified to a service, controller,
 * or entity role. A file GitHub listed that is not Java, or is Java but plays none of those roles, is
 * counted in {@code totalFiles} but not listed, so the response carries the branch's size without the noise
 * of every skipped path. {@code findings} holds every PMD violation across those reviewed files, each naming
 * the file it was found in; it is empty when the rules found nothing.
 *
 * @param repository the {@code owner/repo} slug the branch belongs to
 * @param branchName the branch that was listed
 * @param headSha    the commit SHA the tree listing was pinned to
 * @param truncated  true when GitHub capped the listing, so the branch holds more than was seen
 * @param totalFiles the number of files the branch holds at {@code headSha}, reviewed and skipped alike
 * @param files      the files that classified to a role and were reviewed, in the order GitHub reported
 *                   them; skipped files are not listed
 * @param findings   every PMD violation found across the reviewed files, in file-then-rule order; empty
 *                   when nothing fired
 */
public record CodeReviewBranchFilesResult(String repository, String branchName, String headSha,
		boolean truncated, int totalFiles, List<ClassifiedBranchFile> files, List<BranchFinding> findings) {

	/**
	 * One reviewed file and the role it was classified as.
	 *
	 * @param path repository-relative path, as GitHub reported it
	 * @param role the review role the path resolved to; never null, since only reviewed files are listed
	 */
	public record ClassifiedBranchFile(String path, ReviewRoleType role) {
	}

	/**
	 * One PMD violation found in one classified file. The line numbers are 1-based offsets into the file's
	 * content at {@code headSha}, so they point into the exact snapshot that was listed.
	 *
	 * @param path      repository-relative path of the file the violation was found in
	 * @param ruleName  the PMD rule that fired, e.g. {@code NoRestCallInTransaction}
	 * @param message   the rule's violation message
	 * @param beginLine first line of the offending construct, 1-based
	 * @param endLine   last line of it, 1-based
	 * @param priority  PMD priority, 1 (most severe) to 5
	 */
	public record BranchFinding(String path, String ruleName, String message, int beginLine, int endLine,
			int priority) {
	}
}
