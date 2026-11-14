package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Query parameters accepted by the selected-branch list endpoint: the repository whose selection is
 * being read back, plus pagination.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
public class CodeReviewBranchRequestParameter {

	private Long repositoryId;
	private Integer page;
	private Integer perPage;
}
