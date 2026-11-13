package com.ilkeiapps.skyworx.notulenai.entity;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

import org.hibernate.annotations.Array;
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
 * The persistent class for the {@code document_chunks} table: RAG-ready text chunks and their
 * embeddings. The {@code embedding} column is a PostgreSQL pgvector {@code vector(1024)} mapped via
 * {@code hibernate-vector} ({@code @JdbcTypeCode(SqlTypes.VECTOR)} on a {@code float[]}); cosine
 * similarity search is performed with a native query using the {@code <=>} operator.
 * <p>
 * The HNSW index for the {@code embedding} column cannot be expressed through standard JPA
 * annotations and is created as a one-time operational step (see the implementation notes).
 */
@Entity
@Table(name = "document_chunks", indexes = {
		@Index(name = "idx_dchunk_project", columnList = "project_id"),
		@Index(name = "idx_dchunk_document", columnList = "document_id"),
		@Index(name = "idx_dchunk_document_index", columnList = "document_id,chunk_index")
})
@Setter
@Getter
public class DocumentChunk implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "document_chunks_id_generator", sequenceName = "seq_document_chunks", initialValue = 1, allocationSize = 50)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "document_chunks_id_generator")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "document_id", nullable = false)
	private KnowledgeDocument document;

	@Column(name = "chunk_index", nullable = false)
	private Integer chunkIndex;

	@Column(name = "content", columnDefinition = "text", nullable = false)
	private String content;

	@Column(name = "token_count")
	private Integer tokenCount;

	@Column(name = "page_start")
	private Integer pageStart;

	@Column(name = "page_end")
	private Integer pageEnd;

	@Column(name = "slide_start")
	private Integer slideStart;

	@Column(name = "slide_end")
	private Integer slideEnd;

	/** 1024-dimensional BGE-M3 embedding, stored as a pgvector {@code vector(1024)} column. */
	@JdbcTypeCode(SqlTypes.VECTOR)
	@Array(length = 1024)
	@Column(name = "embedding")
	private float[] embedding;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "metadata")
	private Map<String, Object> metadata;

	@Column(name = "created_at")
	private Instant createdAt;

	public DocumentChunk() {
	}

}
