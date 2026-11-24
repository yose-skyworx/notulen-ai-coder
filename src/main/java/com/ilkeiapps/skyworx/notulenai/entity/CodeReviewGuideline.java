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

import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewMechanism;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewRoleType;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewSeverity;

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the {@code code_review_guidelines} table: <b>one review rule</b> for one role.
 * A row is not a document. This is the library a run reads from — for each file retrieved from git the run
 * resolves the file's {@link ReviewRoleType}, loads every rule of that role which is currently {@code AKT},
 * and checks the file against each one.
 * <p>
 * Nothing is fetched from the reviewed repository to obtain a rule. The library is authored and maintained
 * here, so a repository with no conventions file of its own is reviewed exactly like one that has them, and
 * a run cannot fail because the target repository is missing something.
 * <p>
 * {@code mechanism} is the discriminator the runner branches on, and it is what decides how {@code content}
 * is read: prose that goes into a prompt for {@code LLM}, a reference into the ruleset shipped with
 * {@code pmd-java} for {@code PMD}. One column serves both because the mechanism already says which
 * interpretation applies; the pairing is a documented convention rather than a database CHECK, since
 * {@code ddl-auto=update} will not add one. {@code PMD} is also what lets a run produce findings while
 * inference is disabled, so the mechanism — not the role — decides whether a file is reviewable.
 * <p>
 * <b>One row per {@code (role_type, code)}, forever.</b> The unique constraint covers every status, not
 * just the active ones, so a rule is never superseded by a second row: withdrawing it flips
 * {@code status_id}, and reinstating it edits the same row back to {@code AKT}. A withdrawn {@code code}
 * therefore stays occupied, and its earlier wording is overwritten in place.
 * <p>
 * That is a deliberate loss. This table cannot answer "what did the standard say in March" — {@code
 * updated_at} and the status transitions are the whole trail. What survives is the part that matters for
 * re-explaining a verdict: {@code code_review_findings} snapshots the {@code title}, {@code description}
 * and {@code evidence} of the rule that fired, and cites the {@code code} in its {@code rule_id}. A history
 * table earns its place only if audit history is ever actually required.
 * <p>
 * {@code status} references the shared {@link StatusGeneral} master, whose {@code AKT}, {@code NAK} and
 * {@code REM} rows are this table's three states: in force, withdrawn, and soft-deleted. Every list query
 * filters {@code REM} out. {@code NAK} rows are never removed — the {@code code} they hold is still
 * occupied, and a finding may still cite it.
 * <p>
 * {@code content} is handled as content and never as an instruction to this application: for {@code LLM}
 * nothing here interprets what the prose says, and for {@code PMD} the reference is resolved against the
 * shipped ruleset rather than evaluated. Whoever can write this table cannot thereby direct the service.
 */
@Entity
@Table(name = "code_review_guidelines",
		uniqueConstraints = @UniqueConstraint(name = "uq_crg_role_code", columnNames = {"role_type", "code"}),
		indexes = {
				// The lookup a run performs once per role at the start of every run.
				@Index(name = "idx_crg_role_status", columnList = "role_type,status_id"),
				// The split the runner makes before evaluating anything: which of a role's rules can run now.
				@Index(name = "idx_crg_role_mechanism", columnList = "role_type,mechanism"),
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
	 * Stable identifier of the rule, and the value a finding cites in {@code code_review_findings.rule_id} —
	 * hence the identical 64-character width, so a citation resolves back to exactly one row.
	 * <p>
	 * Unique per role rather than globally, which is what makes "which rules apply to this file" a
	 * single-predicate lookup. The cost is that a role-agnostic rule is authored once per role: an empty
	 * catch block is equally wrong in all three, and exists as {@code SVC-}, {@code CTL-} and {@code ENT-}
	 * rows. A nullable {@code role_type} meaning "every role" was rejected — it would turn that lookup into
	 * a two-branch query to save a few rows.
	 */
	@Column(name = "code", nullable = false, length = 64)
	private String code;

	/** The rule's human name, shown wherever a finding is displayed. Not used to select anything. */
	@Column(name = "title", nullable = false, length = 255)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(name = "mechanism", nullable = false, length = 8)
	private ReviewMechanism mechanism;

	/**
	 * The severity a finding from this rule carries, and the reason {@code code_review_findings.severity}
	 * can be {@code NOT NULL} without depending on a model's self-assessment. For a {@code PMD} rule it
	 * overrides the mapping from PMD's own priority, so the library decides how loud a rule is rather than
	 * the engine; for an {@code LLM} rule it is the only severity there is.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "severity", nullable = false, length = 8)
	private ReviewSeverity severity;

	/**
	 * What the rule says, interpreted by {@code mechanism}: prompt prose for {@code LLM}, a PMD rule
	 * reference such as {@code category/java/errorprone.xml/EmptyCatchBlock} for {@code PMD}.
	 */
	@Column(name = "content", columnDefinition = "text", nullable = false)
	private String content;

	/**
	 * SHA-256 of {@code content}. Detects an in-place edit of a rule, and feeds the rule-set hash a run file
	 * records as {@code rules_hash} — that hash is part of the resume key, so a stale value here silently
	 * suppresses the re-review of files whose rules changed.
	 */
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
