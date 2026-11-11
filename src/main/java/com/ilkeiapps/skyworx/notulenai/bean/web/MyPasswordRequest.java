package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request bean for changing the authenticated user's own password. Mirrors the legacy
 * {@code MyDTO} {@code pass1}/{@code pass2} pair; the confirmation is validated in the service.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class MyPasswordRequest {

	private String pass1;
	private String pass2;
}
