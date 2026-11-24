package com.ilkeiapps.skyworx.notulenai.bean.web;

import java.time.Instant;
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
 * A file GitHub listed that is not Java, or is Java but plays none of the reviewed roles, is counted in
 * {@code totalFiles} but not reviewed. {@code findings} holds every PMD violation across the reviewed files,
 * each naming the file it was found in; it is empty when the rules found nothing.
 *
 * @param repository the {@code owner/repo} slug the branch belongs to
 * @param branchName the branch that was listed
 * @param headSha    the commit SHA the tree listing was pinned to
 * @param truncated  true when GitHub capped the listing, so the branch holds more than was seen
 * @param totalFiles the number of files the branch holds at {@code headSha}, reviewed and skipped alike
 * @param author     the head commit's author — who wrote the change; null when GitHub reported none
 * @param committer  the head commit's committer — who created the commit object; null when none
 * @param reportText the generated Bahasa Indonesia report, or null when reporting is off or the model
 *                   produced nothing; populated after the deterministic review, so null at save time
 * @param findings   every PMD violation found across the reviewed files, in file-then-rule order; empty
 *                   when nothing fired
 */
public record CodeReviewBranchFilesResult(String repository, String branchName, String headSha,
		boolean truncated, int totalFiles, String author, String committer, String reportText,
		List<BranchFinding> findings) {

	/**
	 * Returns a copy with the report text attached; every other field is unchanged. The report is produced
	 * by a remote LLM call made after the review is assembled and persisted, so it is set on the result
	 * returned to the caller rather than at construction.
	 */
	public CodeReviewBranchFilesResult withReportText(String reportText) {
		return new CodeReviewBranchFilesResult(repository, branchName, headSha, truncated, totalFiles, author,
				committer, reportText, findings);
	}

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
	 * @param code      the rule's stable, language-neutral identifier, e.g. {@code SKY-TRANSACTION-003}
	 * @param message   the rule's violation message
	 * @param beginLine first line of the offending construct, 1-based
	 * @param endLine   last line of it, 1-based
	 * @param priority  PMD priority, 1 (most severe) to 5
	 * @param evidence  the trimmed source line(s) around {@code beginLine} in the snapshot, with the
	 *                  annotations above it, so the finding shows the offending code without opening the file
	 * @param author        the author of the latest commit that touched {@code path} at the reviewed head —
	 *                      who last changed the file; falls back to the head commit's author on lookup failure
	 * @param committer     the committer of that same commit; falls back to the head commit's committer
	 * @param authorDate    when that change was written (author date); falls back to the head commit's
	 * @param committerDate when that commit object was created (committer date); falls back to the head commit's
	 */
	public record BranchFinding(String path, String ruleName, String code, String message, int beginLine,
			int endLine, int priority, String evidence, String author, String committer, Instant authorDate,
			Instant committerDate) {
	}
}
