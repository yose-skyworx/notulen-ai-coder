package com.ilkeiapps.skyworx.notulenai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import io.minio.MinioClient;

/**
 * Infrastructure beans for the knowledge-base module: the MinIO client (original-document storage)
 * and a {@link RestClient} pointed at the Apache Tika Server (document extraction). Binds the
 * module's typed configuration classes.
 */
@Configuration
@EnableConfigurationProperties({MinioProperties.class, TikaProperties.class, KnowledgeProperties.class})
public class KnowledgeConfiguration {

	@Bean
	public MinioClient minioClient(MinioProperties properties) {
		return MinioClient.builder()
				.endpoint(properties.getEndpoint())
				.credentials(properties.getAccessKey(), properties.getSecretKey())
				.build();
	}

	@Bean
	public RestClient tikaRestClient(TikaProperties properties) {
		return RestClient.builder()
				.baseUrl(properties.getUrl())
				.build();
	}
}
