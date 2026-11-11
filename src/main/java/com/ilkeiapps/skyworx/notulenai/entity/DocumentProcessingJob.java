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

import lombok.Getter;
import lombok.Setter;


/**
 * The persistent class for the {@code document_processing_jobs} table: durable tracking of
 * asynchronous document-processing work (extraction → normalization → chunking → embedding), with
 * retry accounting and failure details. {@code status} references the shared {@link StatusProcess}
 * master, consistent with {@link KnowledgeDocument#getProcessingStatus()} (no enum, no hardcoded id).
 */
@Entity
@Table(name = "document_processing_jobs", indexes = {
		@Index(name = "idx_djob_project", columnList = "project_id"),
		@Index(name = "idx_djob_document", columnList = "document_id"),
		@Index(name = "idx_djob_status", columnList = "status_id")
})
@Setter
@Getter
public class DocumentProcessingJob implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "document_processing_jobs_id_generator", sequenceName = "seq_document_processing_jobs", initialValue = 1, allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "document_processing_jobs_id_generator")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "document_id", nullable = false)
	private KnowledgeDocument document;

	/** Processing job type, e.g. {@code FULL_PROCESSING}. */
	@Column(name = "job_type", length = 64)
	private String jobType;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "status_id", nullable = false)
	private StatusProcess status;

	@Column(name = "attempt_count", nullable = false)
	private Integer attemptCount;

	@Column(name = "max_attempts", nullable = false)
	private Integer maxAttempts;

	@Column(name = "error_message", length = 4096)
	private String errorMessage;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "created_at")
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	public DocumentProcessingJob() {
	}

}
