package com.ilkeiapps.skyworx.notulenai.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request/input bean for project <em>update</em>. Identical to {@link ProjectRequest} but adds the
 * mandatory {@code id} of the project being changed. Server-controlled columns ({@code status}) are
 * not exposed and so cannot be mass-assigned.
 * {@code @FieldNameConstants} exposes {@code ProjectUpdateRequest.Fields.*} for {@code IlKeiValidator}.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class ProjectUpdateRequest {

	private Long id;

	private String code;

	private String name;

	/** Foreign key to the owning client. */
	private Long clientId;
}
