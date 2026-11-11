package com.ilkeiapps.skyworx.notulenai.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request/input bean for client <em>create</em>. Carries no id (server-generated) and only
 * client-settable fields, so server-controlled columns ({@code status}) cannot be mass-assigned.
 * {@code @FieldNameConstants} exposes {@code ClientRequest.Fields.*} for {@code IlKeiValidator}.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class ClientRequest {

	private String code;

	private String name;
}
