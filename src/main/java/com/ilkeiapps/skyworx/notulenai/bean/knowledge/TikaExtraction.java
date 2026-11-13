package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

import java.util.Map;

/**
 * Raw result of an Apache Tika Server extraction: the detected title and content type, the Markdown
 * body Tika produced (structure-preserving: ATX headings, paragraphs, GFM tables, lists), and the full
 * metadata map. Internal processing holder, not a REST DTO.
 */
public record TikaExtraction(String title, String contentType, String content, Map<String, String> metadata) {
}
