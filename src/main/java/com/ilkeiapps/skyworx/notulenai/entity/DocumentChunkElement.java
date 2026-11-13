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
 * The persistent class for the {@code document_chunk_elements} table: the many-to-many link between a
 * {@link DocumentChunk} and the source {@link DocumentElement} records it was built from, preserving
 * element order within the chunk. A unique constraint on {@code (chunk_id, element_id)} prevents
 * duplicate associations (important for idempotent reprocessing).
 */
@Entity
@Table(name = "document_chunk_elements",
		uniqueConstraints = @UniqueConstraint(name = "uq_chunk_element", columnNames = {"chunk_id", "element_id"}),
		indexes = {
				@Index(name = "idx_dce_chunk", columnList = "chunk_id"),
				@Index(name = "idx_dce_element", columnList = "element_id")
		})
@Setter
@Getter
public class DocumentChunkElement implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "document_chunk_elements_id_generator", sequenceName = "seq_document_chunk_elements", initialValue = 1, allocationSize = 50)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "document_chunk_elements_id_generator")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "chunk_id", nullable = false)
	private DocumentChunk chunk;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "element_id", nullable = false)
	private DocumentElement element;

	@Column(name = "element_order", nullable = false)
	private Integer elementOrder;

	@Column(name = "created_at")
	private Instant createdAt;

	public DocumentChunkElement() {
	}

}
