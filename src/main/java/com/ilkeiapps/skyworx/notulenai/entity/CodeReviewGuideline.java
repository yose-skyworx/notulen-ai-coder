package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

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

import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewRoleType;

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the {@code code_review_guidelines} table: one version of the review standard
 * for one role. This is the library a run reads from — for each file retrieved from git the run resolves
 * the file's {@link ReviewRoleType}, loads the version of that role which is currently {@code AKT}, and
 * inspects the file against its {@code content}.
 * <p>
 * Nothing is fetched from the reviewed repository to obtain a guideline. The standard is authored and
 * maintained here, so a repository with no conventions file of its own is reviewed exactly like one that
 * has them, and a run cannot fail because the target repository is missing something.
 * <p>
 * <b>A published version is immutable.</b> Its {@code content} is never edited in place; changing the
 * standard means inserting the next {@code version} for that role. That is what makes the reference a run
 * records sufficient: {@code CodeReviewRunFile} points at a guideline row by id, and because the row's
 * text cannot change underneath it, a finding can be re-explained later without this table having to copy
 * the text once per run. {@code content_hash} is the check on that promise, not a substitute for it.
 * <p>
 * <b>Exactly one {@code AKT} per role</b>, so a run never has to choose between two standards. The
 * invariant is enforced by the service that activates a version — it deactivates the previous one in the
 * same transaction — and not by the database: a partial unique index over the active rows is the only way
 * to express it in PostgreSQL, and {@code ddl-auto=update} will not create one.
 * <p>
 * {@code status} references the shared {@link StatusGeneral} master, whose {@code AKT}, {@code NAK} and
 * {@code REM} rows are exactly this table's three states: in force, superseded or withdrawn but kept for
 * the runs that referenced it, and soft-deleted. Every list query filters {@code REM} out. {@code NAK}
 * rows are never removed — a run file may still point at one.
 * <p>
 * {@code content} is passed to the model as the standard to judge against. It is still handled as content
 * and never as an instruction to this application: nothing here interprets what a guideline says, and
 * whoever can write this table cannot thereby direct the service.
 */
@Entity
@Table(name = "code_review_guidelines",
		uniqueConstraints = @UniqueConstraint(name = "uq_crg_role_version", columnNames = {"role_type", "version"}),
		indexes = {
				// The lookup a run performs once per role at the start of every run.
				@Index(name = "idx_crg_role_status", columnList = "role_type,status_id"),
				@Index(name = "idx_crg_status", columnList = "status_id")
		})
@Setter
@Getter
public class CodeReviewGuideline implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "code_review_guidelines_id_generator", sequenceName = "seq_code_review_guidelines", initialValue = 1, allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "code_review_guidelines_id_generator")
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "role_type", nullable = false, length = 16)
	private ReviewRoleType roleType;

	/**
	 * Monotonic within {@code roleType}, assigned by the service as one more than the highest version
	 * that role already has. Never reused, including by a version that was withdrawn before it was ever
	 * activated.
	 */
	@Column(name = "version", nullable = false)
	private Integer version;

	/** Short human label for the version, e.g. the change it introduced. Not used to select anything. */
	@Column(name = "title", nullable = false, length = 255)
	private String title;

	/** The review standard itself, in whatever markup the reviewer is prompted with. */
	@Column(name = "content", columnDefinition = "text", nullable = false)
	private String content;

	/** SHA-256 of {@code content}. Detects an in-place edit of a version that was supposed to be frozen. */
	@Column(name = "content_hash", nullable = false, length = 64)
	private String contentHash;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "status_id", nullable = false)
	private StatusGeneral status;

	@Column(name = "created_at")
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	public CodeReviewGuideline() {
	}

}
