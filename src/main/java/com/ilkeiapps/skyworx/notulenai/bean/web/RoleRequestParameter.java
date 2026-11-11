package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Query parameters accepted by the role list endpoint. Only the fields the role query actually
 * uses are exposed: a search keyword plus pagination.
 */
@Setter
@Getter
@ToString(includeFieldNames=true)
public class RoleRequestParameter {

	private String keyword;
	private Integer page;
	private Integer perPage;
}
