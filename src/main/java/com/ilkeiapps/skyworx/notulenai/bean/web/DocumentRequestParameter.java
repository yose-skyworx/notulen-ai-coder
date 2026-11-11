package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Query parameters for the document list endpoint: the mandatory project scope, an optional search
 * keyword (filename/title), and pagination.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
public class DocumentRequestParameter {

	private Long projectId;
	private String keyword;
	private Integer page;
	private Integer perPage;
}
