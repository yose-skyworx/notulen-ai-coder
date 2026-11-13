package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request payload for project-scoped semantic search: the target project, the free-text query, and an
 * optional top-K override (clamped server-side). Exposes only client-settable fields.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class KnowledgeSearchRequest {

	private Long projectId;
	private String query;
	private Integer topK;
}
