package com.ilkeiapps.skyworx.notulenai.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Upload acknowledgement returned without waiting for the extraction/embedding pipeline: the new
 * document identifier, its logical-document id and version, the original filename, and the current
 * processing status code (e.g. {@code PENDING}). Never exposes internal storage keys.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
public class DocumentUploadResponse {

	private Long documentId;
	private Long logicalDocumentId;
	private Integer versionNumber;
	private String originalFilename;
	private String processingStatus;
}
