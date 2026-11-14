package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the {@code code_review_schedules} table: when a repository's selected
 * branches are reviewed. One row per repository, so {@code repository_id} is unique — a repository has
 * a single daily schedule that covers all of its enabled branches.
 * <p>
 * {@code timezone} is stored per row rather than taken from the JVM default so the review window stays
 * correct regardless of where the application is deployed. {@code cron_expression} is a Spring cron
 * string; it is validated on write and is never concatenated into a query.
 */
@Entity
@Table(name = "code_review_schedules",
		uniqueConstraints = @UniqueConstraint(name = "uq_crs_repository", columnNames = {"repository_id"}))
@Setter
@Getter
public class CodeReviewSchedule implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "code_review_schedules_id_generator", sequenceName = "seq_code_review_schedules", initialValue = 1, allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "code_review_schedules_id_generator")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "repository_id", nullable = false)
	private CodeRepository repository;

	@Column(name = "cron_expression", nullable = false, length = 64)
	private String cronExpression;

	/** IANA zone id, e.g. {@code Asia/Jakarta}, used to derive the previous-calendar-day window. */
	@Column(name = "timezone", nullable = false, length = 64)
	private String timezone;

	@Column(name = "enabled", nullable = false)
	private Boolean enabled;

	@Column(name = "created_at")
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	public CodeReviewSchedule() {
	}

}
