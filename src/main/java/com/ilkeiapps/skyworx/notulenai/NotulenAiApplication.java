package com.ilkeiapps.skyworx.notulenai;

import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.ilkeiapps.skyworx.notulenai.config.TlsBootstrap;

/**
 * pgvector auto-configuration is excluded for now: its {@code vectorStore} bean requires an
 * {@code EmbeddingModel}, and the sky-inference endpoint has no working embeddings model yet.
 * Re-enable by removing the exclusion once an embedding model is available.
 */
@SpringBootApplication(exclude = PgVectorStoreAutoConfiguration.class)
public class NotulenAiApplication {

    public static void main(String[] args) {
        // The internal endpoints present an incomplete (but publicly-rooted) certificate chain; enable
        // AIA intermediate fetching before any TLS so OkHttp-based clients (notably Embabel's
        // openai-custom chat client, which never sees Spring AI's TLS customizers) can validate it.
        TlsBootstrap.enableAiaIntermediateFetching();
        SpringApplication.run(NotulenAiApplication.class, args);
    }

}
