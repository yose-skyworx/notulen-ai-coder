package com.ilkeiapps.skyworx.notulenai.bean.enums;

/**
 * The Spring Boot role a reviewed file plays. This is the key a code-review guideline is stored under:
 * a run resolves a file's role, then loads the active guideline for that role and inspects the file
 * against it.
 * <p>
 * There is no catch-all member on purpose. A file that resolves to no role has no guideline to be judged
 * against, so it is recorded as {@code SKIPPED} rather than reviewed against rules written for something
 * else — a README measured against the service guideline produces findings that mean nothing.
 * <p>
 * Declaration order is the tie-break when two roles match in the same tier, so it is part of the
 * behaviour and not incidental. It rarely decides anything: the filename tier is checked before the
 * directory tier, and a Java file's name almost always settles the role on its own. Order matters for a
 * path such as a controller sitting inside a service package, where both directory patterns match and the
 * shallower role wins.
 */
public enum ReviewRoleType {

	SERVICE, CONTROLLER, ENTITY
}
