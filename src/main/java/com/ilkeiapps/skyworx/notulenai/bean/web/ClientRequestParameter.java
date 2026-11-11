package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Query parameters accepted by the client list endpoint. Only the fields the client query actually
 * uses are exposed: a search keyword plus pagination.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
public class ClientRequestParameter {

	private String keyword;
	private Integer page;
	private Integer perPage;
}
