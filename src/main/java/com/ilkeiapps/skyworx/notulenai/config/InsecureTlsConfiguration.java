package com.ilkeiapps.skyworx.notulenai.config;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.boot.restclient.RestClientCustomizer;

import lombok.extern.slf4j.Slf4j;

/**
 * DEV-ONLY: when {@code app.tls.insecure=true}, makes every outbound client trust any TLS certificate
 * and skip hostname verification, because the internal {@code *.example.internal} services present
 * certificates from a CA the JVM does not trust. This covers both transports in use:
 * <ul>
 *   <li>Spring {@code RestClient} (Apache Tika extraction) via a {@link RestClientCustomizer} that
 *       installs a trust-all JDK HTTP client.</li>
 *   <li>The Spring AI OpenAI embedding client (okhttp, via the OpenAI Java SDK) via an
 *       {@link OpenAiHttpClientBuilderCustomizer} that sets a trust-all SSL socket factory.</li>
 * </ul>
 * This disables TLS verification and must be {@code false} in production — use a proper truststore
 * there (import the internal CA/cert with {@code keytool}). The toggle is gated and loudly logged.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "app.tls", name = "insecure", havingValue = "true")
public class InsecureTlsConfiguration {

	@Bean
	public ClientHttpRequestFactory insecureClientHttpRequestFactory() {
		log.warn("app.tls.insecure=true: TLS certificate verification is DISABLED for outbound calls "
				+ "(Tika RestClient + OpenAI embedding okhttp). DEV ONLY — use a truststore in production.");
		return new JdkClientHttpRequestFactory(buildInsecureHttpClient());
	}

	@Bean
	public RestClientCustomizer insecureRestClientCustomizer(ClientHttpRequestFactory insecureClientHttpRequestFactory) {
		return builder -> builder.requestFactory(insecureClientHttpRequestFactory);
	}

	/** Applies trust-all TLS to the Spring AI OpenAI (okhttp) client used for embeddings. */
	@Bean
	public OpenAiHttpClientBuilderCustomizer insecureOpenAiHttpClientBuilderCustomizer() {
		var trustManager = trustAllManager();
		return builder -> builder
				.sslSocketFactory(socketFactory(trustManager))
				.trustManager(trustManager)
				.hostnameVerifier((hostname, session) -> true);
	}

	private HttpClient buildInsecureHttpClient() {
		var sslContext = sslContext(trustAllManager());
		var sslParameters = sslContext.getDefaultSSLParameters();
		sslParameters.setEndpointIdentificationAlgorithm(null); // skip hostname verification
		return HttpClient.newBuilder().sslContext(sslContext).sslParameters(sslParameters).build();
	}

	private javax.net.ssl.SSLSocketFactory socketFactory(X509TrustManager trustManager) {
		return sslContext(trustManager).getSocketFactory();
	}

	private SSLContext sslContext(X509TrustManager trustManager) {
		try {
			var sslContext = SSLContext.getInstance("TLS");
			sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());
			return sslContext;
		} catch (Exception e) {
			throw new IllegalStateException("Failed to build insecure SSLContext", e);
		}
	}

	private X509TrustManager trustAllManager() {
		return new X509TrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) {
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) {
			}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		};
	}
}
