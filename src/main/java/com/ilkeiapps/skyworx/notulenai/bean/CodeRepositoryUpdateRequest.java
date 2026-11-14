package com.ilkeiapps.skyworx.notulenai.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request/input bean for code repository <em>update</em>. Identical to {@link CodeRepositoryRequest}
 * but adds the mandatory {@code id} of the repository being changed. Server-controlled columns
 * ({@code status}, {@code defaultBranch}) are not exposed and so cannot be mass-assigned.
 * <p>
 * Null semantics are explicit: {@code enabled} absent or {@code null} leaves the current flag
 * untouched, while every other field is a full replacement — supply the current value to keep it.
 * <p>
 * {@code @FieldNameConstants} exposes {@code CodeRepositoryUpdateRequest.Fields.*} for
 * {@code IlKeiValidator}.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class CodeRepositoryUpdateRequest {

	private Long id;

	/** Foreign key to the owning project; the project must exist and be {@code AKT}. */
	private Long projectId;

	/** GitHub account or organisation that owns the repository, e.g. {@code ilkeiapps}. */
	private String owner;

	/** Repository name without the owner prefix, e.g. {@code notulen-ai}. */
	private String repoName;

	/** Whether the repository takes part in scheduled reviews. {@code null} means enabled. */
	private Boolean enabled;
}
