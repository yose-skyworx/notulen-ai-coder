package com.ilkeiapps.skyworx.notulenai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Typed configuration for MinIO object storage, bound from {@code minio.*}. Credentials are supplied
 * via environment variables (see {@code application.properties}); nothing is committed.
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

	/** MinIO S3 endpoint, e.g. {@code http://host:9000}. */
	private String endpoint;

	private String accessKey;

	private String secretKey;

	/** Bucket that holds original uploaded documents. Created on startup if absent. */
	private String bucket;

	/** Whether MinIO is used. When false, the local-filesystem storage fallback is active. */
	private boolean enabled = true;

	/** Base directory for the local-filesystem fallback (used when {@code enabled=false}). */
	private String localDir;
}
