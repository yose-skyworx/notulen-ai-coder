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
 * The persistent class for the {@code code_repositories} table: a GitHub repository registered for
 * automated code review, scoped to an existing {@link Project} (no separate project table).
 * <p>
 * No credential is stored here. GitHub access uses a single deployment-wide personal access token from
 * {@code code-review.github.token} ({@code GITHUB_TOKEN}), so this table carries repository identity
 * only and never a secret — there is nothing to encrypt, and a database dump leaks nothing usable.
 * <p>
 * {@code status} references the shared {@link StatusGeneral} master so removal is a soft delete
 * ({@code REM}) consistent with {@link Project}; every list query filters {@code REM} out.
 */
@Entity
@Table(name = "code_repositories",
		uniqueConstraints = @UniqueConstraint(name = "uq_code_repo", columnNames = {"project_id", "owner", "repo_name"}),
		indexes = {
				@Index(name = "idx_crepo_project", columnList = "project_id"),
				@Index(name = "idx_crepo_status", columnList = "status_id")
		})
@Setter
@Getter
public class CodeRepository implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "code_repositories_id_generator", sequenceName = "seq_code_repositories", initialValue = 1, allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "code_repositories_id_generator")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	/** GitHub repository owner — the user or organization, not the authenticated caller. */
	@Column(name = "owner", nullable = false, length = 100)
	private String owner;

	@Column(name = "repo_name", nullable = false, length = 100)
	private String repoName;

	/** Branch reported by GitHub as the repository default; a hint for branch selection, not a filter. */
	@Column(name = "default_branch", length = 255)
	private String defaultBranch;

	@Column(name = "enabled", nullable = false)
	private Boolean enabled;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "status_id", nullable = false)
	private StatusGeneral status;

	@Column(name = "created_at")
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	public CodeRepository() {
	}

}
