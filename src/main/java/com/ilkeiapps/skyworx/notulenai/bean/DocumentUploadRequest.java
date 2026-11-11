package com.ilkeiapps.skyworx.notulenai.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Create payload for a document upload (the non-file fields; the binary arrives as a multipart part).
 * Exposes only client-settable fields: the target project, optional logical-document id (present when
 * uploading a new version of an existing logical document), and optional title/description. Server
 * fields (status, storage keys, version lineage, audit columns) are never bound from the client.
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class DocumentUploadRequest {

	private Long projectId;

	/** When set, this upload becomes a new version of the given logical document; null starts a new one. */
	private Long logicalDocumentId;

	private String documentTitle;

	private String description;
}
