package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

import java.util.Map;

/**
 * Result of a document conversion: the Markdown body the conversion service produced
 * (structure-preserving: ATX headings, paragraphs, GFM tables, lists) and the metadata it reported
 * (detected {@code format}, {@code page_count}, and the {@code ocr_pages} that needed OCR).
 * Internal processing holder, not a REST DTO.
 */
public record TikaExtraction(String content, Map<String, Object> metadata) {
}
