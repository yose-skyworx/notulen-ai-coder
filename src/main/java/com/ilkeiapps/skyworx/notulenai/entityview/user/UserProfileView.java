package com.ilkeiapps.skyworx.notulenai.entityview.user;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.entity.Gender;
import com.ilkeiapps.skyworx.notulenai.entity.StatusGeneral;
import com.ilkeiapps.skyworx.notulenai.entity.Role;
import com.ilkeiapps.skyworx.notulenai.entity.User;

/**
 * Read model for the authenticated user's own profile (the {@code /api/user/check} endpoint).
 * Carries the display/identity fields plus gender, role, and status; the password and audit
 * columns are deliberately omitted.
 */
@EntityView(User.class)
public interface UserProfileView {

	@IdMapping
	Long getId();

	String getUsername();

	String getName();

	String getEmail();

	String getPhone();

	String getAddress();

	String getDesignation();

	String getImage();

	String getSignature();

	GenderView getGender();

	RoleView getRoles();

	StatusView getStatus();

	@EntityView(Gender.class)
	interface GenderView {
		@IdMapping
		Integer getId();

		String getCode();

		String getName();
	}

	@EntityView(Role.class)
	interface RoleView {
		@IdMapping
		Long getId();

		String getName();

		String getDisplayName();
	}

	@EntityView(StatusGeneral.class)
	interface StatusView {
		@IdMapping
		Integer getId();

		String getCode();

		String getName();
	}
}
