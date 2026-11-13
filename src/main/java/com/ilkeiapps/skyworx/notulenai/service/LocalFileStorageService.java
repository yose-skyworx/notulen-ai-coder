package com.ilkeiapps.skyworx.notulenai.service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.config.MinioProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Local-filesystem fallback for original-document storage, active when {@code minio.enabled=false}.
 * Files are written under {@code minio.local-dir} using the same object-key layout MinIO would use
 * ({@code project/{projectId}/{uuid}.ext}). Intended for development when MinIO is unavailable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "minio", name = "enabled", havingValue = "false")
public class LocalFileStorageService implements DocumentStorageService {

	private final MinioProperties properties;

	private Path baseDir() {
		var dir = properties.getLocalDir() != null && !properties.getLocalDir().isBlank()
				? properties.getLocalDir() : "storage/documents";
		return Paths.get(dir).toAbsolutePath().normalize();
	}

	@Override
	public String store(String objectKey, byte[] data, String contentType) {
		try {
			var target = baseDir().resolve(objectKey).normalize();
			Files.createDirectories(target.getParent());
			Files.write(target, data);
			log.info("stored document locally at {}", target);
			return objectKey;
		} catch (Exception e) {
			throw new IllegalStateException("Local storage write failed for " + objectKey, e);
		}
	}

	@Override
	public InputStream openStream(String objectKey) {
		try {
			var target = baseDir().resolve(objectKey).normalize();
			log.debug("reading document locally from {}", target);
			return Files.newInputStream(target);
		} catch (Exception e) {
			throw new IllegalStateException("Local storage read failed for " + objectKey, e);
		}
	}

	@Override
	public String bucket() {
		return "local:" + baseDir();
	}
}
