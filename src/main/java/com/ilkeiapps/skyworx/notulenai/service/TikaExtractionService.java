package com.ilkeiapps.skyworx.notulenai.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.TikaExtraction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Apache Tika Server integration. Sends the document bytes to {@code PUT /rmeta/md}, which returns a
 * JSON array of metadata objects (one per embedded document); the first object is the main document,
 * whose {@code tk:content} field holds structure-preserving Markdown (ATX headings, paragraphs, GFM
 * tables, lists). The bytes are streamed to Tika rather than parsed in-process.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TikaExtractionService {

	private static final String TIKA_CONTENT_KEY = "tk:content";

	/**
	 * Local Jackson (2.x) mapper for reading Tika's JSON array. Spring Boot 4 auto-configures a
	 * Jackson 3 mapper, so no {@code com.fasterxml.jackson.databind.ObjectMapper} bean exists to
	 * inject; a private instance avoids coupling to the framework's JSON stack.
	 */
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final @Qualifier("tikaRestClient") RestClient tikaRestClient;

	public TikaExtraction extract(byte[] data, String contentType) {
		var start = System.currentTimeMillis();
		log.info("tika: PUT /rmeta/md with {} bytes (contentType={})", data.length, contentType);
		String body = tikaRestClient.put()
				.uri("/rmeta/md")
				.accept(MediaType.APPLICATION_JSON)
				.contentType(contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM)
				.body(data)
				.retrieve()
				.body(String.class);

		List<Map<String, Object>> parsed;
		try {
			parsed = OBJECT_MAPPER.readValue(body, new TypeReference<List<Map<String, Object>>>() {});
		} catch (Exception e) {
			throw new IllegalStateException("Unable to parse Tika Server response", e);
		}
		if (parsed == null || parsed.isEmpty()) {
			log.warn("tika: empty response in {} ms", System.currentTimeMillis() - start);
			return new TikaExtraction(null, contentType, "", Map.of());
		}

		var main = parsed.getFirst();
		log.info("tika: parsed {} metadata object(s), content length={} in {} ms", parsed.size(),
				main.get(TIKA_CONTENT_KEY) == null ? 0 : String.valueOf(main.get(TIKA_CONTENT_KEY)).length(),
				System.currentTimeMillis() - start);
		var content = asString(main.get(TIKA_CONTENT_KEY));
		var title = asString(main.get("dc:title"));
		var detectedType = asString(main.get("Content-Type"));

		var metadata = new HashMap<String, String>();
		for (var entry : main.entrySet()) {
			if (TIKA_CONTENT_KEY.equals(entry.getKey())) {
				continue;
			}
			metadata.put(entry.getKey(), asString(entry.getValue()));
		}
		return new TikaExtraction(title, detectedType != null ? detectedType : contentType,
				content != null ? content : "", metadata);
	}

	private String asString(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof List<?> list) {
			return list.isEmpty() ? null : String.valueOf(list.getFirst());
		}
		return String.valueOf(value);
	}
}
