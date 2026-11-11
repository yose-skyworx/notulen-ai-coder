package com.ilkeiapps.skyworx.notulenai.entityview.gender;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.entity.Gender;

@EntityView(Gender.class)
public interface GenderListView {

	@IdMapping
	Integer getId();

	String getCode();

	String getName();
}
