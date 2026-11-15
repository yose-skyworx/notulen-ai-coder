package com.ilkeiapps.skyworx.notulenai.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Typed configuration for the automated code-review module, bound from {@code code-review.*}: GitHub
 * API access and credentials, the daily schedule, execution limits, the per-file context budget, and
 * exclusion patterns. These are tunable defaults, not hardcoded constants.
 * <p>
 * The module is <b>disabled by default</b> ({@code enabled = false}) and AI review is separately
 * disabled ({@code inference.enabled = false}), so adding it changes nothing about a running
 * application until both are deliberately switched on.
 * <p>
 * The {@code context.*} token budgets are carried over from the module specification as placeholders
 * for a future, more capable chat model. They are <b>not</b> tuned to the model deployed today
 * ({@code lfm-1.2b}), which produces unusable output well below these sizes — see the measurements
 * recorded on {@code ProjectKnowledgeRagAgent}. They stay configuration-driven so a larger model can be
 * adopted by editing properties rather than code.
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "code-review")
public class CodeReviewProperties {

	/** Master switch. When false, no scheduler is registered and the endpoints report the module as off. */
	private boolean enabled = false;

	/** IANA zone used to derive the previous-calendar-day review window and to run the cron trigger. */
	private String timezone = "Asia/Jakarta";

	private Schedule schedule = new Schedule();
	private Execution execution = new Execution();
	private Context context = new Context();
	private Inference inference = new Inference();
	private Github github = new Github();
	private Exclusions exclusions = new Exclusions();
	private JavaFilter javaFilter = new JavaFilter();
	private BranchReview branchReview = new BranchReview();

	@Setter
	@Getter
	public static class Schedule {
		/** Spring cron expression. The default runs five minutes after midnight in {@code timezone}. */
		private String cron = "0 5 0 * * *";
	}

	@Setter
	@Getter
	public static class Execution {
		/**
		 * Repositories reviewed in parallel. Kept at 1: the inference server is CPU-bound and the
		 * specification forbids raising concurrency before inference performance has been measured.
		 */
		private int globalConcurrency = 1;
		/** Files reviewed in parallel within one run. Kept at 1 for the same reason. */
		private int fileConcurrency = 1;
		/** Attempts per transient failure before the file is recorded as {@code FAILED}. */
		private int retryAttempts = 3;
		/** Base delay for the bounded exponential backoff, which also carries jitter. */
		private long retryInitialDelayMs = 3000;
	}

	@Setter
	@Getter
	public static class Context {
		private int maxInputTokens = 5000;
		private int maxOutputTokens = 1500;
		private int maxGuidelineTokens = 1200;
		private int maxDiffTokens = 1500;
		private int maxSourceContextTokens = 1200;
	}

	@Setter
	@Getter
	public static class Inference {
		/**
		 * When false, files are recorded as {@code SKIPPED} with reason {@code INFERENCE_DISABLED} and
		 * the run completes as {@code COMPLETED_WITH_WARNING}. Every deterministic step — GitHub
		 * retrieval, classification, guideline resolution, context building, persistence — still runs,
		 * so the pipeline is exercisable and testable with no model in the loop.
		 */
		private boolean enabled = false;
		private int timeoutSeconds = 300;
	}

	@Setter
	@Getter
	public static class Github {
		/**
		 * Deployment-wide personal access token, supplied via {@code GITHUB_TOKEN}. One credential serves
		 * every registered repository, so nothing secret is ever written to the database. There is
		 * deliberately no default: {@code application.properties} is git-tracked, and a token committed
		 * there is leaked to everyone who can clone the repository. Phase 3 reports an absent token as a
		 * configuration error rather than calling GitHub unauthenticated.
		 */
		private String token;

		/**
		 * The only host this module will ever call. It comes from configuration, never from a request
		 * body, so a client cannot redirect review traffic to an arbitrary host.
		 */
		private String baseUrl = "https://api.github.com";
		private String apiVersion = "2022-11-28";
		private Duration connectTimeout = Duration.ofSeconds(15);
		private Duration readTimeout = Duration.ofSeconds(60);
		/** Items requested per page; GitHub caps this at 100. */
		private int pageSize = 100;
		/**
		 * Hard bound on pages followed for any single listing. Prevents an unbounded walk of a
		 * repository's history or branch list from exhausting the rate limit or the heap.
		 */
		private int maxPages = 20;
		/** Largest source file fetched for context, in bytes. Larger files are skipped with a reason. */
		private long maxFileBytes = 1_048_576L;
	}

