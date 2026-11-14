package com.ilkeiapps.skyworx.notulenai.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;

import com.ilkeiapps.skyworx.notulenai.bean.enums.FileChangeStatus;
import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.BranchFile;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.CommitFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns the raw file list from GitHub into review decisions: what kind of change each file is, what
 * language it is written in, whether it is reviewed at all, and the hash that identifies the content a
 * review covered.
 * <p>
 * Two entry points, because the module has two run strategies. {@link #classify} takes a commit's changed
 * files, where a patch exists and the hash is of that patch. {@link #classifyBranchFiles} takes a branch's
 * whole file listing, where there is no change to describe and the hash is the git blob SHA GitHub already
 * reported. Both produce the same {@link ClassifiedFile} shape so the rest of the pipeline does not need
 * to know which strategy produced it.
 * <p>
 * They do not gate on the same things. A commit's file list is already short and already interesting —
 * someone changed those files — so only exclusion and patch presence are checked here, and
 * {@link JavaRoleFilter#isExamined} settles the role question later on content the pipeline has fetched
 * anyway. A branch's listing is the entire repository, so {@link JavaRoleFilter#isTargetByPath} gates it up
 * front, from the path alone.
 * <p>
 * Every rejected file is recorded with a {@code skipReason} rather than dropped. A run that silently
 * omitted half a commit would look identical to a run that reviewed everything and found nothing, and
 * the difference between those two is the whole point of the module.
 * <p>
 * Language is informational only — it is persisted for reporting and never gates eligibility. Deciding
 * reviewability from a guessed language would quietly exclude any file whose extension is missing or
 * unrecognised, which is exactly the class of file most likely to hide a problem.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangedFileClassifier {

	/** The path matched one of {@code code-review.exclusions.patterns}. */
	public static final String SKIP_EXCLUDED = "EXCLUDED_PATTERN";
	/** GitHub omitted the diff: a binary file, or a diff too large to inline. */
	public static final String SKIP_NO_PATCH = "NO_PATCH_CONTENT";
	/** Past {@code code-review.branch-review.max-files}; only a whole-branch run can reach it. */
	public static final String SKIP_BRANCH_FILE_LIMIT = "BRANCH_FILE_LIMIT";
	/**
	 * Not one of the roles this module reviews. A whole-branch run records it during classification, from
	 * the path alone; a commit run records the same token later, in {@code reviewOneFile}, when the fetched
	 * content matches no annotation either. One reason covers both so a report reads the same whichever
	 * strategy produced it.
	 */
	public static final String SKIP_NOT_TARGET_ROLE = "NOT_A_TARGET_ROLE";

	/** Extension to language. Deliberately small; an unknown extension yields null, not a guess. */
	private static final Map<String, String> LANGUAGES = Map.ofEntries(
			Map.entry("java", "Java"),
			Map.entry("kt", "Kotlin"),
			Map.entry("scala", "Scala"),
			Map.entry("groovy", "Groovy"),
			Map.entry("js", "JavaScript"),
			Map.entry("jsx", "JavaScript"),
			Map.entry("mjs", "JavaScript"),
			Map.entry("cjs", "JavaScript"),
			Map.entry("ts", "TypeScript"),
			Map.entry("tsx", "TypeScript"),
			Map.entry("vue", "Vue"),
			Map.entry("py", "Python"),
			Map.entry("go", "Go"),
			Map.entry("rs", "Rust"),
			Map.entry("rb", "Ruby"),
			Map.entry("php", "PHP"),
			Map.entry("cs", "C#"),
			Map.entry("c", "C"),
			Map.entry("h", "C"),
			Map.entry("cpp", "C++"),
			Map.entry("hpp", "C++"),
			Map.entry("swift", "Swift"),
			Map.entry("sql", "SQL"),
			Map.entry("sh", "Shell"),
			Map.entry("bash", "Shell"),
			Map.entry("ps1", "PowerShell"),
			Map.entry("yaml", "YAML"),
			Map.entry("yml", "YAML"),
			Map.entry("json", "JSON"),
			Map.entry("xml", "XML"),
			Map.entry("html", "HTML"),
			Map.entry("css", "CSS"),
			Map.entry("scss", "SCSS"),
			Map.entry("md", "Markdown"),
			Map.entry("properties", "Properties"),
			Map.entry("dockerfile", "Dockerfile"));

	private final CodeReviewProperties properties;
	private final JavaRoleFilter javaRoleFilter;

	/**
	 * {@code AntPathMatcher} is safe to share: it is immutable once constructed and caches compiled
	 * patterns in a concurrent map, so a singleton service can hold one instance.
	 */
	private final AntPathMatcher pathMatcher = new AntPathMatcher();

	/**
	 * One file and its review decision.
	 *
	 * @param skipReason  null when the file should be reviewed; a stable token explaining why not
	 *                    otherwise, persisted to {@code code_review_run_files.skip_reason}
	 * @param contentHash identifies the content a review covered, so a re-run can tell an unchanged file
	 *                    from a changed one. SHA-256 of the patch for a commit-based run, the git blob SHA
	 *                    for a whole-branch run. Never null.
	 */
	public record ClassifiedFile(CommitFile file, FileChangeStatus changeStatus, String language,
			String skipReason, String contentHash) {

		public boolean reviewable() {
			return skipReason == null;
		}
	}

	/**
	 * Classifies every file in a commit. Exclusion is checked before patch presence so that a lock file
	 * or a bundled asset reports the reason it was actually rejected — a configured exclusion — rather
	 * than an incidental property of how GitHub chose to represent it.
	 */
	public List<ClassifiedFile> classify(List<CommitFile> files) {
		if (files == null || files.isEmpty()) {
			return List.of();
		}

		var patterns = properties.getExclusions().getPatterns();
		var classified = new ArrayList<ClassifiedFile>(files.size());
		var skipped = 0;

		for (CommitFile file : files) {
			var path = file.path();
			String skipReason = null;

			if (isExcluded(path, patterns)) {
				skipReason = SKIP_EXCLUDED;
			} else if (file.patch() == null || file.patch().isBlank()) {
				skipReason = SKIP_NO_PATCH;
			}

			if (skipReason != null) {
				skipped++;
			}
			classified.add(new ClassifiedFile(file, changeStatusOf(file.status()), languageOf(path), skipReason,
					sha256Hex(file.patch() == null ? "" : file.patch())));
		}

		log.info("classifier: {} file(s), {} reviewable, {} skipped", files.size(), files.size() - skipped, skipped);
		return List.copyOf(classified);
	}

	/**
	 * Classifies every file in a branch, for a run that reviews the branch as it stands rather than one
	 * commit's diff. There is no patch whose absence could be a reason to skip — the file's own content is
	 * fetched and read later regardless — so exclusion, the target-role whitelist and the file ceiling
	 * apply, in that order.
	 * <p>
	 * {@code changeStatus} is {@code MODIFIED} for all of them, because the column is {@code NOT NULL} and
	 * a branch listing describes no change at all. {@code MODIFIED} is the value whose handling is correct
	 * for an unknown shape of change — the file is read at the reviewed commit and findings are validated
	 * against the {@code NEW} side, which is where its content is.
	 * <p>
	 * {@code additions} and {@code deletions} stay null and {@code previousFilename} stays null: neither
	 * has a meaning without a diff to compare against, and inventing a zero would report "this file changed
	 * by nothing" where the truth is "nothing was compared".
	 * <p>
	 * The whitelist is {@link JavaRoleFilter#isTargetByPath}, and running it here rather than after the
	 * fetch is what makes the ceiling mean something. Both decisions look like the same skip reason in the
	 * response, but ordering them the other way round would spend the whole budget on whatever git lists
	 * first — {@code .github/}, root configs, {@code docs/} — and reject all of it, while the service and
	 * controller files further down the tree were never reached. A branch review that reported 200 files
	 * covered and 200 skipped would look exactly like one that reviewed the code and found nothing.
	 * <p>
	 * {@code code-review.branch-review.max-files} is applied last, to the files that survived exclusion and
	 * the whitelist, because the ceiling is a review-eligibility decision like any other and produces a skip
	 * reason like any other. Only the branch strategy needs it: a commit's file list is bounded by what one
	 * commit touched, while a branch's is bounded by the size of the repository, and each examinable file
	 * costs one content fetch and one model call at a file concurrency of one. The overflow is marked, never
	 * dropped, so the run still accounts for every file the branch holds.
	 */
	public List<ClassifiedFile> classifyBranchFiles(List<BranchFile> files) {
		if (files == null || files.isEmpty()) {
			return List.of();
		}

		var patterns = properties.getExclusions().getPatterns();
		var maxFiles = properties.getBranchReview().getMaxFiles();
		var classified = new ArrayList<ClassifiedFile>(files.size());
		var excluded = 0;
		var notTarget = 0;
		var examinable = 0;
		var overLimit = 0;

		for (BranchFile file : files) {
			var path = file.path();

			// GitHub always reports a blob SHA for a tree entry; hashing the path is the fallback that keeps
			// the column populated and the resume comparison null-safe if one is ever absent.
			var blobSha = file.blobSha();
			var contentHash = blobSha == null || blobSha.isBlank() ? sha256Hex(path) : blobSha;

			String skipReason = null;
			if (isExcluded(path, patterns)) {
				skipReason = SKIP_EXCLUDED;
				excluded++;
			} else if (!javaRoleFilter.isTargetByPath(path)) {
				skipReason = SKIP_NOT_TARGET_ROLE;
				notTarget++;
			} else if (maxFiles > 0 && examinable == maxFiles) {
				// The first maxFiles examinable files in tree order are kept. Arbitrary, but stable: git's
				// tree order does not change between runs, so re-triggering resumes the same run and
				// re-examines the same files rather than a different arbitrary slice.
				skipReason = SKIP_BRANCH_FILE_LIMIT;
				overLimit++;
			} else {
				examinable++;
			}

			classified.add(new ClassifiedFile(new CommitFile(path, null, null, null, null, null),
					FileChangeStatus.MODIFIED, languageOf(path), skipReason, contentHash));
		}

		log.info("branch classifier: {} file(s), {} examinable, {} excluded, {} outside the target roles, "
				+ "{} over the file limit", files.size(), examinable, excluded, notTarget, overLimit);
		if (overLimit > 0) {
			log.warn("branch classifier: {} file(s) exceeded max-files={} and are marked {}",
					overLimit, maxFiles, SKIP_BRANCH_FILE_LIMIT);
		}
		return List.copyOf(classified);
	}

	private boolean isExcluded(String path, List<String> patterns) {
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

	/**
	 * Maps GitHub's {@code status} string onto the module's enum. {@code copied} counts as an addition
	 * because a copy is new content to review, and anything unrecognised — including {@code changed},
	 * {@code unchanged}, and a null status — falls back to {@code MODIFIED}, the only value whose
	 * handling is correct for an unknown shape of change.
	 */
	private static FileChangeStatus changeStatusOf(String githubStatus) {
		if (githubStatus == null) {
			return FileChangeStatus.MODIFIED;
		}
		return switch (githubStatus.toLowerCase(Locale.ROOT)) {
			case "added", "copied" -> FileChangeStatus.ADDED;
			case "removed" -> FileChangeStatus.REMOVED;
			case "renamed" -> FileChangeStatus.RENAMED;
			default -> FileChangeStatus.MODIFIED;
		};
	}

	/**
	 * Language from the file extension, or null when there is none worth claiming. Dotfiles such as
	 * {@code .gitignore} are treated as extensionless: the dot is a filename convention there, not a
	 * type separator.
	 */
	private static String languageOf(String path) {
		if (path == null) {
			return null;
		}
		var slash = path.lastIndexOf('/');
		var dot = path.lastIndexOf('.');
		if (dot <= slash + 1 || dot == path.length() - 1) {
			return null;
		}
		return LANGUAGES.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	private static String sha256Hex(String text) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}
}
