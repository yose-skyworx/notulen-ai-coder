package com.ilkeiapps.skyworx.notulenai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup TLS fix for the internal {@code *.example.internal} endpoints. Their certificate is publicly
 * rooted (DigiCert Global Root G2, already in the JDK trust store) but the servers present an
 * <em>incomplete chain</em> — only the leaf, omitting the DigiCert intermediate. The JDK/OkHttp PKIX
 * validator does not fetch missing issuers on its own, so chat calls through Embabel's {@code
 * openai-custom} OkHttp client fail with {@code SSLHandshakeException: unable to find valid
 * certification path to requested target} (OpenSSL reports {@code verify code 21}).
 * <p>
 * Enabling {@code com.sun.security.enableAIAcaIssuers} makes the JDK fetch the missing intermediate via
 * the leaf's Authority Information Access extension and complete the path to the trusted DigiCert root.
 * This preserves full certificate validation — it is a secure fix, unlike the dev trust-all toggle in
 * {@link InsecureTlsConfiguration}, and it also covers OkHttp-based clients that never see Spring AI's
 * per-client customizers (Embabel 1.5.4's {@code openai-custom} provider does not forward them).
 * <p>
 * Must run before any TLS handshake, so it is called from {@code main} before {@code
 * SpringApplication.run}. It is always safe to enable and is left untouched if explicitly configured.
 */
public final class TlsBootstrap {

	private static final Logger log = LoggerFactory.getLogger(TlsBootstrap.class);
	private static final String AIA_PROPERTY = "com.sun.security.enableAIAcaIssuers";

	private TlsBootstrap() {
	}

	/** Enables AIA CA-issuer fetching so incomplete server certificate chains can still be validated. */
	public static void enableAiaIntermediateFetching() {
		if (System.getProperty(AIA_PROPERTY) != null) {
			return;
		}
		System.setProperty(AIA_PROPERTY, "true");
		log.info("Enabled {}=true so the JVM fetches missing intermediate certificates and can validate the "
				+ "internal endpoints' incomplete (but publicly-rooted) certificate chains.", AIA_PROPERTY);
	}
}
