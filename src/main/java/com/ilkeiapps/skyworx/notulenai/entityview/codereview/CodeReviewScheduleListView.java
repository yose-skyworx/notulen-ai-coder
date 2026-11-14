package com.ilkeiapps.skyworx.notulenai.entityview.codereview;

import java.time.Instant;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.entity.CodeRepository;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewSchedule;
import com.ilkeiapps.skyworx.notulenai.entity.Project;

/**
 * Read model for a repository's daily review schedule. A repository has at most one, so the list is
 * naturally one row per repository.
 */
@EntityView(CodeReviewSchedule.class)
public interface CodeReviewScheduleListView {

	@IdMapping
	Long getId();

	String getCronExpression();

	String getTimezone();

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

		Boolean getEnabled();

		ProjectView getProject();

		@EntityView(Project.class)
		interface ProjectView {
			@IdMapping
			Long getId();

			String getCode();

			String getName();
		}
	}
}
