package com.ilkeiapps.skyworx.notulenai.entityview.codereview;

import java.time.Instant;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.entity.CodeRepository;
import com.ilkeiapps.skyworx.notulenai.entity.Project;
import com.ilkeiapps.skyworx.notulenai.entity.StatusGeneral;

/**
 * Read model for the registered-repository list. Exposes identity, the server-discovered default
 * branch, and project + status summaries. No credential is exposed because none is stored: GitHub
 * access uses one deployment-wide token held in configuration.
 */
@EntityView(CodeRepository.class)
public interface CodeRepositoryListView {

	@IdMapping
	Long getId();

	String getOwner();

	String getRepoName();

	String getDefaultBranch();

	Boolean getEnabled();

	Instant getCreatedAt();

	Instant getUpdatedAt();

	ProjectView getProject();

	StatusView getStatus();

	@EntityView(Project.class)
	interface ProjectView {
		@IdMapping
		Long getId();

		String getCode();

		String getName();
	}

	@EntityView(StatusGeneral.class)
	interface StatusView {
		@IdMapping
		Integer getId();

		String getCode();

		String getName();
	}
}
