package com.ilkeiapps.skyworx.notulenai.service;

import java.util.List;
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
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.ConvertedElement;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.TikaExtraction;
import com.ilkeiapps.skyworx.notulenai.config.TikaProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Document-conversion integration (the AnyDoc + PaddleOCR service behind {@code tika.server.url}).
 * Sends the document bytes as a multipart {@code POST /v1/convert} with {@code output=json}, which
 * returns {@code {"metadata": {...}, "elements": [...], "element_count": n, "schema_version": "1.0"}}:
 * the document already split into ordered, typed elements (heading / paragraph / list / table), each
 * carrying its heading path and parent linkage, plus what the service detected. The bytes are streamed
 * to the service rather than parsed in-process.
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

		var extraction = parse(body);
		log.info("convert: {} elements in {} ms, metadata={}", extraction.elements().size(),
				System.currentTimeMillis() - start, extraction.metadata());
		return extraction;
	}

	/** Package-private so the response binding is testable without a live conversion service. */
	TikaExtraction parse(String body) {
		var root = readTree(body);
		var elementsNode = root.path("elements");
		if (!elementsNode.isArray()) {
			throw new IllegalStateException("Document conversion returned no elements");
		}

		var metadataNode = root.path("metadata");
		Map<String, Object> metadata = metadataNode.isObject()
				? OBJECT_MAPPER.convertValue(metadataNode, new TypeReference<Map<String, Object>>() {})
				: Map.of();

		return new TikaExtraction(readElements(elementsNode), metadata);
	}

	private List<ConvertedElement> readElements(JsonNode elementsNode) {
		try {
			return OBJECT_MAPPER.convertValue(elementsNode, new TypeReference<List<ConvertedElement>>() {});
		} catch (Exception e) {
			throw new IllegalStateException("Unable to read the document conversion elements", e);
		}
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
