package com.ilkeiapps.skyworx.notulenai.config;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * Infrastructure beans for the automated code-review module: the {@link RestClient} used for all GitHub
 * API traffic, plus the scheduling infrastructure the daily review trigger needs. Binds
 * {@link CodeReviewProperties}.
 * <p>
 * {@code @EnableScheduling} is a no-op for the rest of the application: no other bean declares a
 * {@code @Scheduled} method, and the review scheduler itself is conditional on
 * {@code code-review.enabled}. With {@code spring.threads.virtual.enabled=true} the auto-configured
 * scheduler runs jobs on virtual threads.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(CodeReviewProperties.class)
public class CodeReviewConfiguration {

	/**
	 * GitHub API client. Deliberately built from the static {@link RestClient#builder()} rather than the
	 * injected auto-configured {@code RestClient.Builder} that {@code tikaRestClient} clones.
	 * <p>
	 * The auto-configured builder carries every registered {@code RestClientCustomizer}, including
	 * {@code InsecureTlsConfiguration}'s trust-all factory, which is active whenever
	 * {@code app.tls.insecure=true} — currently the default. Inheriting it would send the GitHub
	 * personal access token over a connection with certificate and hostname validation disabled. That
	 * toggle exists for the internal {@code *.example.internal} services, whose CA the JVM does not trust;
	 * {@code api.github.com} presents a valid public certificate and needs no exception, so this client
	 * uses the JVM default trust store.
	 */
	@Bean
	public RestClient githubRestClient(CodeReviewProperties properties) {
		var github = properties.getGithub();
		var httpClient = HttpClient.newBuilder()
				.connectTimeout(github.getConnectTimeout())
				// Never follow redirects: the GitHub REST endpoints used here answer directly, and
				// silently following a 3xx would let a response steer the request — and the bearer token
				// it carries — to a host other than the one configured above.
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();

		var requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(github.getReadTimeout());

		return RestClient.builder()
				.baseUrl(github.getBaseUrl())
				.requestFactory(requestFactory)
				.defaultHeader("Accept", "application/vnd.github+json")
				.defaultHeader("X-GitHub-Api-Version", github.getApiVersion())
				.build();
	}
}
