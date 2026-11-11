package com.ilkeiapps.skyworx.notulenai.bean.web;

import java.time.LocalDate;

import jakarta.servlet.http.HttpServletRequest;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Setter
@Getter
@ToString(includeFieldNames=true)
public class GenericRequestParameter {

	private Long id;
	private String keyword;
	private LocalDate startDate;
	private LocalDate endDate;
	private Integer statusId;
	private Integer page;
	private Integer perPage;
	private Long device;
	private Long groupId;
	private String medicalRecord;
	private String code;
	private Long parentId;
	private String type;
	private Long measureId;


	public void processFromRequest(HttpServletRequest request) {
		if (request.getParameter("id") != null) {
			this.id = Long.parseLong(request.getParameter("id"));
		}
		if (request.getParameter("keyword") != null) {
			this.keyword = request.getParameter("keyword");
		}
		if (request.getParameter("startDate") != null) {
			LocalDate ld = LocalDate.parse(request.getParameter("startDate"));
			this.startDate = ld;
		}
		if (request.getParameter("endDate") != null) {
			LocalDate ld = LocalDate.parse(request.getParameter("endDate"));
			this.endDate = ld;
		}
		if (request.getParameter("status") != null) {
			this.statusId = Integer.parseInt(request.getParameter("status"));
		}
		if (request.getParameter("page") != null) {
			this.page = Integer.parseInt(request.getParameter("page"));
		}
		if (request.getParameter("device") != null) {
			this.device = Long.parseLong(request.getParameter("device"));
		}
		if (request.getParameter("parentId") != null) {
			this.parentId = Long.parseLong(request.getParameter("parentId"));
		}
		if (request.getParameter("type") != null) {
			this.type = request.getParameter("type");
		}
		if (request.getParameter("measureId") != null) {
			this.measureId = Long.parseLong(request.getParameter("measureId"));
		}
	}
}
