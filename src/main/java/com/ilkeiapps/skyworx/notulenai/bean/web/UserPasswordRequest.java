package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request bean for the admin password reset. Mirrors the legacy {@code User} payload, which carried
 * only the target {@code id} plus the new plaintext {@code password}.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class UserPasswordRequest {

	private Long id;
	private String password;
}
