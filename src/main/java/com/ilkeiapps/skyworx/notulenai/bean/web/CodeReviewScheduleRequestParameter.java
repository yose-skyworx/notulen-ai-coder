package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Query parameters accepted by the review-schedule list endpoint: the mandatory project scope, plus
 * pagination. A repository has at most one schedule, so this returns at most one row per repository.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
public class CodeReviewScheduleRequestParameter {

	private Long projectId;
	private Integer page;
	private Integer perPage;
}
