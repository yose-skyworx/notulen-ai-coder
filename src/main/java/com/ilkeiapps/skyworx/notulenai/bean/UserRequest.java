package com.ilkeiapps.skyworx.notulenai.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request/input bean for user create &amp; update. Carries only client-settable fields and references
 * gender/role by id instead of binding the {@code Gender}/{@code Role} entities, so server-controlled
 * columns ({@code status}, {@code createdAt}, {@code createdBy}, …) cannot be mass-assigned.
 * {@code @FieldNameConstants} exposes {@code UserRequest.Fields.*} for {@code IlKeiValidator}.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class UserRequest {

	/** Null on create; required on update. */
	private Long id;

	private String name;

	private String username;

	/** Plaintext on the way in; never echoed back in a response. */
	private String password;

	private String email;

	private String phone;

	private String designation;

	private Long genderId;

	private Long roleId;
}
