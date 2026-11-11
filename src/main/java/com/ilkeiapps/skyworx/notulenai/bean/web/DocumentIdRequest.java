package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

/**
 * Request bean carrying only a knowledge-document id. Used by operations that identify a document and
 * carry no other payload (detail, status, download, processing trigger).
 */
@Setter
@Getter
@ToString(includeFieldNames = true)
@FieldNameConstants
public class DocumentIdRequest {

	private Long id;
}
