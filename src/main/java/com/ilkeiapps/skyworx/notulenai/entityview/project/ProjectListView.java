package com.ilkeiapps.skyworx.notulenai.entityview.project;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.entity.Client;
import com.ilkeiapps.skyworx.notulenai.entity.GeneralStatus;
import com.ilkeiapps.skyworx.notulenai.entity.Project;

@EntityView(Project.class)
public interface ProjectListView {

	@IdMapping
	Long getId();

	String getCode();

	String getName();

	ClientView getClient();

	StatusView getStatus();

	@EntityView(Client.class)
	interface ClientView {
		@IdMapping
		Long getId();

		String getCode();

		String getName();
	}

	@EntityView(GeneralStatus.class)
	interface StatusView {
		@IdMapping
		Integer getId();

		String getCode();

		String getName();
	}
}
