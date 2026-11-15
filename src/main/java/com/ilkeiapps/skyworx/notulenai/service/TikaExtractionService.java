package com.ilkeiapps.skyworx.notulenai.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.TikaExtraction;
import com.ilkeiapps.skyworx.notulenai.config.TikaProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Document-conversion integration (the AnyDoc + PaddleOCR service behind {@code tika.server.url}).
 * Sends the document bytes as a multipart {@code POST /v1/convert} with {@code output=json}, which
 * returns {@code {"markdown": "...", "metadata": {...}}}: structure-preserving Markdown (ATX headings,
 * paragraphs, GFM tables, lists) plus what the service detected. The bytes are streamed to the service
 * rather than parsed in-process.
 * <p>
 * The service identifies the format by sniffing the bytes, so no content type or file extension needs
 * to be supplied; an ambiguous format would need an extra {@code format} part, which nothing uploaded
 * here requires. {@code ocr=auto} leaves the decision to the service, which OCRs only the pages that
 * have no text layer and lists them in {@code metadata.ocr_pages}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TikaExtractionService {

	/** The format is sniffed from the bytes, so the part's filename carries no information. */
	private static final String FILE_PART_NAME = "document";

	/**
	 * Local Jackson (2.x) mapper for reading the conversion response. Spring Boot 4 auto-configures a
	 * Jackson 3 mapper, so no {@code com.fasterxml.jackson.databind.ObjectMapper} bean exists to
	 * inject; a private instance avoids coupling to the framework's JSON stack.
	 */
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final @Qualifier("tikaRestClient") RestClient tikaRestClient;
	private final TikaProperties properties;

	public TikaExtraction extract(byte[] data) {
		var start = System.currentTimeMillis();
		log.info("convert: POST /v1/convert with {} bytes", data.length);

		var parts = new MultipartBodyBuilder();
		parts.part("file", data, MediaType.APPLICATION_OCTET_STREAM).filename(FILE_PART_NAME);
		parts.part("ocr", "auto");
		parts.part("output", "json");

		String body = tikaRestClient.post()
				.uri("/v1/convert")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + requireToken())
				.accept(MediaType.APPLICATION_JSON)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(parts.build())
				.retrieve()
				.body(String.class);

		var root = readTree(body);
		if (!root.path("markdown").isTextual()) {
			throw new IllegalStateException("Document conversion returned no markdown");
		}

		var content = root.path("markdown").asText();
		var metadataNode = root.path("metadata");
		Map<String, Object> metadata = metadataNode.isObject()
				? OBJECT_MAPPER.convertValue(metadataNode, new TypeReference<Map<String, Object>>() {})
				: Map.of();

		log.info("convert: markdown length={} in {} ms, metadata={}", content.length(),
				System.currentTimeMillis() - start, metadata);
		return new TikaExtraction(content, metadata);
	}

	private JsonNode readTree(String body) {
		if (body == null || body.isBlank()) {
			throw new IllegalStateException("Document conversion returned an empty response");
		}
		try {
			return OBJECT_MAPPER.readTree(body);
		} catch (Exception e) {
			throw new IllegalStateException("Unable to parse the document conversion response", e);
		}
	}

	/**
	 * Read once per request rather than cached, so rotating {@code TIKA_SERVER_TOKEN} and restarting
	 * picks the new value up and a blank value fails before any request leaves the process. The message
	 * names the environment variable but never the token.
	 */
	private String requireToken() {
		var configured = properties.getToken();
		if (configured == null || configured.isBlank()) {
			throw new IllegalStateException(
					"tika.server.token is not set; export TIKA_SERVER_TOKEN before processing a document");
		}
		return configured.trim();
	}
}
