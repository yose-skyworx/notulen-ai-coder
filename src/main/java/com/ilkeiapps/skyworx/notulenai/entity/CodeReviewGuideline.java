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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the {@code code_review_guidelines} table: an immutable snapshot of one
 * {@code AGENTS.md} file exactly as it stood at the reviewed commit, kept so a finding can be
 * re-explained later even after the guideline is edited or deleted on the branch.
 * <p>
 * One row per resolved guideline path per run — a file under {@code backend/src/...} produces both the
 * root and the {@code backend/} guideline, ordered root-first. {@code content_hash} (SHA-256) is what
 * the idempotency check compares: an unchanged hash means the guideline snapshot can be reused instead
 * of re-reviewing a completed file.
 * <p>
 * {@code content_snapshot} is untrusted repository text. It is stored verbatim and is never treated as
 * an instruction to this application.
 */
@Entity
@Table(name = "code_review_guidelines",
		uniqueConstraints = @UniqueConstraint(name = "uq_crg_run_path", columnNames = {"review_run_id", "guideline_path"}),
		indexes = @Index(name = "idx_crg_run", columnList = "review_run_id"))
@Setter
@Getter
public class CodeReviewGuideline implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "code_review_guidelines_id_generator", sequenceName = "seq_code_review_guidelines", initialValue = 1, allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "code_review_guidelines_id_generator")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "review_run_id", nullable = false)
	private CodeReviewRun run;

	/** Repository-relative path, e.g. {@code AGENTS.md} or {@code backend/AGENTS.md}. */
	@Column(name = "guideline_path", nullable = false, length = 512)
	private String guidelinePath;

	/** The commit SHA the content was read at — always the reviewed commit, never the branch HEAD. */
	@Column(name = "guideline_commit_sha", nullable = false, length = 64)
	private String guidelineCommitSha;

	/** SHA-256 of {@code content_snapshot}, used for idempotent re-runs. */
	@Column(name = "content_hash", nullable = false, length = 64)
	private String contentHash;

	@Column(name = "content_snapshot", columnDefinition = "text", nullable = false)
	private String contentSnapshot;

	@Column(name = "created_at")
	private Instant createdAt;

	public CodeReviewGuideline() {
	}

}
