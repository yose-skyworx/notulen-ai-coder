package com.ilkeiapps.skyworx.notulenai.entityview.codereview;

import java.time.Instant;
import java.time.LocalDate;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.entity.CodeRepository;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewBranch;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewRun;
import com.ilkeiapps.skyworx.notulenai.entity.Project;
import com.ilkeiapps.skyworx.notulenai.entity.StatusProcess;

/**
 * Read model for a single review run. Adds the fields the list omits — the parent commit the diff was
 * taken against, the generated summary, and the owning project — so one call is enough to explain a
 * run without a second round trip.
 */
@EntityView(CodeReviewRun.class)
public interface CodeReviewRunDetailView {

	@IdMapping
	Long getId();

	LocalDate getReviewDate();

	String getStrategy();

	String getSelectedCommitSha();

	String getParentCommitSha();

	String getCommitMessage();

	String getAuthor();

	Instant getAuthorDate();

	String getCommitter();

	Instant getCommitDate();

	Instant getStartedAt();

	Instant getCompletedAt();

	Long getDurationMs();

	Integer getTotalFiles();

	Integer getAnalyzedFiles();

	Integer getSkippedFiles();

	Integer getTotalFindings();

	String getSummary();

	String getErrorMessage();

	Instant getCreatedAt();

	Instant getUpdatedAt();

	ProjectView getProject();

	RepositoryView getRepository();

	BranchView getBranch();

	StatusView getStatus();

	@EntityView(Project.class)
	interface ProjectView {
		@IdMapping
		Long getId();

		String getCode();

		String getName();
	}

	@EntityView(CodeRepository.class)
	interface RepositoryView {
		@IdMapping
		Long getId();

		String getOwner();

		String getRepoName();

		String getDefaultBranch();
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
