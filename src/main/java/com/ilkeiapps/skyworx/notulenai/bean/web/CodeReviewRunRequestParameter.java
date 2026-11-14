package com.ilkeiapps.skyworx.notulenai.bean.web;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Query parameters accepted by the review run list endpoint: the mandatory project scope, optional
 * repository and status filters, an inclusive {@code yyyy-MM-dd} review-date range, plus pagination.
 * <p>
 * The status filter takes a {@code StatusProcess} <em>code</em> such as {@code COMPLETED} or
 * {@code NO_COMMIT}, never a row id, following the project's resolve-status-by-code rule.
 * <p>
 * {@code @DateTimeFormat} is required here because the application registers no global date
 * formatter, so without it Spring cannot convert the query string to a {@code LocalDate}.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
public class CodeReviewRunRequestParameter {

	private Long projectId;
	private Long repositoryId;
	private String statusCode;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate startDate;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate endDate;

	private Integer page;
	private Integer perPage;
}
