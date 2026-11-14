package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the {@code code_review_runs} table: one execution of the daily review for a
 * single repository branch on a single calendar day. {@code status} references the shared
 * {@link StatusProcess} master, consistent with {@link DocumentProcessingJob} (no enum, no hardcoded id).
 * <p>
 * {@code uq_crr_identity} is the duplicate-run guard: the same repository, branch, review date and
 * strategy can only ever produce one run, so a re-trigger after a crash resumes the existing row
 * instead of starting a second review. Claiming a run therefore relies on the constraint rather than on
 * a check-then-insert, which would race.
 * <p>
 * {@code project} is denormalized from the repository (the pattern {@link DocumentChunk} and
 * {@link DocumentProcessingJob} already follow) so project-scoped authorization checks and dashboard
 * aggregates do not have to join through the repository.
 * <p>
 * {@code review_date} is a {@link LocalDate} because it names a calendar day in the schedule's zone,
 * not an instant; the UTC bounds actually sent to GitHub are derived from it at query time.
 */
@Entity
@Table(name = "code_review_runs",
		uniqueConstraints = @UniqueConstraint(name = "uq_crr_identity",
				columnNames = {"repository_id", "branch_id", "review_date", "strategy"}),
		indexes = {
				@Index(name = "idx_crr_project", columnList = "project_id"),
				@Index(name = "idx_crr_repository", columnList = "repository_id"),
				@Index(name = "idx_crr_branch", columnList = "branch_id"),
				@Index(name = "idx_crr_review_date", columnList = "review_date"),
				@Index(name = "idx_crr_status", columnList = "status_id")
		})
@Setter
@Getter
public class CodeReviewRun implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "code_review_runs_id_generator", sequenceName = "seq_code_review_runs", initialValue = 1, allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "code_review_runs_id_generator")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "repository_id", nullable = false)
	private CodeRepository repository;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "branch_id", nullable = false)
	private CodeReviewBranch branch;

	/** The calendar day under review, in the schedule's configured zone. */
	@Column(name = "review_date", nullable = false)
	private LocalDate reviewDate;

	/** Commit-selection strategy, e.g. {@code LATEST_COMMIT_OF_DAY}. Part of the run's identity. */
	@Column(name = "strategy", nullable = false, length = 32)
	private String strategy;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "status_id", nullable = false)
	private StatusProcess status;

	/** Exact SHA reviewed. All source and guideline retrieval uses this, never the branch HEAD. */
	@Column(name = "selected_commit_sha", length = 64)
	private String selectedCommitSha;

	/** First parent of the selected commit; null for a root commit, which diffs against the empty tree. */
	@Column(name = "parent_commit_sha", length = 64)
	private String parentCommitSha;

	@Column(name = "commit_message", length = 2048)
	private String commitMessage;

	@Column(name = "committer", length = 255)
	private String committer;

	/** Committer date (not author date) — the criterion the commit was selected by. */
	@Column(name = "commit_date")
	private Instant commitDate;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "duration_ms")
	private Long durationMs;

	@Column(name = "total_files")
	private Integer totalFiles;

	@Column(name = "analyzed_files")
	private Integer analyzedFiles;

	@Column(name = "skipped_files")
	private Integer skippedFiles;

	@Column(name = "total_findings")
	private Integer totalFindings;

	@Column(name = "summary", columnDefinition = "text")
	private String summary;

	@Column(name = "error_message", length = 4096)
	private String errorMessage;

	@Column(name = "created_at")
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	public CodeReviewRun() {
	}

}
