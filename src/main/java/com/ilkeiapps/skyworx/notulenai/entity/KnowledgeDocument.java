package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * The persistent class for the {@code knowledge_documents} table: document metadata, MinIO storage
 * location, project ownership, version lineage, and processing lifecycle.
 * <p>
 * Each version is its own row. {@code logicalDocumentId} ties versions of the same logical document
 * together; {@code parentVersion} points to the preceding version; exactly one version per logical
 * document is {@code isCurrent} (enforced in the service layer and, operationally, by a partial
 * unique index that standard JPA cannot express — see the implementation notes).
 */
@Entity
@Table(name = "knowledge_documents", indexes = {
		@Index(name = "idx_kdoc_project", columnList = "project_id"),
		@Index(name = "idx_kdoc_logical", columnList = "logical_document_id"),
		@Index(name = "idx_kdoc_status", columnList = "processing_status_id"),
		@Index(name = "idx_kdoc_logical_current", columnList = "logical_document_id,is_current"),
		@Index(name = "idx_kdoc_checksum", columnList = "checksum")
})
@Setter
@Getter
public class KnowledgeDocument implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "knowledge_documents_id_generator", sequenceName = "seq_knowledge_documents", initialValue = 1, allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "knowledge_documents_id_generator")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	/** Shared across all versions of the same logical document (set to this row's id for v1). */
	@Column(name = "logical_document_id")
	private Long logicalDocumentId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_version_id")
	private KnowledgeDocument parentVersion;

	@Column(name = "version_number", nullable = false)
	private Integer versionNumber;

	@Column(name = "is_current", nullable = false)
	private Boolean isCurrent;

	@Column(name = "original_filename", nullable = false, length = 512)
	private String originalFilename;

	@Column(name = "stored_filename", length = 512)
	private String storedFilename;

	@Column(name = "content_type", length = 255)
	private String contentType;

	@Column(name = "file_extension", length = 32)
	private String fileExtension;

	@Column(name = "file_size")
	private Long fileSize;

	@Column(name = "checksum", length = 128)
	private String checksum;

	@Column(name = "storage_bucket", length = 255)
	private String storageBucket;

	@Column(name = "storage_object_key", length = 1024)
	private String storageObjectKey;

	@Column(name = "document_title", length = 1024)
	private String documentTitle;

	@Column(name = "description", length = 2048)
	private String description;

	/** Origin of the document, e.g. {@code UPLOAD} or {@code MICROSOFT_TEAMS}. */
	@Column(name = "source_type", length = 64)
	private String sourceType;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "processing_status_id", nullable = false)
	private StatusProcess processingStatus;

	@Column(name = "processing_error", length = 4096)
	private String processingError;

	/**
	 * What the conversion service detected on the last run, verbatim: {@code format}, {@code page_count},
	 * {@code sha256}, {@code ocr_pages}, {@code ocr_engine}, {@code ocr_langs}, {@code duration_ms}. Kept
	 * as JSON rather than as columns because it is the converter's detection report, not data this
	 * application filters or joins on — and the parts that do have a relational counterpart
	 * ({@code filename}, {@code bytes}) already have their own columns above.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "conversion_metadata")
	private Map<String, Object> conversionMetadata;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_id")
	private User createdBy;

	@Column(name = "created_at")
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	@Column(name = "processed_at")
	private Instant processedAt;

	public KnowledgeDocument() {
	}

}
