package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request bean carrying only a code repository id. Used by operations that identify the target
 * repository and carry no other payload, such as the soft delete and the branch listing.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class CodeRepositoryIdRequest {

	private Long id;
}
