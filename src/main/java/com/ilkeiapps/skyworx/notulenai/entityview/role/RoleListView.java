package com.ilkeiapps.skyworx.notulenai.entityview.role;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.entity.Role;

@EntityView(Role.class)
public interface RoleListView {

	@IdMapping
	Long getId();

	String getName();

	String getDisplayName();

	String getDescription();
}
