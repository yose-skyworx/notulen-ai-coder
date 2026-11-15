package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

import com.ilkeiapps.skyworx.notulenai.bean.enums.FileChangeStatus;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewRoleType;

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
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the {@code code_review_run_files} table: the review outcome for one changed
 * file within a {@link CodeReviewRun}. The review strategy is strictly file-by-file, so this row is the
 * unit of work, the unit of failure isolation, and the unit of idempotency.
 * <p>
 * A failure here never aborts the run — the orchestrator records {@code FAILED} on this row and
 * continues to the next file, which is what makes a run with any failed file
 * {@code COMPLETED_WITH_WARNING} rather than {@code FAILED}.
 * <p>
 * Skipped files are rows too, not omissions: {@code skip_reason} always says why (binary, excluded
 * path, oversized, no patch content, a file that plays none of the reviewed roles, a role with no active
 * guideline, past the whole-branch file ceiling, inference disabled), so nothing is silently dropped.
 * <p>
 * {@code diff_hash} plus the run's commit SHA, this {@code file_path}, and the {@code guideline_id} this
 * row was reviewed against form the idempotency key: a {@code COMPLETED} row whose key is unchanged is not
 * re-reviewed. Recording the guideline by id is what makes that work — publishing a new version changes
 * the id, so a re-trigger after the standard was edited genuinely re-reviews, while a re-trigger with the
 * standard untouched reuses the completed rows.
 * <p>
 * {@code segment_count} and {@code segments_completed} track the splitting of an oversized file, and a
 * file must not reach {@code COMPLETED} until every segment has.
 */
@Entity
@Table(name = "code_review_run_files",
		uniqueConstraints = @UniqueConstraint(name = "uq_crrf_run_file", columnNames = {"review_run_id", "file_path"}),
		indexes = {
				@Index(name = "idx_crrf_run", columnList = "review_run_id"),
				@Index(name = "idx_crrf_status", columnList = "status_id")
		})
@Setter
@Getter
public class CodeReviewRunFile implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "code_review_run_files_id_generator", sequenceName = "seq_code_review_run_files", initialValue = 1, allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "code_review_run_files_id_generator")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "review_run_id", nullable = false)
	private CodeReviewRun run;

	/**
	 * Repository-relative path at 1024 characters rather than the 512 used for
	 * {@code knowledge_documents.original_filename}: GitHub paths are not length-limited to 512 and an
	 * over-long value must not fail the whole run.
	 */
	@Column(name = "file_path", nullable = false, length = 1024)
	private String filePath;

	/** Pre-rename path, set only when {@code change_status} is {@code RENAMED}. */
	@Column(name = "old_file_path", length = 1024)
	private String oldFilePath;

	@Enumerated(EnumType.STRING)
	@Column(name = "change_status", nullable = false, length = 16)
	private FileChangeStatus changeStatus;

	/** Language inferred from the file extension; informational, never used to decide eligibility. */
	@Column(name = "language", length = 32)
	private String language;

	/**
	 * The review role this file resolved to, which is what selected its guideline. Null when no role was
	 * resolved — either the file plays none of them, which is why it was skipped, or a commit-based run
	 * skipped it before its content was fetched and the annotation tier could decide.
	 * <p>
	 * Deliberately set on a skipped row whenever the role is known. "This is a service and there is no
	 * active service standard" says more than the skip reason alone, and it is what makes
	 * {@code NO_GUIDELINE} rows countable per role instead of being one undifferentiated pile.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "role_type", length = 16)
	private ReviewRoleType roleType;

	/**
	 * The guideline version this file was reviewed against. Referenced by id rather than snapshotted,
	 * because a published {@link CodeReviewGuideline} is immutable — the id pins the exact wording, so a
	 * finding can be re-explained years later without this table holding a copy of the text per run.
	 * <p>
	 * Null on a skipped row, including a file whose role had no active version: without a standard there
	 * is nothing to review against, and that is recorded as a skip rather than run with an empty guideline.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "guideline_id")
	private CodeReviewGuideline guideline;

	@Column(name = "additions")
	private Integer additions;

	@Column(name = "deletions")
	private Integer deletions;

	/**
	 * Identifies the content this row's review covered, and is part of the idempotency key. For a
	 * commit-based run it is the SHA-256 of the diff text; for a whole-branch run, which has no diff, it is
	 * the git blob SHA GitHub reported for the file — 40 hex characters, and it changes exactly when the
	 * content does. Both are content-derived, so one column serves both strategies without a schema change.
	 */
	@Column(name = "diff_hash", length = 64)
	private String diffHash;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "status_id", nullable = false)
	private StatusProcess status;

	/** Why this file was not reviewed. Always populated when the status is {@code SKIPPED}. */
	@Column(name = "skip_reason", length = 512)
	private String skipReason;

	@Column(name = "segment_count")
	private Integer segmentCount;

	@Column(name = "segments_completed")
	private Integer segmentsCompleted;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "error_message", length = 4096)
	private String errorMessage;

	@Column(name = "created_at")
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	public CodeReviewRunFile() {
	}

}
