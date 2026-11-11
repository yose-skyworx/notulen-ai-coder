package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

import java.util.Map;

/**
 * Raw result of an Apache Tika Server extraction: the detected title and content type, the XHTML body
 * Tika produced (structure-preserving: headings, paragraphs, tables, page/slide divs), and the full
 * metadata map. Internal processing holder, not a REST DTO.
 */
public record TikaExtraction(String title, String contentType, String xhtml, Map<String, String> metadata) {
}