	@Setter
	@Getter
	public static class Exclusions {
		/**
		 * Ant-style path patterns excluded from review. A matching file is still recorded as a row with
		 * a skip reason — excluded files are never silently dropped.
		 */
		private List<String> patterns = List.of(
				"**/node_modules/**",
				"**/target/**",
				"**/dist/**",
				"**/build/**",
				"**/out/**",
				"**/.git/**",
				"**/*.min.js",
				"**/*.min.css",
				"**/*.map",
				"**/package-lock.json",
				"**/yarn.lock",
				"**/pnpm-lock.yaml",
				"**/*.lock",
				"**/*.class",
				"**/*.jar",
				"**/*.war",
				"**/*.png",
				"**/*.jpg",
				"**/*.jpeg",
				"**/*.gif",
				"**/*.ico",
				"**/*.pdf",
				"**/*.zip",
				"**/*.tar",
				"**/*.gz");
	}

	/**
	 * Decides which review role a file plays, and therefore which guideline a run judges it against. One
	 * {@link Role} block per role, because the patterns are what identify the role: a single flat list
	 * could say that a file is examinable but not which guideline applies to it.
	 * <p>
	 * Inside a role the three lists keep their original order — filename patterns, then directory
	 * patterns, then annotations found in the fetched content. Across roles the tiers still beat each
	 * other, so every role's filename list is consulted before any role's directory list. That is what
	 * settles {@code entity/OrderService.java}: it is a service by name, and the directory tier that would
	 * have called it an entity is never reached.
	 * <p>
	 * Setting a list replaces it wholesale rather than adding to the defaults, matching how the exclusion
	 * patterns above behave. A role whose three lists are all empty can never be resolved, so its files
	 * are skipped — emptying a role is how that role is turned off.
	 * <p>
	 * There is no {@code enabled} flag here any more. Role resolution is not an optional narrowing of what
	 * gets reviewed, it is how a file is matched to its guideline, so "off" would mean reviewing nothing.
	 * Use {@code code-review.enabled} to switch the module off.
	 * <p>
	 * Note that the directory patterns match paths rather than Java files, so a role's directory list also
	 * reaches {@code src/test/java/.../service/OrderServiceTest.java} and any non-Java file living under
	 * that directory. Add a {@code src/test} exclusion to {@link Exclusions#getPatterns()} if test sources
	 * should not be reviewed at all.
	 * <p>
	 * The Ant patterns themselves are not quoted in this Javadoc on purpose: a glob written as
	 * asterisk-asterisk-slash inside a block comment contains the two characters that close the comment.
	 */
	@Setter
	@Getter
	public static class JavaFilter {
		private Role service = new Role(
				List.of("**/*Service.java", "**/*ServiceImpl.java"),
				List.of("**/service/**", "**/services/**"),
				List.of("@Service"));

		private Role controller = new Role(
				List.of("**/*Controller.java", "**/*RestController.java"),
				List.of("**/controller/**", "**/controllers/**"),
				List.of("@RestController", "@Controller"));

		private Role entity = new Role(
				List.of("**/*Entity.java"),
				List.of("**/entity/**", "**/entities/**"),
				List.of("@Entity"));
	}

	/**
	 * The three pattern tiers for one review role.
	 * <p>
	 * The no-argument constructor is required by configuration binding and leaves every list empty; the
	 * per-role defaults live on the {@link JavaFilter} fields instead, so binding starts from the existing
	 * instance and only the properties actually set are replaced. Do not collapse the two constructors
	 * into one — with only the parameterized form, Spring binds by value object and the defaults are lost.
	 */
	@Setter
	@Getter
	public static class Role {
		/** Ant patterns checked first, against the whole path; these key off the file name. */
		private List<String> filenamePatterns = List.of();

		/** Ant patterns checked when no filename pattern matched — these key off the directory. */
		private List<String> pathPatterns = List.of();

		/**
		 * Annotation names checked last, against the file content already fetched for the review. Matched
		 * on a whole-token boundary, so {@code @Controller} does not match {@code @ControllerAdvice}.
		 */
		private List<String> annotations = List.of();

		public Role() {
		}

		public Role(List<String> filenamePatterns, List<String> pathPatterns, List<String> annotations) {
			this.filenamePatterns = filenamePatterns;
			this.pathPatterns = pathPatterns;
			this.annotations = annotations;
		}
	}

	/**
	 * Whole-branch review, which covers every file a branch holds rather than one commit's diff. Only the
	 * {@code BRANCH_FULL} strategy reads this group; a commit-based run is bounded by what that commit
	 * touched and needs no ceiling of its own.
	 */
	@Setter
	@Getter
	public static class BranchReview {
		/**
		 * How many files one whole-branch run examines. Each examinable file costs one content fetch from
		 * GitHub and, when inference is on, one model call — at a file concurrency of one, so the wall-clock
		 * cost is the file count times the per-file cost. A large repository triggered without a ceiling
		 * would hold an HTTP connection open for hours and spend rate limit the rest of the module needs.
		 * <p>
		 * The overflow is recorded as {@code SKIPPED} with reason {@code BRANCH_FILE_LIMIT} rather than
		 * dropped, so the response still accounts for every file in the branch. Zero or negative disables
		 * the ceiling, which is only advisable for a repository whose size is already known to be small.
		 */
		private int maxFiles = 200;
	}
}
