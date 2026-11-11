package com.ilkeiapps.skyworx.notulenai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Typed configuration for JWT signing/validation, bound from the {@code security.jwt.*}
 * properties. Registered via {@code @EnableConfigurationProperties} on {@link SecurityConfiguration}.
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "security.jwt")
public class JwtProperties {

	/** HMAC secret for HS256. Must be at least 32 bytes; provided via the JWT_SECRET env var in production. */
	private String secret;

	/** Token lifetime in milliseconds. */
	private long expirationMs;
}
