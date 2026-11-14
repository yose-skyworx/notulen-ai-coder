package com.ilkeiapps.skyworx.notulenai.bean.web;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewSeverity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Query parameters accepted by the finding list endpoint: the mandatory project scope, optional run,
 * repository and severity filters, an inclusive {@code yyyy-MM-dd} range over the run's review date, a
 * keyword matched against finding title and file path, plus pagination.
 * <p>
 * Severity is bound as the {@link ReviewSeverity} enum rather than a string, so an unusable value is
 * rejected by the converter with a 400 instead of silently matching nothing. The name must be sent
 * exactly, e.g. {@code HIGH}.
 * <p>
 * {@code @DateTimeFormat} is required here because the application registers no global date
 * formatter, so without it Spring cannot convert the query string to a {@code LocalDate}.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
public class CodeReviewFindingRequestParameter {

	private Long projectId;
	private Long reviewRunId;
	private Long repositoryId;
	private ReviewSeverity severity;
	private String keyword;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate startDate;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate endDate;

	private Integer page;
	private Integer perPage;
}
