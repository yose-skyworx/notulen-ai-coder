package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request bean carrying only a review run id. Used by the run detail endpoint and by the per-file and
 * finding listings when they are scoped to a single run.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class CodeReviewRunIdRequest {

	private Long id;
}
