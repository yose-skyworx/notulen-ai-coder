package com.ilkeiapps.skyworx.notulenai.entityview.user;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.entity.GeneralStatus;
import com.ilkeiapps.skyworx.notulenai.entity.Role;
import com.ilkeiapps.skyworx.notulenai.entity.User;

@EntityView(User.class)
public interface UserListView {

	@IdMapping
	Long getId();

	String getUsername();

	String getName();

	String getEmail();

	String getPhone();

	RoleView getRoles();

	StatusView getStatus();

	@EntityView(Role.class)
	interface RoleView {
		@IdMapping
		Long getId();

		String getName();

		String getDisplayName();
	}

	@EntityView(GeneralStatus.class)
	interface StatusView {
		@IdMapping
		Integer getId();

		String getCode();

		String getName();
	}
}
