package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

import com.ilkeiapps.skyworx.notulenai.bean.enums.DiffSide;
import com.ilkeiapps.skyworx.notulenai.bean.enums.FindingValidationStatus;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewSeverity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the {@code code_review_findings} table: one issue reported against one file
 * of one {@link CodeReviewRun}.
 * <p>
 * A finding is an AI-produced claim that has been checked, not a confirmed defect — {@code
 * validation_status} records that check and must be surfaced alongside the finding wherever it is
 * displayed. Rows that fail validation are kept with a {@code validation_detail} explaining why, so
 * model reliability stays measurable instead of being hidden by silent discards.
 * <p>
 * {@code side} decides which snapshot {@code line_number} is validated against: {@code NEW} the
 * selected commit, {@code OLD} its parent, which is the only correct reference for deleted code.
 * <p>
 * {@code confidence} is {@code numeric(3,2)} rather than a float: the value is range-checked against
 * {@code [0.0, 1.0]} and returned verbatim in API responses, where a {@code double} would render as
 * {@code 0.9400000000000001}. This is the first exact-decimal column in the schema.
 */
@Entity
@Table(name = "code_review_findings", indexes = {
		@Index(name = "idx_crf_project", columnList = "project_id"),
		@Index(name = "idx_crf_run", columnList = "review_run_id"),
		@Index(name = "idx_crf_run_file", columnList = "run_file_id"),
		@Index(name = "idx_crf_severity", columnList = "severity")
})
@Setter
@Getter
public class CodeReviewFinding implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "code_review_findings_id_generator", sequenceName = "seq_code_review_findings", initialValue = 1, allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "code_review_findings_id_generator")
	private Long id;

	/** Denormalized from the run so project-scoped filters and aggregates need no join. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "review_run_id", nullable = false)
	private CodeReviewRun run;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "run_file_id", nullable = false)
	private CodeReviewRunFile runFile;

	@Column(name = "file_path", nullable = false, length = 1024)
	private String filePath;

	@Column(name = "line_number")
	private Integer lineNumber;

	@Column(name = "end_line_number")
	private Integer endLineNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "side", nullable = false, length = 3)
	private DiffSide side;

	/** Identifier of the guideline rule violated, when the finding is guideline-derived. */
	@Column(name = "rule_id", length = 64)
	private String ruleId;

	@Enumerated(EnumType.STRING)
	@Column(name = "severity", nullable = false, length = 8)
	private ReviewSeverity severity;

	@Column(name = "category", length = 32)
	private String category;

	@Column(name = "title", nullable = false, length = 255)
	private String title;

	@Column(name = "description", columnDefinition = "text")
	private String description;

	/** The code or diff text the finding is based on. Required — a finding without evidence is rejected. */
	@Column(name = "evidence", columnDefinition = "text")
	private String evidence;

	/** Path of the {@code AGENTS.md} guideline this finding cites, when any. */
	@Column(name = "guideline_reference", length = 512)
	private String guidelineReference;

	@Column(name = "recommendation", columnDefinition = "text")
	private String recommendation;

	@Column(name = "confidence", precision = 3, scale = 2)
	private BigDecimal confidence;

	@Enumerated(EnumType.STRING)
	@Column(name = "validation_status", nullable = false, length = 24)
	private FindingValidationStatus validationStatus;

	/** Why validation failed. Null when {@code validation_status} is {@code VALID}. */
	@Column(name = "validation_detail", length = 512)
	private String validationDetail;

	@Column(name = "created_at")
	private Instant createdAt;

	public CodeReviewFinding() {
	}

}
