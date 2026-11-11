package com.ilkeiapps.skyworx.notulenai.entityview.permission;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.entity.Permission;

@EntityView(Permission.class)
public interface PermissionListView {

	@IdMapping
	Long getId();

	String getName();

	String getDisplayName();

	String getDescription();
}
