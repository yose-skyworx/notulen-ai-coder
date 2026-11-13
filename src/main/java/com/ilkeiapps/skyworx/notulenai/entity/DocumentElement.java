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
 * The persistent class for the {@code document_elements} table: the normalized extraction output of a
 * {@link KnowledgeDocument}, preserving structure (heading hierarchy, parent/child, extraction order)
 * and provenance (page/slide numbers). Produced by the normalization layer from Apache Tika output.
 * <p>
 * {@code elementType} values: {@code DOCUMENT, HEADING, PARAGRAPH, TABLE, TABLE_ROW, LIST, IMAGE,
 * FOOTNOTE, OTHER}.
 */
@Entity
@Table(name = "document_elements", indexes = {
		@Index(name = "idx_delem_document", columnList = "document_id"),
		@Index(name = "idx_delem_document_seq", columnList = "document_id,sequence_number"),
		@Index(name = "idx_delem_parent", columnList = "parent_id")
})
@Setter
@Getter
public class DocumentElement implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "document_elements_id_generator", sequenceName = "seq_document_elements", initialValue = 1, allocationSize = 50)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "document_elements_id_generator")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "document_id", nullable = false)
	private KnowledgeDocument document;

	@Column(name = "element_type", nullable = false, length = 32)
	private String elementType;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_id")
	private DocumentElement parent;

	@Column(name = "sequence_number", nullable = false)
	private Integer sequenceNumber;

	@Column(name = "heading_level")
	private Integer headingLevel;

	@Column(name = "page_number")
	private Integer pageNumber;

	@Column(name = "slide_number")
	private Integer slideNumber;

	@Column(name = "element_path", length = 1024)
	private String elementPath;

	@Column(name = "content", columnDefinition = "text")
	private String content;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "structured_content")
	private Map<String, Object> structuredContent;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "metadata")
	private Map<String, Object> metadata;

	@Column(name = "created_at")
	private Instant createdAt;

	public DocumentElement() {
	}

}
