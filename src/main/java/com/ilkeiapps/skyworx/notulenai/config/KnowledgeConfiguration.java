package com.ilkeiapps.skyworx.notulenai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;

import io.minio.MinioClient;

/**
 * Infrastructure beans for the knowledge-base module: the MinIO client (original-document storage)
 * and a {@link RestClient} pointed at the Apache Tika Server (document extraction). Binds the
 * module's typed configuration classes and enables {@code @Async} so uploads can trigger processing
 * off the request thread (virtual-thread executor; see {@code spring.threads.virtual.enabled}).
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties({MinioProperties.class, TikaProperties.class, KnowledgeProperties.class})
public class KnowledgeConfiguration {

	@Bean
	@ConditionalOnProperty(prefix = "minio", name = "enabled", havingValue = "true", matchIfMissing = true)
	public MinioClient minioClient(MinioProperties properties) {
		return MinioClient.builder()
				.endpoint(properties.getEndpoint())
				.credentials(properties.getAccessKey(), properties.getSecretKey())
				.build();
	}

	@Bean
	public RestClient tikaRestClient(TikaProperties properties, RestClient.Builder builder) {
		// Use the auto-configured builder so RestClientCustomizers apply (e.g. the dev TLS toggle).
		return builder.clone()
				.baseUrl(properties.getUrl())
				.build();
	}
}
