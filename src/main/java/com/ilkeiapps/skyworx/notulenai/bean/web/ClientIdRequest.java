package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request bean carrying only a client id. Used by operations that identify the target client and
 * carry no other payload, such as the soft delete.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class ClientIdRequest {

	private Long id;
}
