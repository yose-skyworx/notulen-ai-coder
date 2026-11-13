package com.ilkeiapps.skyworx.notulenai.service;

import java.io.InputStream;

/**
 * Abstraction over original-document storage so the backend (MinIO or local filesystem) can be
 * switched via configuration ({@code minio.enabled}). The processing pipeline and controllers depend
 * on this interface, not on a concrete store.
 */
public interface DocumentStorageService {

	/** Stores the bytes under {@code objectKey} and returns the key. */
	String store(String objectKey, byte[] data, String contentType);

	/** Opens a stream to the stored object. The caller must close it. */
	InputStream openStream(String objectKey);

	/** Identifier of the storage location (MinIO bucket name, or a label for local storage). */
	String bucket();
}
