package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/**
 * One entry of the {@code elements} array returned by {@code POST /v1/convert} ({@code schema_version}
 * 1.0). The converter reports document structure directly, so only the fields the pipeline needs for
 * ordering, hierarchy and provenance are declared. Every type-specific field — {@code items} /
 * {@code ordered} / {@code start} on a list, {@code headers} / {@code rows} on a table, {@code images}
 * on a paragraph — is collected into {@link #getExtras()} and persisted as the element's
 * {@code structured_content} JSON, which keeps an unrecognised field from being silently dropped.
 * Internal processing holder, not a REST DTO.
 */
@Setter
@Getter
public class ConvertedElement {

	/** Converter-assigned element id, e.g. {@code el-000017}; the target of other elements' parentId. */
	private String id;

	/** {@code heading}, {@code paragraph}, {@code list} or {@code table}. */
	private String type;

	/** Markdown rendering of the element. This is the text that gets chunked and embedded. */
	private String text;

	/** Heading depth; present only on a {@code heading}. */
	private Integer level;

	/** Source page number, null when the converter did not report one. */
	private Integer page;

	/** Id of the enclosing heading element, null for a top-level element. */
	@JsonProperty("parent_id")
	private String parentId;

	/** Headings enclosing this element, outermost first. */
	@JsonProperty("heading_path")
	private List<String> headingPath;

	/** Type-specific fields, in response order, without the nulls the converter sends for absent ones. */
	private final Map<String, Object> extras = new LinkedHashMap<>();

	@JsonAnySetter
	private void putExtra(String name, Object value) {
		if (value != null) {
			extras.put(name, value);
		}
	}
}
