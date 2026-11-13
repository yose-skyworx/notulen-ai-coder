package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request payload for a RAG question against a project knowledge base: the target project, the
 * question, and an optional top-K override for the grounding retrieval (clamped server-side). Exposes
 * only client-settable fields.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class KnowledgeAskRequest {

	private Long projectId;
	private String question;
	private Integer topK;
}
