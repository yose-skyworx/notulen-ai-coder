package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.ConvertedElement;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.NormalizedElement;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.TikaExtraction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Normalization layer between conversion and {@code DocumentElement} persistence. {@code POST
 * /v1/convert} already returns the document as a flat, ordered {@code elements} array carrying its own
 * structure (type, heading level, {@code heading_path}, {@code parent_id}), so this service maps each
 * {@link ConvertedElement} onto a {@link NormalizedElement} rather than parsing Markdown: it resolves
 * {@code parent_id} into a parent sequence index, derives the element path from the heading path,
 * cleans the text, and keeps every type-specific field as structured content.
 * <p>
 * This decouples chunking from the converter's wire format: chunking consumes {@code DocumentElement}s,
 * never the conversion response. A synthetic {@code DOCUMENT} root at sequence 0 anchors the top-level
 * elements, which is the shape both chunking and persistence already expect.
 * <p>
 * Note: {@code slideNumber} is left null — the converter reports pages only, and reported no page for
 * the text-layer PDFs seen so far.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentNormalizationService {

	/** CommonMark backslash-escapable punctuation; the converter escapes these in element text. */
	private static final Pattern MD_ESCAPE = Pattern.compile("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|>~])");

	/** Runs of horizontal whitespace, collapsed to a single space without touching line breaks. */
	private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");

	private static final String ROOT_PATH = "/";
	private static final int ROOT_INDEX = 0;
	private static final String TYPE_OTHER = "OTHER";

	public List<NormalizedElement> normalize(TikaExtraction extraction, String documentTitle) {
		var elements = new ArrayList<NormalizedElement>();

		var root = new NormalizedElement();
		root.setElementType("DOCUMENT");
		root.setSequenceNumber(ROOT_INDEX);
		root.setParentIndex(-1);
		root.setContent(documentTitle);
		root.setElementPath(ROOT_PATH);
		elements.add(root);

		var source = extraction == null ? null : extraction.elements();
		if (source == null || source.isEmpty()) {
			log.warn("normalize: conversion returned no elements, produced {} element(s)", elements.size());
			return elements;
		}

		// Converter element id -> sequence index. A parent_id always refers to an element the converter
		// emitted earlier (the enclosing heading), so one forward pass resolves every parent.
		var indexById = new HashMap<String, Integer>(source.size() * 2);
		for (ConvertedElement ce : source) {
			var ne = toNormalized(ce, indexById, elements.size());
			elements.add(ne);
			if (ce.getId() != null && !ce.getId().isBlank()) {
				indexById.put(ce.getId(), ne.getSequenceNumber());
			}
		}

		log.info("normalize: produced {} elements from {} converted element(s)", elements.size(), source.size());
		return elements;
	}

	private NormalizedElement toNormalized(ConvertedElement ce, Map<String, Integer> indexById, int sequenceNumber) {
		var ne = new NormalizedElement();
		ne.setElementType(elementType(ce.getType()));
		ne.setSequenceNumber(sequenceNumber);
		ne.setParentIndex(resolveParent(ce.getParentId(), indexById));
		ne.setHeadingLevel(ce.getLevel());
		ne.setPageNumber(ce.getPage());
		ne.setElementPath(path(ce.getHeadingPath()));
		ne.setContent(clean(ce.getText()));
		if (!ce.getExtras().isEmpty()) {
			ne.setStructuredContent(new LinkedHashMap<>(ce.getExtras()));
		}
		return ne;
	}

	private String elementType(String type) {
		return type == null || type.isBlank() ? TYPE_OTHER : type.strip().toUpperCase(Locale.ROOT);
	}

	/** Falls back to the root when the converter gave no parent or named one that was never emitted. */
	private int resolveParent(String parentId, Map<String, Integer> indexById) {
		if (parentId == null || parentId.isBlank()) {
			return ROOT_INDEX;
		}
		return indexById.getOrDefault(parentId, ROOT_INDEX);
	}

	private String path(List<String> headingPath) {
		if (headingPath == null || headingPath.isEmpty()) {
			return ROOT_PATH;
		}
		var parts = new ArrayList<String>(headingPath.size());
		for (String heading : headingPath) {
			var cleaned = clean(heading);
			if (!cleaned.isEmpty()) {
				parts.add(cleaned);
			}
		}
		return parts.isEmpty() ? ROOT_PATH : ROOT_PATH + String.join("/", parts);
	}

	/**
	 * Unescapes Markdown backslash-escapes, collapses punctuation leaders and horizontal whitespace, and
	 * drops blank lines. Line breaks are preserved: a list and a table carry their whole structure in
	 * them, and flattening either would destroy the row and item boundaries chunking relies on.
	 */
	private String clean(String text) {
		if (text == null) {
			return "";
		}
		var unescaped = MD_ESCAPE.matcher(text).replaceAll("$1");
		// Collapse runs of 4+ identical punctuation chars (dot/dash leaders in tables of contents, rule
		// lines, GFM table delimiters) to a single char — they carry no meaning and explode token counts.
		var collapsed = unescaped.replaceAll("([^\\w\\s])\\1{3,}", "$1");

		var sb = new StringBuilder(collapsed.length());
		for (String line : collapsed.split("\n", -1)) {
			var stripped = HORIZONTAL_SPACE.matcher(line).replaceAll(" ").strip();
			if (stripped.isEmpty()) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append('\n');
			}
			sb.append(stripped);
		}
		return sb.toString();
	}
}
