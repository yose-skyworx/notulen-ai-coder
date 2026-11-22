package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

import com.ilkeiapps.skyworx.notulenai.bean.enums.BranchReviewStatus;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for {@code code_review_branch_results}: one execution of the on-demand whole-branch
 * PMD review, stored flat with its whole result as {@code jsonb}.
 * <p>
 * This is deliberately simpler than {@link CodeReviewRun}, which models the scheduled daily review and
 * normalizes its findings into {@link CodeReviewRunFile}/{@link CodeReviewFinding}. A branch trigger is an
 * ad-hoc request with no review-date or strategy identity, and its output is wanted whole rather than
 * queried finding-by-finding, so it keeps only the execution envelope — repository, branch, timing, status —
 * and the entire {@code CodeReviewBranchFilesResult} as a {@code jsonb} {@code result}, mapped the same way
 * the project maps other JSON columns ({@code @JdbcTypeCode(SqlTypes.JSON)}, as on {@link DocumentElement}).
 * <p>
 * {@code repository} and {@code branch} are lazy {@code @ManyToOne}s so the row carries the same
 * {@code repository_id}/{@code branch_id} foreign keys the trigger was given, with referential integrity;
 * they are set from {@code EntityManager.getReference} so persisting a row needs no extra select.
 */
@Entity
@Table(name = "code_review_branch_results", indexes = {
		@Index(name = "idx_crbr_repository", columnList = "repository_id"),
		@Index(name = "idx_crbr_branch", columnList = "branch_id"),
		@Index(name = "idx_crbr_status", columnList = "status")
})
@Setter
@Getter
public class CodeReviewBranchResult implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "code_review_branch_results_id_generator",
			sequenceName = "seq_code_review_branch_results", initialValue = 1, allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "code_review_branch_results_id_generator")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "repository_id", nullable = false)
	private CodeRepository repository;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "branch_id", nullable = false)
	private CodeReviewBranch branch;

	/** When the trigger began, captured before any GitHub call. */
	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	/** When the trigger finished, whether it completed the review or failed resolving the branch. */
	@Column(name = "completed_at")
	private Instant completedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private BranchReviewStatus status;

	/** The whole {@code CodeReviewBranchFilesResult} as jsonb; null on a FAILED row. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "result")
	private Map<String, Object> result;

	/**
	 * A human-readable report rendered from {@link #result}, filled in later (e.g. a generated summary of the
	 * findings). Not written by the trigger yet, so it stays null until that step exists.
	 */
	@Column(name = "report_text", columnDefinition = "text")
	private String reportText;

	public CodeReviewBranchResult() {
	}

}
