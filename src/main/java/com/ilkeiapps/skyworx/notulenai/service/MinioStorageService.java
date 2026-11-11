package com.ilkeiapps.skyworx.notulenai.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.config.MinioProperties;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.PutObjectArgs;
import io.minio.MinioClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MinIO object storage for original uploaded documents. Object keys are unique and namespaced by
 * project ({@code project/{projectId}/{uuid}.{ext}}) so predictable keys cannot be guessed across
 * projects. Infrastructure failures are wrapped as {@link IllegalStateException} (mapped to 500 by the
 * global handler); the processing pipeline records them against the job.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageService {

	private final MinioClient minioClient;
	private final MinioProperties properties;

	/** Ensures the configured bucket exists; safe to call repeatedly. */
	public void ensureBucket() {
		try {
			var exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build());
			if (!exists) {
				minioClient.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
				log.info("created MinIO bucket {}", properties.getBucket());
			}
		} catch (Exception e) {
			throw new IllegalStateException("MinIO bucket check/creation failed", e);
		}
	}

	/**
	 * Stores the given bytes under {@code objectKey} in the configured bucket and returns the key.
	 */
	public String store(String objectKey, byte[] data, String contentType) {
		ensureBucket();
		try (InputStream in = new ByteArrayInputStream(data)) {
			minioClient.putObject(PutObjectArgs.builder()
					.bucket(properties.getBucket())
					.object(objectKey)
					.stream(in, data.length, -1)
					.contentType(contentType != null ? contentType : "application/octet-stream")
					.build());
			return objectKey;
		} catch (Exception e) {
			throw new IllegalStateException("MinIO upload failed for object " + objectKey, e);
		}
	}

	/** Opens a stream to the stored object. Caller is responsible for closing it. */
	public InputStream openStream(String objectKey) {
		try {
			return minioClient.getObject(GetObjectArgs.builder()
					.bucket(properties.getBucket())
					.object(objectKey)
					.build());
		} catch (Exception e) {
			throw new IllegalStateException("MinIO download failed for object " + objectKey, e);
		}
	}

	public String bucket() {
		return properties.getBucket();
	}
}
