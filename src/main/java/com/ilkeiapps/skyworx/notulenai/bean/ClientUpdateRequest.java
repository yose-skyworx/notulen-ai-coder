package com.ilkeiapps.skyworx.notulenai.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request/input bean for client <em>update</em>. Identical to {@link ClientRequest} but adds the
 * mandatory {@code id} of the client being changed. Server-controlled columns ({@code status}) are
 * not exposed and so cannot be mass-assigned.
 * {@code @FieldNameConstants} exposes {@code ClientUpdateRequest.Fields.*} for {@code IlKeiValidator}.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class ClientUpdateRequest {

	private Long id;

	private String code;

	private String name;
}
