package com.ilkeiapps.skyworx.notulenai.bean.web;

import java.util.List;

import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewRoleType;

/**
 * An early stage of a whole-branch review, rebuilt step by step. The branch has been resolved to a single
 * head commit, every file it holds at that commit has been listed, and each file has been classified by
 * the review role it plays — service, controller, or entity. No content is fetched and nothing is reviewed
 * yet.
 * <p>
 * {@code headSha} is the commit the listing was pinned to, so the files describe one immutable snapshot
 * rather than a branch that may have moved. {@code truncated} is GitHub's own cap on a recursive tree
 * (100,000 entries or 7 MB) surfaced rather than swallowed: when true, the branch holds more files than
 * {@code files} names.
 * <p>
 * Nothing is dropped. Every file GitHub listed is an entry in {@code files}: a classified file carries its
 * {@link ReviewRoleType role} and a null {@code skipReason}, while a file that is not reviewed carries a
 * null role and the reason it was skipped — {@code NOT_JAVA} for a non-Java file, {@code NOT_A_TARGET_ROLE}
 * for a Java file that plays none of the three roles.
 *
 * @param repository the {@code owner/repo} slug the branch belongs to
 * @param branchName the branch that was listed
 * @param headSha    the commit SHA the tree listing was pinned to
 * @param truncated  true when GitHub capped the listing, so {@code files} is incomplete
 * @param totalFiles the number of entries in {@code files}, equal to {@code files.size()}
 * @param files      every file the branch holds at {@code headSha}, in the order GitHub reported them, each
 *                   with its resolved role or the reason it was skipped
 */
public record CodeReviewBranchFilesResult(String repository, String branchName, String headSha,
		boolean truncated, int totalFiles, List<ClassifiedBranchFile> files) {

	/**
	 * One listed file and the outcome of classifying it. Exactly one of {@code role} and {@code skipReason}
	 * is non-null: a reviewable file has a role and no skip reason, a skipped file has a skip reason and no
	 * role.
	 *
	 * @param path       repository-relative path, as GitHub reported it
	 * @param role       the review role the path resolved to, or null when the file was skipped
	 * @param skipReason a stable token explaining why the file was not classified to a role, or null when it
	 *                   was — {@code NOT_JAVA} or {@code NOT_A_TARGET_ROLE}
	 */
	public record ClassifiedBranchFile(String path, ReviewRoleType role, String skipReason) {
	}
}
