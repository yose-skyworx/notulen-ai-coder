package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Query parameters accepted by the project list endpoint. Only the fields the project query actually
 * uses are exposed: a search keyword, an optional client filter, plus pagination.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
public class ProjectRequestParameter {

	private String keyword;
	private Long clientId;
	private Integer page;
	private Integer perPage;
}
