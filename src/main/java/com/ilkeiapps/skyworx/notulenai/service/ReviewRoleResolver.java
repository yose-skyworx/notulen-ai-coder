package com.ilkeiapps.skyworx.notulenai.service;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;

import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewRoleType;
import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves which review role a changed file plays — service, controller, or entity. The role is what
 * decides which guideline a run judges the file against, so this is not a filter that narrows the review:
 * a file with no role has no guideline to be measured against and is not reviewed at all. The caller
 * records it as skipped rather than dropping it.
 * <p>
 * <b>Three tiers, and the tier beats the role.</b> Every role's filename patterns are checked first, then
 * every role's directory patterns, then annotations in the file content. Ordering it the other way round —
 * finishing one role's three tiers before starting the next role — would let a directory match on SERVICE
 * outrank a filename match on CONTROLLER, and the filename is the more specific claim. Within a tier,
 * roles are consulted in {@link ReviewRoleType} declaration order.
 * <p>
 * The two pattern tiers are free, so they run first and settle the common case. The annotation tier is the
 * fallback for a correctly-annotated class in an unconventionally named file or package, and it costs
 * nothing extra either: it reads the snapshot the pipeline has already fetched in order to build the
 * review context, so this class never triggers a GitHub call of its own.
 * <p>
 * Running the annotation tier on the fetched content is also what makes it reliable. Deciding from the diff
 * alone would not be: a patch contains only the changed lines, so an edit near the bottom of an existing
 * {@code @Service} class carries no annotation to find.
 * <p>
 * The directory tiers are not restricted to Java, deliberately — a migration or a configuration file
 * living under a role's directory is part of that role and is reviewed against its guideline. A file of
 * any language outside those directories resolves to nothing.
 * <p>
 * <b>Heuristic, and honest about it.</b> The annotation tier searches text, so an annotation named inside
 * a comment or a string literal counts as a match. Parsing Java properly would mean a parser dependency
 * for a resolver whose only job is to reduce noise; a false positive costs one review, while a false
 * negative silently drops a file the user asked to have examined. The bias is deliberate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewRoleResolver {

	private static final String JAVA_EXTENSION = ".java";

	private final CodeReviewProperties properties;

	/**
	 * {@code AntPathMatcher} is safe to share: immutable once constructed, and it caches compiled patterns
	 * in a concurrent map. Same reasoning as {@link ChangedFileClassifier}.
	 */
	private final AntPathMatcher pathMatcher = new AntPathMatcher();

	/**
	 * Resolves the role from the path alone, which is all a whole-branch run has before it fetches
	 * anything. The two pattern tiers only.
	 * <p>
	 * The annotation tier is left out because it reads content, and consulting it here would mean fetching
	 * every Java file in the branch that missed both patterns — thousands of GitHub calls to catch the
	 * occasional unconventionally named {@code @Service}, all spent before the file ceiling could bound
	 * anything. A whole-branch run accepts that false negative; {@link #roleOf} does not have to.
	 *
	 * @param path repository-relative path
	 * @return the role, or null when no role's patterns match
	 */
	public ReviewRoleType roleOfPath(String path) {
		if (path == null) {
			return null;
		}
		var role = resolveByPatterns(path);
		// One line per path reporting the role it actually resolved to (null = not a target role). Debug,
		// not info: this runs once per file in a whole-branch listing, which is the entire repository.
		log.debug("review role (pattern): {} -> {}", path, role);
		return role;
	}

	/**
	 * The two pattern tiers, filename before directory, returning the first role that matches or null.
	 * Separated from {@link #roleOfPath} so the resolved role can be logged in one place without a log
	 * statement firing on every role the loop merely considered.
	 */
	private ReviewRoleType resolveByPatterns(String path) {
		for (ReviewRoleType role : ReviewRoleType.values()) {
			if (matchesAny(configFor(role).getFilenamePatterns(), path)) {
				return role;
			}
		}
		for (ReviewRoleType role : ReviewRoleType.values()) {
			if (matchesAny(configFor(role).getPathPatterns(), path)) {
				return role;
			}
		}
		return null;
	}

	/**
	 * Resolves the role with the file content available as a final fallback, which a commit-based run can
	 * afford because it has already fetched the file to build the review context.
	 *
	 * @param path    repository-relative path of the changed file
	 * @param content the file's full text at the reviewed commit; only consulted when both pattern tiers
	 *                miss, so it may be null without changing the outcome for a path that matched
	 * @return the role, or null when the file plays none of them
	 */
	public ReviewRoleType roleOf(String path, String content) {
		var byPath = roleOfPath(path);
		if (byPath != null) {
			return byPath;
		}
		if (!isJava(path)) {
			return null;
		}

		for (ReviewRoleType role : ReviewRoleType.values()) {
			if (containsAnyAnnotation(configFor(role).getAnnotations(), content)) {
				// Logged because this is the tier whose behaviour is least obvious from the configuration:
				// a file that matched no pattern at all was still given a role.
				log.debug("review role: {} settled by annotation scan -> {}", path, role);
				return role;
			}
		}
		return null;
	}

	/**
	 * Exhaustive on purpose: adding a member to {@link ReviewRoleType} without giving it a configuration
	 * block is a compile error here rather than a role that silently never resolves.
	 */
	private CodeReviewProperties.Role configFor(ReviewRoleType role) {
		var filter = properties.getJavaFilter();
		return switch (role) {
			case SERVICE -> filter.getService();
			case CONTROLLER -> filter.getController();
			case ENTITY -> filter.getEntity();
		};
	}

	private boolean matchesAny(List<String> patterns, String path) {
		if (patterns == null || patterns.isEmpty()) {
			return false;
		}
		for (String pattern : patterns) {
			if (pattern != null && !pattern.isBlank() && pathMatcher.match(pattern.trim(), path)) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsAnyAnnotation(List<String> annotations, String content) {
		if (annotations == null || annotations.isEmpty() || content == null || content.isEmpty()) {
			return false;
		}
		for (String annotation : annotations) {
			if (annotation != null && !annotation.isBlank() && containsAnnotation(content, annotation.trim())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whole-token search: the character after the annotation name must not continue an identifier, so
	 * {@code @Controller} matches {@code @Controller public class} but not {@code @ControllerAdvice}.
	 * {@code indexOf} rather than a compiled regex because this runs once per file and a regex would have
	 * to be compiled and cached to match that cost.
	 */
	private static boolean containsAnnotation(String content, String annotation) {
		var from = 0;
		while (true) {
			var at = content.indexOf(annotation, from);
			if (at < 0) {
				return false;
			}
			var after = at + annotation.length();
			if (after >= content.length() || !Character.isJavaIdentifierPart(content.charAt(after))) {
				return true;
			}
			from = after;
		}
	}

	/**
	 * Extension check, and it gates only the annotation tier. The pattern tiers apply to every language so
	 * that a role's directory reaches the non-Java files living in it; scanning a markdown or SQL file for
	 * a Java annotation would only ever produce a false positive.
	 */
	private static boolean isJava(String path) {
		return path != null && path.toLowerCase(Locale.ROOT).endsWith(JAVA_EXTENSION);
	}
}
