package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request bean carrying only a user id. Used by operations that identify the target user and carry
 * no other payload, such as the soft delete.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class UserIdRequest {

	private Long id;
}
