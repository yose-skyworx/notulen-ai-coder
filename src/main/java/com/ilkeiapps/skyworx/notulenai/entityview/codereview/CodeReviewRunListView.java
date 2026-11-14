package com.ilkeiapps.skyworx.notulenai.entityview.codereview;

import java.time.Instant;
import java.time.LocalDate;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.entity.CodeRepository;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewBranch;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewRun;
import com.ilkeiapps.skyworx.notulenai.entity.StatusProcess;

/**
 * Read model for the review-history list: which commit was selected, how far the run got, and the
 * file/finding tallies. Leaves out {@code parentCommitSha} and the free-text {@code summary}, which
 * only the detail endpoint needs — the list stays narrow so a long history page is cheap to fetch.
 */
@EntityView(CodeReviewRun.class)
public interface CodeReviewRunListView {

	@IdMapping
	Long getId();

	LocalDate getReviewDate();

	String getStrategy();

	String getSelectedCommitSha();

	String getCommitMessage();

	String getCommitter();

	Instant getCommitDate();

	Instant getStartedAt();

	Instant getCompletedAt();

	Long getDurationMs();

	Integer getTotalFiles();

	Integer getAnalyzedFiles();

	Integer getSkippedFiles();

	Integer getTotalFindings();

	String getErrorMessage();

	RepositoryView getRepository();

	BranchView getBranch();

	StatusView getStatus();

	@EntityView(CodeRepository.class)
	interface RepositoryView {
		@IdMapping
		Long getId();

		String getOwner();

		String getRepoName();
	}

	@EntityView(CodeReviewBranch.class)
	interface BranchView {
		@IdMapping
		Long getId();

		String getBranchName();
	}

	@EntityView(StatusProcess.class)
	interface StatusView {
		@IdMapping
		Integer getId();

		String getCode();

		String getName();
	}
}
