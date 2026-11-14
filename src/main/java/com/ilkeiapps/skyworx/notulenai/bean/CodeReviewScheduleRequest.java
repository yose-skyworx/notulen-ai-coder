package com.ilkeiapps.skyworx.notulenai.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request/input bean for a repository's daily review schedule.
 * <p>
 * Carries no id: a repository has at most one schedule (enforced by the {@code uq_crs_repository}
 * unique constraint on {@code repository_id}), so saving is an upsert keyed by the natural key
 * {@code repositoryId}. A separate update payload would add nothing but a second way to address the
 * same row.
 * <p>
 * {@code @FieldNameConstants} exposes {@code CodeReviewScheduleRequest.Fields.*} for
 * {@code IlKeiValidator}.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class CodeReviewScheduleRequest {

	/** Foreign key to the repository this schedule drives; also the upsert key. */
	private Long repositoryId;

	/**
	 * Spring six-field cron expression, e.g. {@code 0 5 0 * * *} for 00:05 daily. Parsed with
	 * {@code CronExpression.parse} before it is stored, so an unusable value is rejected at the
	 * boundary instead of failing silently at scheduling time.
	 */
	private String cronExpression;

	/** IANA zone the cron expression is evaluated in, e.g. {@code Asia/Jakarta}. */
	private String timezone;

	/** Whether the scheduler fires this schedule. {@code null} means enabled. */
	private Boolean enabled;
}
