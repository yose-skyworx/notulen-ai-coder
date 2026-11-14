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
 * The persistent class for the {@code code_review_branches} table: a branch explicitly selected for
 * review under a {@link CodeRepository}. Review scope is only ever the registered repositories and
 * their selected branches — the scheduler never scans everything a token can reach.
 */
@Entity
@Table(name = "code_review_branches",
		uniqueConstraints = @UniqueConstraint(name = "uq_crb_repo_branch", columnNames = {"repository_id", "branch_name"}),
		indexes = @Index(name = "idx_crb_repository", columnList = "repository_id"))
@Setter
@Getter
public class CodeReviewBranch implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "code_review_branches_id_generator", sequenceName = "seq_code_review_branches", initialValue = 1, allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "code_review_branches_id_generator")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "repository_id", nullable = false)
	private CodeRepository repository;

	@Column(name = "branch_name", nullable = false, length = 255)
	private String branchName;

	@Column(name = "enabled", nullable = false)
	private Boolean enabled;

	@Column(name = "created_at")
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	public CodeReviewBranch() {
	}

}
