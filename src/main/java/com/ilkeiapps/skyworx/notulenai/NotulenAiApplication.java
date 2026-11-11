package com.ilkeiapps.skyworx.notulenai;

import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * pgvector auto-configuration is excluded for now: its {@code vectorStore} bean requires an
 * {@code EmbeddingModel}, and the sky-inference endpoint has no working embeddings model yet.
 * Re-enable by removing the exclusion once an embedding model is available.
 */
@SpringBootApplication(exclude = PgVectorStoreAutoConfiguration.class)
public class NotulenAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotulenAiApplication.class, args);
    }

}
