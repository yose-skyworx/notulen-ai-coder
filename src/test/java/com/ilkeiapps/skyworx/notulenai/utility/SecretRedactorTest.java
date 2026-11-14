package com.ilkeiapps.skyworx.notulenai.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the shapes that actually reach a model in this module: credentials committed by accident in
 * source, diffs, and guideline files. The false-positive cases matter as much as the positives — a
 * redactor that blanks ordinary code destroys the context the review depends on.
 */
class SecretRedactorTest {

	@Test
	void leavesNullAndBlankUnchanged() {
		assertNull(SecretRedactor.redact(null));
		assertEquals("", SecretRedactor.redact(""));
		assertEquals("   ", SecretRedactor.redact("   "));
	}

	@Test
	void redactsProviderIssuedTokens() {
		var redacted = SecretRedactor.redact("token in code: github_pat_11BR3BHKA0LsvgABCDEFGHIJKLMNOPQRSTUVWXYZ0123");
		assertFalse(redacted.contains("github_pat_"), "a token prefix must not survive: " + redacted);
		assertTrue(redacted.contains("[REDACTED]"));

		assertFalse(SecretRedactor.redact("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
				.contains("ghp_"));
		assertFalse(SecretRedactor.redact("key AKIAIOSFODNN7EXAMPLE here").contains("AKIAIOSFODNN7EXAMPLE"));
	}

	@Test
	void redactsAssignedSecrets() {
		var redacted = SecretRedactor.redact("spring.datasource.password=SuperSecret123");
		assertFalse(redacted.contains("SuperSecret123"), redacted);
		// The key survives so the line still reads as configuration rather than as noise.
		assertTrue(redacted.contains("password"), redacted);
	}

	@Test
	void keepsTheLabelThatIdentifiesWhatWasRedacted() {
		// Which key or header carried the credential is what makes the redacted line worth reading.
		assertEquals("password=[REDACTED]", SecretRedactor.redact("password=SuperSecret123"));
		assertEquals("Authorization: [REDACTED]",
				SecretRedactor.redact("Authorization: Bearer abcdefghijklmnop"));
	}

	@Test
	void leavesOrdinaryCodeAlone() {
		// A method call is not a secret. Blanking these would strip real logic from the context.
		var source = "String password = getPassword();\nif (token.equals(expected)) { return; }";
		assertEquals(source, SecretRedactor.redact(source));
	}

	@Test
	void redactsUrlCredentialsButKeepsTheHost() {
		var redacted = SecretRedactor.redact("url = jdbc:postgresql://user:secret@db.internal:5432/app");
		assertFalse(redacted.contains("secret"), redacted);
		// The host is not a credential and is needed for the finding to make sense.
		assertTrue(redacted.contains("db.internal:5432/app"), redacted);
	}

	@Test
	void redactsWholePrivateKeyBlocks() {
		var key = """
				-----BEGIN PRIVATE KEY-----
				MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7
				VJTUt9Us8cKjMzEfYyjiWA4R4/M2bS1GB4t7NXp98C3SC6dVMvDuic
				-----END PRIVATE KEY-----""";
		var redacted = SecretRedactor.redact("before\n" + key + "\nafter");
		assertFalse(redacted.contains("MIIEvQIBADANBgkqhkiG9w0BAQEFAASC"), redacted);
		assertTrue(redacted.contains("before"), redacted);
		assertTrue(redacted.contains("after"), redacted);
	}
}
