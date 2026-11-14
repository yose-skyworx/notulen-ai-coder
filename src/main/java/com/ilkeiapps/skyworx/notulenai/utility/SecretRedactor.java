package com.ilkeiapps.skyworx.notulenai.utility;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Strips credentials out of repository content before it is handed to a model.
 * <p>
 * Repository content is untrusted input that frequently carries secrets by accident — a committed
 * {@code .env} sample, a connection string in a test fixture, a private key pasted into a README.
 * Sending that to an inference endpoint publishes it to a third party, so every guideline, diff, and
 * source snapshot passes through here first.
 * <p>
 * Redaction is best-effort by nature: it catches the shapes that matter and cannot catch a secret that
 * looks like an ordinary identifier. The point is to make an accidental leak unlikely, not to make
 * committed secrets safe. They are still committed, and still need rotating.
 * <p>
 * Patterns are ordered deliberately. Broad structural rules run before the generic
 * {@code key = value} rule, because a generic assignment match would otherwise consume only part of a
 * longer token shape and leave a recognisable fragment behind.
 */
public final class SecretRedactor {

	/** Placeholder left in place of a removed secret, so the surrounding text still reads as code. */
	private static final String REPLACEMENT = "[REDACTED]";

	/** Replacement for rules whose group 1 is the label being assigned to, which is not itself secret. */
	private static final String KEEP_GROUP_1 = "$1" + REPLACEMENT;

	/**
	 * The {@code password = getPassword()} shape is a method call, not a secret, so the bare-value
	 * alternative excludes parentheses, quotes, and statement terminators, and then refuses to match when
	 * an open paren follows.
	 * <p>
	 * The quantifier is <b>possessive</b> on purpose. A greedy {@code {6,}} would backtrack off the final
	 * character to satisfy the lookahead — matching {@code getPasswor} out of {@code getPassword()} — which
	 * is both a false positive and a mangled line of source. Possessive matching gives up the whole
	 * alternative instead. Requiring six characters also keeps {@code pwd = ""} and {@code secret: null}
	 * out of the way.
	 */
	private static final String GENERIC_VALUE = "(?:\"[^\"]*\"|'[^']*'|[^\\s'\"();,]{6,}+(?!\\())";

	/**
	 * URL userinfo: {@code scheme://user:pass@host} keeps its scheme and host, loses the credentials. The
	 * host is not part of the match, so it survives untouched; the trailing {@code @} is restored by the
	 * replacement because the delimiter itself is what marks the end of the credential.
	 */
	private static final Pattern URL_USERINFO =
			Pattern.compile("(?i)\\b([a-z][a-z0-9+.\\-]*://)[^/\\s:@]+:[^/\\s:@]+@");

	private static final List<Rule> RULES = List.of(
			// PEM blocks first: they span many lines and contain no keyword the later rules would catch.
			new Rule(Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")),
			// Provider-issued tokens. Matched whole so no fragment survives.
			new Rule(Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}")),
			new Rule(Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{22,}")),
			new Rule(Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
			new Rule(Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}")),
			new Rule(Pattern.compile("\\bAIza[0-9A-Za-z\\-_]{35}\\b")),
			// Header values, with or without the bearer scheme. The header name stays: which header
			// carried the credential is exactly what makes the finding actionable.
			new Rule(Pattern.compile("(?i)\\b(authorization\\s*:\\s*)(?:bearer\\s+)?[^\\r\\n]{8,}"), KEEP_GROUP_1),
			new Rule(URL_USERINFO, KEEP_GROUP_1 + "@"),
			// Generic key = value assignments. The key stays for the same reason as the header name.
			new Rule(Pattern.compile("(?i)(\\b(?:password|passwd|pwd|secret|api[_-]?key|apikey|access[_-]?key"
					+ "|private[_-]?key|client[_-]?secret|auth[_-]?token|token)\\b\\s*[:=]\\s*)" + GENERIC_VALUE),
					KEEP_GROUP_1));

	private SecretRedactor() {
	}

	/**
	 * Returns the input with every recognised credential shape replaced by {@code [REDACTED]}. Null and
	 * blank input is returned unchanged, so callers do not need to guard.
	 */
	public static String redact(String text) {
		if (text == null || text.isBlank()) {
			return text;
		}

		var result = text;
		for (Rule rule : RULES) {
			result = rule.pattern().matcher(result).replaceAll(rule.replacement());
		}
		return result;
	}

	/**
	 * A pattern plus what replaces its whole match. Rules that capture a prefix they mean to keep carry
	 * {@link #KEEP_GROUP_1}; the rest replace the entire match, including any groups.
	 */
	private record Rule(Pattern pattern, String replacement) {

		Rule(Pattern pattern) {
			this(pattern, REPLACEMENT);
		}
	}
}
