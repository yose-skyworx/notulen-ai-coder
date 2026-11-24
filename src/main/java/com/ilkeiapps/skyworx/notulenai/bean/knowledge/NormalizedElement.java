package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * A single normalized document element produced by the normalization layer from the conversion
 * service's {@code elements} array, before it is persisted as a {@code DocumentElement}. Carries
 * structure and provenance (sequence, heading level, page/slide, parent linkage by sequence index).
 * Internal processing holder, not a REST DTO.
 */
@Setter
@Getter
public class NormalizedElement {

	/** DOCUMENT, HEADING, PARAGRAPH, LIST, TABLE; OTHER for a type the converter did not name. */
	private String elementType;

	/** Position in the original extraction order (0-based). */
	private int sequenceNumber;

	/** Sequence index of the parent element, or -1 for a root element. */
	private int parentIndex = -1;

	private Integer headingLevel;

	private Integer pageNumber;

	private Integer slideNumber;

	private String elementPath;

	private String content;

	private Map<String, Object> structuredContent;

	private Map<String, Object> metadata;
}
