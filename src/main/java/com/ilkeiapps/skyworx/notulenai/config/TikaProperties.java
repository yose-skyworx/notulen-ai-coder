package com.ilkeiapps.skyworx.notulenai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Typed configuration for the document-conversion service (AnyDoc + PaddleOCR) reached at
 * {@code tika.server.url}, bound from {@code tika.server.*}.
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "tika.server")
public class TikaProperties {

	/** Base URL of the conversion service, e.g. {@code https://tika.example.internal}. */
	private String url;

	/**
	 * Bearer token for {@code POST /v1/convert}. Supplied through {@code TIKA_SERVER_TOKEN}; this file
	 * is git-tracked, so it deliberately carries no committed default.
	 */
	private String token;
}
