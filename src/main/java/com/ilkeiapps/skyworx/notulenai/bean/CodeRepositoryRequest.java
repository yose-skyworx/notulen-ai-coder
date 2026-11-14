package com.ilkeiapps.skyworx.notulenai.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request/input bean for code repository <em>create</em>. Carries no id (server-generated) and only
 * client-settable fields, so server-controlled columns cannot be mass-assigned: {@code status} is
 * resolved server-side and {@code defaultBranch} is discovered from the GitHub API rather than
 * accepted from the client.
 * <p>
 * No credential is accepted here: GitHub access uses one deployment-wide token from
 * {@code code-review.github.token}, so registering a repository cannot introduce a secret and there is
 * nothing for a client to mass-assign.
 * <p>
 * {@code @FieldNameConstants} exposes {@code CodeRepositoryRequest.Fields.*} for {@code IlKeiValidator}.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class CodeRepositoryRequest {

	/** Foreign key to the owning project; the project must exist and be {@code AKT}. */
	private Long projectId;

	/** GitHub account or organisation that owns the repository, e.g. {@code ilkeiapps}. */
	private String owner;

	/** Repository name without the owner prefix, e.g. {@code notulen-ai}. */
	private String repoName;

	/** Whether the repository takes part in scheduled reviews. {@code null} means enabled. */
	private Boolean enabled;
}
