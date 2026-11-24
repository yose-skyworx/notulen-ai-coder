package com.ilkeiapps.skyworx.notulenai.bean.enums;

/**
 * The engine that evaluates a review rule, and the discriminator the runner branches on. Persisted as a
 * string ({@code @Enumerated(EnumType.STRING)}) — never by ordinal, so the set can be reordered safely.
 * <p>
 * This decides what {@link com.ilkeiapps.skyworx.notulenai.entity.CodeReviewGuideline#getContent() content}
 * <em>means</em>, which is why one column can serve both mechanisms: for {@link #LLM} it is prose that goes
 * into the prompt, for {@link #PMD} it is a reference into the ruleset that ships with {@code pmd-java} and
 * never reaches a model at all.
 * <p>
 * It also decides whether a rule can run while inference is disabled. {@link #PMD} is deterministic, local
 * and reports exact lines, so it needs no model; {@link #LLM} does. That is what stops
 * {@code code-review.inference.enabled} from being a whole-file gate.
 */
public enum ReviewMechanism {

	LLM, PMD
}
