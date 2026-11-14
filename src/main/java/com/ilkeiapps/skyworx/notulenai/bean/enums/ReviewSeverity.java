package com.ilkeiapps.skyworx.notulenai.bean.enums;

/**
 * Severity of a single automated code-review finding. Persisted as a string
 * ({@code @Enumerated(EnumType.STRING)}) — never by ordinal, so the set can be reordered safely.
 */
public enum ReviewSeverity {

	CRITICAL, HIGH, MEDIUM, LOW, INFO
}
