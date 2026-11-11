package com.ilkeiapps.skyworx.notulenai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Typed configuration for the Apache Tika Server, bound from {@code tika.server.*}.
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "tika.server")
public class TikaProperties {

	/** Base URL of the Tika Server, e.g. {@code http://host:9998}. */
	private String url;
}
