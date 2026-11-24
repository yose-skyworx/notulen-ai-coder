package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

import java.util.List;
import java.util.Map;

/**
 * Result of a document conversion: the ordered {@code elements} the conversion service produced
 * ({@code schema_version} 1.0 — headings, paragraphs, lists and tables, each already carrying its
 * heading path and parent linkage) and the {@code metadata} it reported (detected {@code format},
 * {@code page_count}, and the {@code ocr_pages} that needed OCR).
 * Internal processing holder, not a REST DTO.
 */
public record TikaExtraction(List<ConvertedElement> elements, Map<String, Object> metadata) {
}
