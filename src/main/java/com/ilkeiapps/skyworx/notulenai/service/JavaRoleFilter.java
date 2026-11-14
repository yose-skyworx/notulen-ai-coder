package com.ilkeiapps.skyworx.notulenai.service;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;

import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Decides whether a changed {@code .java} file is one of the Spring Boot roles worth reviewing —
 * service, controller, or entity. Files of every other language pass through untouched: this narrows
 * Java, it does not gate the commit.
 * <p>
 * That describes {@link #isExamined}, the decision a commit-based run makes once it has the file in hand.
 * {@link #isTargetByPath} is the whole-branch equivalent and is stricter: it works from the path alone and
 * whitelists the target roles across every language. See its own Javadoc for why the two differ.
 * <p>
 * <b>Three tiers, first hit wins.</b> Filename patterns, then directory patterns, then annotations in the
 * file content. The two pattern tiers are free, so they run first and settle the common case; the
 * annotation scan is the fallback for a correctly-annotated class in an unconventionally named file or
 * package. It costs nothing extra either, because it reads the snapshot the pipeline has already fetched
 * in order to build the review context — this filter never triggers a GitHub call of its own.
 * <p>
 * Running it on the fetched content is also what makes the tiers safe to order this way. Deciding from
 * the diff alone would be unreliable: a patch contains only the changed lines, so an edit near the bottom
 * of an existing {@code @Service} class carries no annotation to find.
 * <p>
 * <b>Heuristic, and honest about it.</b> The annotation tier searches text, so an annotation named inside
 * a comment or a string literal counts as a match. Parsing Java properly would mean a parser dependency
 * for a filter whose only job is to reduce noise; a false positive here costs one review, while a false
 * negative would silently drop a file the user asked to have examined. The bias is deliberate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JavaRoleFilter {

	private static final String JAVA_EXTENSION = ".java";

	private final CodeReviewProperties properties;

	/**
	 * {@code AntPathMatcher} is safe to share: immutable once constructed, and it caches compiled patterns
	 * in a concurrent map. Same reasoning as {@link ChangedFileClassifier}.
	 */
	private final AntPathMatcher pathMatcher = new AntPathMatcher();

	/**
	 * @param path    repository-relative path of the changed file
	 * @param content the file's full text at the reviewed commit; only consulted when both pattern tiers
	 *                miss, so it may be null without changing the outcome for a matched path
	 * @return true when the file should be examined — either it is not Java, or it is one of the roles
	 */
	public boolean isExamined(String path, String content) {
		var filter = properties.getJavaFilter();
		if (!filter.isEnabled() || !isJava(path)) {
			return true;
		}
		if (matchesAny(filter.getFilenamePatterns(), path)) {
			return true;
		}
		if (matchesAny(filter.getPathPatterns(), path)) {
			return true;
		}

		var examined = containsAnyAnnotation(filter.getAnnotations(), content);
		// Logged because this is the tier whose behaviour is least obvious from the configuration: a file
		// that matched no pattern was still examined, or one that looked like a target was not.
		log.debug("java role filter: {} settled by annotation scan -> {}", path, examined);
		return examined;
	}

	/**
	 * Path-only eligibility, used by a whole-branch review to settle what is examinable before anything is
	 * fetched. The two pattern tiers only, first hit wins: no {@code .java} gate and no annotation scan.
	 * <p>
	 * Both omissions are deliberate, and both make this stricter than {@link #isExamined}. A branch listing
	 * holds every file in the repository, so here the patterns act as a whitelist over the whole tree rather
	 * than as a narrowing of Java — only service, controller and entity files, plus everything under a
	 * service, controller or entity directory. Dropping the {@code .java} gate is what lets the directory
	 * tier reach the non-Java files that live beside them; a whole-branch review that examined every
	 * {@code .vue} and {@code .sql} in the repository would be reviewing the frontend, not the roles this
	 * filter exists to isolate.
	 * <p>
	 * The annotation tier is dropped because it reads content. Consulting it here would mean fetching every
	 * Java file that missed both patterns — thousands of GitHub calls to catch the occasional
	 * unconventionally named {@code @Service}, all spent before the file ceiling could bound anything. A
	 * whole-branch run accepts that false negative; a commit run, which has already fetched the file, keeps
	 * the tier.
	 * <p>
	 * A disabled filter examines everything, matching {@link #isExamined}.
	 *
	 * @param path repository-relative path from the branch's tree listing
	 * @return true when the file is one a whole-branch review examines
	 */
	public boolean isTargetByPath(String path) {
		var filter = properties.getJavaFilter();
		if (!filter.isEnabled()) {
			return true;
		}
		return matchesAny(filter.getFilenamePatterns(), path) || matchesAny(filter.getPathPatterns(), path);
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
	 * Extension check only. Deliberately not a language guess from {@link ChangedFileClassifier}: the
	 * filter must apply to exactly {@code .java} and to nothing else, and reusing an inferred language
	 * would drag Kotlin and Scala files into a filter configured for Spring Boot roles.
	 */
	private static boolean isJava(String path) {
		return path != null && path.toLowerCase(Locale.ROOT).endsWith(JAVA_EXTENSION);
	}
}
