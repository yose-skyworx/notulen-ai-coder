package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Query parameters accepted by the code repository list endpoint. Only the fields the repository query
 * actually uses are exposed: the mandatory project scope, a search keyword matched against owner and
 * repository name, plus pagination.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
public class CodeRepositoryRequestParameter {

	private Long projectId;
	private String keyword;
	private Integer page;
	private Integer perPage;
}
