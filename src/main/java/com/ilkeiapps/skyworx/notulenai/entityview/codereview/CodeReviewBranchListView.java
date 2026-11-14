package com.ilkeiapps.skyworx.notulenai.entityview.codereview;

import java.time.Instant;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.entity.CodeRepository;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewBranch;

/**
 * Read model for the branches selected for review, so an operator can read back the selection made
 * through {@code CodeReviewBranchRequest}. Nesting the repository is a to-one join and so does not
 * multiply rows.
 */
@EntityView(CodeReviewBranch.class)
public interface CodeReviewBranchListView {

	@IdMapping
	Long getId();

	String getBranchName();

	Boolean getEnabled();

	Instant getCreatedAt();

	Instant getUpdatedAt();

	RepositoryView getRepository();

	@EntityView(CodeRepository.class)
	interface RepositoryView {
		@IdMapping
		Long getId();

		String getOwner();

		String getRepoName();
	}
}
