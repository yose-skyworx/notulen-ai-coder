package com.ilkeiapps.skyworx.notulenai.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request/input bean for role create &amp; update. Carries only client-settable fields, so
 * server-controlled columns ({@code status}, {@code createdAt}, …) cannot be mass-assigned.
 * {@code @FieldNameConstants} exposes {@code RoleRequest.Fields.*} for {@code IlKeiValidator}.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class RoleRequest {

	/** Null on create; required on update. */
	private Long id;

	private String name;

	private String displayName;

	private String description;
}
