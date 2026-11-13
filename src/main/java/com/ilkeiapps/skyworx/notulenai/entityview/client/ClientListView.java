package com.ilkeiapps.skyworx.notulenai.entityview.client;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.entity.Client;
import com.ilkeiapps.skyworx.notulenai.entity.StatusGeneral;

@EntityView(Client.class)
public interface ClientListView {

	@IdMapping
	Long getId();

	String getCode();

	String getName();

	StatusView getStatus();

	@EntityView(StatusGeneral.class)
	interface StatusView {
		@IdMapping
		Integer getId();

		String getCode();

		String getName();
	}
}
