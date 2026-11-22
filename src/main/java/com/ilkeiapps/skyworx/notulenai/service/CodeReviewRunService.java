package com.ilkeiapps.skyworx.notulenai.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

import org.springframework.stereotype.Service;

import com.ilkeiapps.ilkeitool.validator.IlKeiValidator;
import com.ilkeiapps.ilkeitool.validator.ProcessResult;
import com.ilkeiapps.skyworx.notulenai.bean.enums.BranchReviewStatus;
import com.ilkeiapps.skyworx.notulenai.bean.enums.DiffSide;
import com.ilkeiapps.skyworx.notulenai.bean.enums.FileChangeStatus;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewRoleType;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewBranchFilesResult;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewBranchFilesResult.BranchFinding;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewBranchFilesResult.ClassifiedBranchFile;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewBranchTriggerRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewProjectTriggerRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewRunResult;
import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;
import com.ilkeiapps.skyworx.notulenai.service.ChangedFileClassifier.ClassifiedFile;
import com.ilkeiapps.skyworx.notulenai.service.CodeReviewGuidelineService.ActiveGuideline;
import com.ilkeiapps.skyworx.notulenai.service.CodeReviewRunPersistence.BranchRef;
import com.ilkeiapps.skyworx.notulenai.service.CodeReviewRunPersistence.ClaimedRun;
import com.ilkeiapps.skyworx.notulenai.service.CodeReviewRunPersistence.RepositoryRef;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.BranchFile;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.BranchTree;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.CommitInfo;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.GithubApiException;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.GithubAuthException;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.GithubNotFoundException;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.GithubRateLimitException;
import com.ilkeiapps.skyworx.notulenai.service.PmdService.PmdViolation;
import com.ilkeiapps.skyworx.notulenai.service.ReviewAgent.ReviewTarget;
import com.ilkeiapps.skyworx.notulenai.utility.Utility;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates a code review run: establish what is being reviewed, list and classify the files, resolve
 * each file's review role and the active guideline for that role, build a redacted context per file, ask
 * the {@link ReviewAgent}, validate what came back, and persist the lot.
 * <p>
 * <b>The standard is ours, not the repository's.</b> Guidelines live in this application's database — see
 * {@link CodeReviewGuidelineService} — so the repository under review contributes only the code being
 * judged. Nothing is read from it to decide what "correct" means, which is what lets one run review any
 * repository the same way and stops a repository from changing its own verdict by editing a file.
 * <p>
 * <b>Two run strategies.</b> {@code MANUAL} reviews one commit's diff — the latest commit that reached the
 * branch during a calendar day — and is what both the scheduler and {@code trigger-project} produce.
 * {@code BRANCH_FULL} reviews every file the branch holds at its current head, with no diff at all, and is
 * what {@code trigger-branch} produces. They share the claiming, guideline, per-file, persistence, and
 * finalization stages; only the step that establishes the file list differs, so {@link #executeRun} takes
 * the pipeline as a parameter rather than the two being written out twice.
 * <p>
 * <b>Transaction design.</b> This class is deliberately <em>not</em> {@code @Transactional}. A run makes
 * dozens of GitHub calls and (when inference is on) model calls, each of which can take seconds. Holding
 * a pooled connection across them would starve the pool and couple every other request in the
 * application to GitHub's latency, so the orchestrator runs untransactioned and defers every database
 * step to {@link CodeReviewRunPersistence}, whose public methods are each their own short Spring
 * transaction. They live in a separate bean on purpose: {@code @Transactional} is proxy-based, so a
 * same-class call would bypass the advice and run with no transaction. A late failure therefore cannot
 * roll back the files already reviewed, and the terminal status plus error message are always written in
 * their own committed transaction so the cause survives.
 * <p>
 * <b>Failure isolation.</b> A project fan-out reviews many repository×branch pairs, and each pair
 * reviews many files. Every level catches its own failures: one unreachable repository does not stop the
 * others, and one unreadable file does not stop the rest of its run. Each failure is recorded on the row
 * it belongs to rather than propagated, so the response explains what happened instead of returning
 * nothing.
 * <p>
 * <b>Idempotency.</b> {@code uq_crr_identity} makes (repository, branch, review_date, strategy) the run
 * identity, so re-triggering the same project on the same day resumes the existing runs instead of
 * creating duplicates. Within a run, a file whose content hash is unchanged and whose run still points at
 * the same commit is left alone, along with the findings already recorded against it.
 * <p>
 * <b>Read-only against GitHub.</b> Nothing here pushes, comments, opens a pull request, or runs
 * repository code. Source is fetched at an exact commit SHA, never at the branch head, so the line
 * numbers findings cite are the line numbers that existed in the reviewed commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeReviewRunService {

	/** Recorded on {@code code_review_runs.strategy}; distinct from the scheduler's {@code DAILY}. */
	private static final String STRATEGY_MANUAL = "MANUAL";

	/**
	 * Whole-branch review of the branch as it stands. Distinct from {@code MANUAL} so a branch review never
	 * resumes or overwrites the day's commit review, and vice versa.
	 */
	private static final String STRATEGY_BRANCH_FULL = "BRANCH_FULL";

	/**
	 * Skip reasons recorded on {@code code_review_run_files.skip_reason}. The other two —
	 * {@code ChangedFileClassifier.SKIP_NOT_TARGET_ROLE} and {@code SKIP_NO_GUIDELINE} — are emitted during
	 * classification; these three can only be discovered once a file's content is in hand.
	 * <p>
	 * There is deliberately no run-level status for a missing guideline. Guidelines are library rows keyed
	 * by role, so a gap is per-file, not per-run: a library missing only the entity standard still reviews
	 * every service and controller normally, and those files are skipped with a reason while the run ends
	 * {@code COMPLETED_WITH_WARNING} through the ordinary "nothing was analyzed" path in
	 * {@code CodeReviewRunPersistence.finalizeRun}. A terminal state would have claimed the whole run failed
	 * when part of it succeeded.
	 */
	private static final String SKIP_SOURCE_NOT_FOUND = "SOURCE_NOT_FOUND";
	private static final String SKIP_FILE_TOO_LARGE = "FILE_TOO_LARGE";
	private static final String SKIP_INFERENCE_DISABLED = "INFERENCE_DISABLED";

	/** Case-insensitive suffix that marks a path as Java source, the one language this review classifies. */
	private static final String JAVA_EXTENSION = ".java";

	/**
	 * Ceiling on how many paths one "eligible file(s)" log line names. Set to the default
	 * {@code branch-review.max-files} so a whole-branch run lists everything it is about to review rather
	 * than trailing off into "... and N more" — the point of the line is to see the selection.
	 */
	private static final int MAX_LOGGED_PATHS = 200;

	private final CodeReviewRunPersistence persistence;
	private final CodeReviewProperties properties;
	private final GithubApiClient githubApiClient;
	private final CommitSelectionService commitSelectionService;
	private final ChangedFileClassifier changedFileClassifier;
	private final ReviewRoleResolver roleResolver;
	private final CodeReviewGuidelineService guidelineService;
	private final ReviewContextBuilder reviewContextBuilder;
	private final ReviewAgent reviewAgent;
	private final PmdService pmdService;

	// ------------------------------------------------------------------ entry point

	/**
	 * Reviews every enabled repository of the project and every enabled branch selected under it. Blocks
	 * until all of them finish and returns one {@link CodeReviewRunResult} per run, each carrying its own
	 * terminal status plus the per-file breakdown of what it covered.
	 * <p>
	 * The day reviewed is {@code src.reviewDate} when given, otherwise the previous calendar day in
	 * {@code code-review.timezone} — the same target the scheduled job uses.
	 * <p>
	 * The response is {@code status=true} when at least one run executed. A repository with no branch
	 * selected is reported through the message while the runs that did execute are still returned —
	 * discarding them would hide real results behind an unrelated configuration gap.
	 */
	public ApiResponse<CodeReviewRunResult> triggerProject(CodeReviewProjectTriggerRequest src) {
		var wrap = new ApiResponse<CodeReviewRunResult>();
		wrap.setStatus(false);

		if (!properties.isEnabled()) {
			wrap.setMessage(Utility.getErrorMessage("codereview.module_disabled"));
			return wrap;
		}

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(CodeReviewProjectTriggerRequest.Fields.projectId, "Project").isMandatory().asLong()
				.withMinLen(1).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		if (!persistence.projectIsActive(src.getProjectId())) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.project_not_found"));
			return wrap;
		}

		var token = properties.getGithub().getToken();
		if (token == null || token.isBlank()) {
			wrap.setMessage(Utility.getErrorMessage("codereview.github.token_missing"));
			return wrap;
		}

		var repositories = persistence.findEnabledRepositories(src.getProjectId());
		if (repositories.isEmpty()) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.none_enabled"));
			return wrap;
		}

		// Resolved once so every repository in this fan-out reviews the same calendar day, whether that day
		// was named by the caller or derived. reviewZone() throws on an invalid configured zone, which is
		// correct: a wrong zone selects the wrong commit.
		var reviewDate = src.getReviewDate() != null
				? src.getReviewDate()
				: LocalDate.now(commitSelectionService.reviewZone()).minusDays(1);
		log.info("code review: project {} triggered for {}", src.getProjectId(), reviewDate);

		var anyRun = false;
		var missingBranches = false;

		for (RepositoryRef repository : repositories) {
			var branches = persistence.findEnabledBranches(repository.id());
			if (branches.isEmpty()) {
				missingBranches = true;
				log.warn("code review: repository {} has no enabled branch, nothing to review", repository.slug());
				continue;
			}

			for (BranchRef branch : branches) {
				try {
					var runId = executeRun(repository, branch, reviewDate, STRATEGY_MANUAL,
							(claimed, guidelines) -> runReview(claimed, guidelines, reviewDate));
					var result = persistence.loadRunResult(runId);
					if (result != null) {
						wrap.addData(result);
						anyRun = true;
					}
				} catch (RuntimeException e) {
					// Last resort: executeRun already records failures on the run row, so reaching here
					// means the run row itself could not be claimed. One branch must not stop the others.
					log.error("code review: could not run {} on {}", repository.slug(), branch.branchName(), e);
				}
			}
		}

		if (!anyRun) {
			wrap.setMessage(missingBranches
					? Utility.getErrorMessage("codereview.run.no_branch_selected")
					: Utility.getErrorMessage("codereview.run.item_not_found"));
			return wrap;
		}
		if (missingBranches) {
			wrap.setMessage(Utility.getErrorMessage("codereview.run.no_branch_selected"));
		}
		wrap.setStatus(true);
		return wrap;
	}

	/**
	 * A whole-branch review by the deterministic {@code PMD} mechanism, rebuilt step by step. It does five
	 * things and returns what they found:
	 * <ol>
	 * <li>gets the registered repository and branch rows for the ids in the request, checking the branch
	 * belongs to the repository;</li>
	 * <li>connects to that repository and branch on GitHub;</li>
	 * <li>retrieves every file the branch holds at its current head;</li>
	 * <li>classifies each file by the role it plays — service, controller, or entity — skipping any file
	 * that is not Java, and any Java file that plays none of those roles;</li>
	 * <li>fetches each classified file at the pinned head SHA and runs the {@link PmdService} rule set over
	 * it, collecting the violations.</li>
	 * </ol>
	 * The response carries the resolved head SHA, the reviewed files with their roles, and
	 * every PMD finding across the classified files. No model is involved — this is deterministic static
	 * analysis — so it produces findings whether or not inference is enabled. Nothing is persisted: this is
	 * the read-only {@code PMD} path, and the run/persistence pipeline below ({@link #executeRun},
	 * {@link #runBranchReview}) remains the route that records a run.
	 * <p>
	 * Both ids are mandatory and the branch must belong to the repository. A branch <em>name</em> is not
	 * accepted: naming the registered row makes the ownership check the query's job, so one id cannot be
	 * substituted for another's.
	 * <p>
	 * {@code enabled} is not required on either row. That flag governs inclusion in the automatic fan-out;
	 * an explicit request naming a branch is a deliberate act and should not be refused by a scheduling
	 * switch.
	 * <p>
	 * The GitHub listing runs outside any transaction — it is a series of network round trips — and its
	 * failures are mapped to a user-facing message rather than propagated, so a rejected token or an
	 * unreachable API comes back as {@code status=false} with a reason instead of a 500.
	 */
	public ApiResponse<CodeReviewBranchFilesResult> triggerBranch(CodeReviewBranchTriggerRequest src) {
		var wrap = new ApiResponse<CodeReviewBranchFilesResult>();
		wrap.setStatus(false);

		// Captured before any work so the persisted row's started_at spans the whole execution. The
		// pre-resolution failures below return before a repository/branch id exists, so nothing is persisted
		// for them; persistence begins only once both are resolved.
		var startedAt = Instant.now();

		if (!properties.isEnabled()) {
			wrap.setMessage(Utility.getErrorMessage("codereview.module_disabled"));
			return wrap;
		}

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(CodeReviewBranchTriggerRequest.Fields.repositoryId, "Repository").isMandatory().asLong()
				.withMinLen(1).pack()
				.pick(CodeReviewBranchTriggerRequest.Fields.branchId, "Branch").isMandatory().asLong()
				.withMinLen(1).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		var token = properties.getGithub().getToken();
		if (token == null || token.isBlank()) {
			wrap.setMessage(Utility.getErrorMessage("codereview.github.token_missing"));
			return wrap;
		}

		// 1. Get the repository and branch. findBranchRef's query scopes the branch to the repository, so a
		//    branch that belongs to another repository comes back null rather than being reviewed.
		var repository = persistence.findRepositoryRef(src.getRepositoryId());
		if (repository == null) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.item_not_found"));
			return wrap;
		}
		var branch = persistence.findBranchRef(repository.id(), src.getBranchId());
		if (branch == null) {
			wrap.setMessage(Utility.getErrorMessage("codereview.branch.not_in_repository"));
			return wrap;
		}

		// 2 + 3. Connect to the repository and branch on GitHub and retrieve every file the branch holds.
		//        listBranchTree pins the head commit first and lists its tree at that SHA, so the paths
		//        describe one immutable snapshot even if the branch moves while this runs.
		log.info("code review: whole-branch file listing requested for {} on {}", repository.slug(),
				branch.branchName());
		BranchTree tree;
		try {
			tree = githubApiClient.listBranchTree(repository.owner(), repository.repoName(), branch.branchName());
		} catch (GithubAuthException e) {
			log.warn("code review: GitHub rejected the token listing {} on {}", repository.slug(),
					branch.branchName(), e);
			wrap.setMessage(Utility.getErrorMessage("codereview.github.token_invalid"));
			recordBranchResult(repository.id(), branch.id(), startedAt, BranchReviewStatus.FAILED, null);
			return wrap;
		} catch (GithubNotFoundException e) {
			log.warn("code review: GitHub could not resolve {} on {}", repository.slug(), branch.branchName(), e);
			wrap.setMessage(Utility.getErrorMessage("codereview.github.not_found"));
			recordBranchResult(repository.id(), branch.id(), startedAt, BranchReviewStatus.FAILED, null);
			return wrap;
		} catch (GithubRateLimitException e) {
			log.warn("code review: GitHub rate limited listing {} on {}", repository.slug(), branch.branchName(), e);
			wrap.setMessage(Utility.getErrorMessage("codereview.github.rate_limited"));
			recordBranchResult(repository.id(), branch.id(), startedAt, BranchReviewStatus.FAILED, null);
			return wrap;
		} catch (GithubApiException e) {
			log.error("code review: GitHub call failed listing {} on {}", repository.slug(), branch.branchName(), e);
			wrap.setMessage(Utility.getErrorMessage("codereview.github.unreachable"));
			recordBranchResult(repository.id(), branch.id(), startedAt, BranchReviewStatus.FAILED, null);
			return wrap;
		}

		// 4. Classify each file by the role it plays, keeping only the files that will be reviewed. A non-Java
		//    file, and a Java file that matches none of the service/controller/entity rules, is counted but
		//    not listed — the response is the branch's reviewed subset, not every path. roleOfPath carries the
		//    actual rule (filename tier, then directory tier), reused rather than reimplemented.
		var totalFiles = tree.files().size();
		var files = new ArrayList<ClassifiedBranchFile>();
		var serviceCount = 0;
		var controllerCount = 0;
		var entityCount = 0;
		var skippedCount = 0;
		for (BranchFile file : tree.files()) {
			var path = file.path();
			if (!isJavaFile(path)) {
				skippedCount++;
				continue;
			}
			var role = roleResolver.roleOfPath(path);
			if (role == null) {
				skippedCount++;
				continue;
			}
			files.add(new ClassifiedBranchFile(path, role));
			switch (role) {
				case SERVICE -> serviceCount++;
				case CONTROLLER -> controllerCount++;
				case ENTITY -> entityCount++;
			}
		}
		log.info("code review: {} on {} holds {} file(s) at {}{} — {} service, {} controller, {} entity, "
				+ "{} skipped", repository.slug(), branch.branchName(), totalFiles, tree.head().sha(),
				tree.truncated() ? " (listing truncated by GitHub)" : "", serviceCount, controllerCount,
				entityCount, skippedCount);

		// 5. Fetch each classified file at the pinned head SHA and run the deterministic PMD rule set over it.
		//    No model is involved, so this stage runs whether or not inference is enabled.
		var findings = analyseClassifiedFiles(repository, tree.head().sha(), files);
		log.info("code review: PMD found {} finding(s) across the reviewed files of {} on {}", findings.size(),
				repository.slug(), branch.branchName());

		var result = new CodeReviewBranchFilesResult(repository.slug(), branch.branchName(),
				tree.head().sha(), tree.truncated(), totalFiles, findings);
		// Persist the completed review as a flat jsonb row. Done after all GitHub/analysis work, in its own
		// short transaction; a persistence failure is logged but never fails the response that already holds
		// the result.
		recordBranchResult(repository.id(), branch.id(), startedAt, BranchReviewStatus.COMPLETED, result);

		wrap.insertNewData(result);
		wrap.setStatus(true);
		return wrap;
	}

	/**
	 * Persists one whole-branch outcome, swallowing any persistence failure. The computed result has already
	 * been assembled and is about to be returned to the caller, so a database problem here must be logged and
	 * not raised — losing the row is worse than failing the request would be worth. {@code completedAt} is
	 * taken here so it reflects the moment the review actually finished.
	 */
	private void recordBranchResult(Long repositoryId, Long branchId, Instant startedAt,
			BranchReviewStatus status, CodeReviewBranchFilesResult result) {
		try {
			persistence.saveBranchResult(repositoryId, branchId, startedAt, Instant.now(), status, result);
		} catch (RuntimeException e) {
			log.warn("code review: could not persist branch result for repository {} branch {}", repositoryId,
					branchId, e);
		}
	}

	/**
	 * Runs the PMD rule set over every classified file and collects the violations.
	 * <p>
	 * Only a file that resolved to a role is analysed — the same selection the review pipeline covers; a
	 * skipped file ({@code NOT_JAVA}, {@code NOT_A_TARGET_ROLE}) is never fetched. Content is read at the
	 * pinned head SHA, so a finding's line numbers index the snapshot that was listed rather than a branch
	 * that may have moved.
	 * <p>
	 * Each file is fetched and analysed on its own: one that cannot be read, is missing at the commit, or is
	 * too large is logged and skipped rather than failing the whole listing — the same per-file isolation the
	 * review pipeline applies. {@link PmdService#analyse} is in-memory and deterministic, and every GitHub
	 * read here runs outside any transaction, as all of this class does.
	 */
	private List<BranchFinding> analyseClassifiedFiles(RepositoryRef repository, String headSha,
			List<ClassifiedBranchFile> files) {
		var findings = new ArrayList<BranchFinding>();
		for (ClassifiedBranchFile file : files) {
			if (file.role() == null) {
				continue;
			}
			var path = file.path();
			try {
				var content = githubApiClient.findFileContent(repository.owner(), repository.repoName(), headSha, path);
				if (content.isEmpty()) {
					log.warn("pmd: {} at {} could not be fetched, not analysed", path, headSha);
					continue;
				}
				if (content.get().oversized()) {
					log.info("pmd: {} is too large to analyse, skipped", path);
					continue;
				}
				for (PmdViolation violation : pmdService.analyse(file.role(), path, content.get().text())) {
					findings.add(new BranchFinding(path, violation.ruleName(), violation.code(), violation.message(),
							violation.beginLine(), violation.endLine(), violation.priority(), violation.evidence()));
				}
			} catch (RuntimeException e) {
				// One file's fetch or analysis failing must not abandon the rest of the branch.
				log.warn("pmd: analysing {} at {} failed, skipped", path, headSha, e);
			}
		}
		return findings;
	}

	// ------------------------------------------------------------------ one run

	/**
	 * What one file is being reviewed as: the role it resolved to, and the active guideline that role
	 * selected. {@code guideline} is null exactly when the role had no active version, or when no role was
	 * resolved at all.
	 */
	private record ReviewBasis(ReviewRoleType role, ActiveGuideline guideline) {

		/** True when there is a standard to review against, which is the only thing that makes a review possible. */
		boolean reviewable() {
			return guideline != null;
		}

		/** The id of the guideline to stamp on the row, or null when no version is active for the role. */
		Long guidelineId() {
			return guideline == null ? null : guideline.id();
		}
	}

	/**
	 * Resolves the basis for one file. A whole-branch run already settled the role from the path during
	 * classification; a commit run defers it to here, where the fetched content lets the annotation tier
	 * decide a file whose path matched no pattern.
	 */
	private ReviewBasis basisFor(ClassifiedFile file, String content, Map<ReviewRoleType, ActiveGuideline> guidelines) {
		var role = file.role() != null ? file.role() : roleResolver.roleOf(file.file().path(), content);
		return new ReviewBasis(role, role == null ? null : guidelines.get(role));
	}

	/**
	 * Claims the run row, then executes the pipeline. Any failure is recorded as {@code FAILED} on the
	 * run in its own committed transaction, so the row always explains what happened to it.
	 * <p>
	 * The pipeline is a parameter because the two strategies differ only in how they establish the file
	 * list, and everything around that — claiming, failure recording, the run id — is identical. Passing
	 * it in keeps one copy of the failure isolation rather than two that can drift apart.
	 * <p>
	 * The guideline library is read here, once per run, before anything is claimed or fetched. Both
	 * strategies need it and neither should read it per file, and reading it before the claim means an
	 * empty library is reported against the run that is about to be affected by it. It is carried as plain
	 * values, not entities, so nothing is managed across the untransactioned pipeline below.
	 */
	private Long executeRun(RepositoryRef repository, BranchRef branch, LocalDate reviewDate, String strategy,
			BiConsumer<ClaimedRun, Map<ReviewRoleType, ActiveGuideline>> pipeline) {
		var guidelines = guidelineService.loadActive();
		if (guidelines.isEmpty()) {
			log.warn("code review: the guideline library has no active version, so every file in the run for "
					+ "{} on {} will be skipped as {}", repository.slug(), branch.branchName(),
					ChangedFileClassifier.SKIP_NO_GUIDELINE);
		}

		var claimed = persistence.claimRun(repository, branch, reviewDate, strategy);
		if (claimed == null) {
			throw new IllegalStateException("Could not claim a review run for " + repository.slug()
					+ " on " + branch.branchName());
		}

		var runId = claimed.runId();
		log.info("code review: run {} started for {} on {} ({}, strategy {})", runId, repository.slug(),
				branch.branchName(), reviewDate, strategy);
		try {
			pipeline.accept(claimed, guidelines);
		} catch (RuntimeException e) {
			log.error("code review: run {} failed", runId, e);
			try {
				persistence.failRun(runId, e.getMessage());
			} catch (RuntimeException inner) {
				log.error("code review: run {} could not record FAILED", runId, inner);
			}
		}
		return runId;
	}

	/**
	 * The commit pipeline: select the day's commit, classify its diff, then hand the classified files to
	 * {@link #reviewClassifiedFiles}. Terminates early as {@code NO_COMMIT} when no commit reached the
	 * branch during the review window.
	 */
	private void runReview(ClaimedRun claimed, Map<ReviewRoleType, ActiveGuideline> guidelines, LocalDate reviewDate) {
		var selection = commitSelectionService.selectForDay(claimed.owner(), claimed.repoName(),
				claimed.branchName(), reviewDate);
		if (selection.isEmpty()) {
			persistence.recordNoCommit(claimed.runId());
			log.info("code review: run {} found no commit, recorded NO_COMMIT", claimed.runId());
			return;
		}

		var commit = selection.get().commit();
		var parentSha = selection.get().parentCommitSha();
		var detail = githubApiClient.getCommit(claimed.owner(), claimed.repoName(), commit.sha());
		var classified = changedFileClassifier.classify(detail.files());
		reviewClassifiedFiles(claimed, commit, parentSha, classified, guidelines, "changed", null);
	}

	/**
	 * The whole-branch pipeline. Same as {@link #runReview} with commit selection replaced by a tree
	 * listing, and with the differences that follow from covering the branch instead of one commit: no
	 * parent SHA to fall back to for a removed file, no patch to put in the context, and a file ceiling —
	 * applied by {@link ChangedFileClassifier#classifyBranchFiles}, which marks the overflow rather than
	 * dropping it.
	 * <p>
	 * Everything is read at the head SHA the tree listing pinned, so one run reviews one immutable snapshot
	 * even if the branch moves while the run is in flight.
	 */
	private void runBranchReview(ClaimedRun claimed, Map<ReviewRoleType, ActiveGuideline> guidelines) {
		var tree = githubApiClient.listBranchTree(claimed.owner(), claimed.repoName(), claimed.branchName());
		var classified = changedFileClassifier.classifyBranchFiles(tree.files(), guidelines.keySet());
		var note = tree.truncated()
				? " GitHub truncated the tree listing, so the branch holds more files than this run covered."
				: null;
		// Parent is null: with no diff there is no earlier snapshot, and findings validate against the NEW side.
		reviewClassifiedFiles(claimed, tree.head(), null, classified, guidelines, "holds", note);
	}

	/**
	 * The stage both pipelines share once the file list is in hand: record the commit, persist one row per
	 * file, review each eligible file, then finalize. Every remote call is inside {@link #reviewOneFile};
	 * each persistence step is its own short transaction on {@link CodeReviewRunPersistence}.
	 *
	 * @param verb reads into the "eligible file(s)" log line so each strategy describes itself — a commit
	 *             "changed" its files, a branch "holds" them
	 * @param note appended to the final summary, or null (see {@code CodeReviewRunPersistence.finalizeRun})
	 */
	private void reviewClassifiedFiles(ClaimedRun claimed, CommitInfo commit, String parentSha,
			List<ClassifiedFile> classified, Map<ReviewRoleType, ActiveGuideline> guidelines, String verb,
			String note) {
		var runId = claimed.runId();
		var commitSha = commit.sha();
		logFiles(runId, commitSha, verb, classified);

		// Written before the file rows so the resume check inside persistRunFiles can compare against it.
		persistence.recordCommit(runId, commit, parentSha);
		var persisted = persistence.persistRunFiles(runId, classified, commitSha);
		if (persisted == null) {
			throw new IllegalStateException("Could not persist the file list for run " + runId);
		}

		for (ClassifiedFile file : classified) {
			log.info("class: {}", file.file().path());
			var runFileId = persisted.pendingIdsByPath().get(file.file().path());
			if (runFileId == null) {
				continue;
			}
            log.info("runFileId: {}", runFileId);
			reviewOneFile(claimed, runId, runFileId, file, commitSha, parentSha, guidelines);
		}

		persistence.finalizeRun(runId, note);
	}

	/**
	 * Reviews one file. Fetching the snapshot, resolving the basis, building the context and calling the
	 * model all happen here, outside any transaction; the file's outcome is then written in a single short
	 * transaction on {@link CodeReviewRunPersistence} — a skip with its reason, the findings with
	 * {@code COMPLETED}, or {@code FAILED}. The row was already left {@code PROCESSING} by
	 * {@code persistRunFiles}, so no separate "started" commit is needed.
	 * <p>
	 * Catches {@code RuntimeException} broadly and on purpose: a file that cannot be read, is too large,
	 * or breaks the model is recorded as {@code FAILED} and the run continues. Propagating would abandon
	 * every remaining file because of one.
	 */
	private void reviewOneFile(ClaimedRun claimed, Long runId, Long runFileId, ClassifiedFile file,
			String commitSha, String parentSha, Map<ReviewRoleType, ActiveGuideline> guidelines) {
		var path = file.file().path();
		try {
			// A removed file has no content at the reviewed commit. Its parent snapshot is the only correct
			// reference, and the side recorded on findings must match the snapshot validated against, or
			// every line number would be checked against the wrong file.
			var removed = file.changeStatus() == FileChangeStatus.REMOVED;
			var ref = removed ? parentSha : commitSha;
			var side = removed ? DiffSide.OLD : DiffSide.NEW;

			var content = githubApiClient.findFileContent(claimed.owner(), claimed.repoName(), ref, path);
			if (content.isEmpty()) {
				persistence.skipFile(runFileId, null, null, SKIP_SOURCE_NOT_FOUND);
				return;
			}
			if (content.get().oversized()) {
				persistence.skipFile(runFileId, null, null, SKIP_FILE_TOO_LARGE);
				return;
			}
			var text = content.get().text();

			// Resolved here, not during classification, because the annotation tier reads the content, which
			// is now in hand — no extra GitHub call. A whole-branch run already settled the role from the
			// path; a commit run resolves both here. The role and guideline id are passed to whichever writer
			// runs below, so every outcome leaves the row saying what the file was reviewed as.
			var basis = basisFor(file, text, guidelines);

			if (basis.role() == null) {
				log.info("code review: {} plays none of the reviewed roles, skipped", path);
				persistence.skipFile(runFileId, null, null, ChangedFileClassifier.SKIP_NOT_TARGET_ROLE);
				return;
			}
			if (!basis.reviewable()) {
				log.info("code review: {} is a {} but no guideline version is active for that role, skipped", path,
						basis.role());
				persistence.skipFile(runFileId, basis.role(), basis.guidelineId(), ChangedFileClassifier.SKIP_NO_GUIDELINE);
				return;
			}
			if (!properties.getInference().isEnabled()) {
				persistence.skipFile(runFileId, basis.role(), basis.guidelineId(), SKIP_INFERENCE_DISABLED);
				return;
			}

			var context = reviewContextBuilder.build(path, basis.guideline().content(), file.file().patch(), text);
			var candidates = reviewAgent.review(
					new ReviewTarget(path, file.language(), file.changeStatus(), context));
			var lineCount = countLines(text);

			persistence.completeFile(runId, runFileId, path, side, lineCount, basis.role(), basis.guidelineId(),
					candidates);
		} catch (RuntimeException e) {
			log.error("code review: file {} in run {} failed", path, runId, e);
			try {
				persistence.failFile(runFileId, e.getMessage());
			} catch (RuntimeException inner) {
				log.error("code review: could not record FAILED for {} in run {}", path, runId, inner);
			}
		}
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Names the files a run will actually review, which is the one thing an operator watching the console
	 * needs: it is the selection, and it is what makes a run that found nothing distinguishable from a run
	 * that was never given anything.
	 * <p>
	 * Files that were skipped are deliberately not listed here. On a whole-branch run they are the majority
	 * — a repository's 466 files against the 130 that are services, controllers and entities — and naming
	 * each one with its reason buried the answer under the noise. The classifier already logs the counts
	 * per reason, and the response carries every file with its {@code skipReason}, so nothing is lost.
	 * <p>
	 * Paths only, never patch or source content, because the log is not a place to put source code.
	 *
	 * @param verb reads into the sentence, so the two strategies describe themselves accurately: a commit
	 *             "changed" its files, a branch "holds" its files
	 */
	private static void logFiles(Long runId, String commitSha, String verb, List<ClassifiedFile> classified) {
		var eligible = new ArrayList<String>();
		for (ClassifiedFile file : classified) {
			if (file.reviewable()) {
				eligible.add(file.file().path());
			}
		}

		if (eligible.isEmpty()) {
			log.info("code review: run {} at {} {} {} file(s), none eligible for review", runId, commitSha, verb,
					classified.size());
			return;
		}

		var listed = new StringBuilder();
		var shown = 0;
		for (String path : eligible) {
			if (shown == MAX_LOGGED_PATHS) {
				break;
			}
			if (shown > 0) {
				listed.append("; ");
			}
			listed.append(path);
			shown++;
		}
		if (eligible.size() > shown) {
			listed.append("; ... and ").append(eligible.size() - shown).append(" more");
		}
		log.info("code review: run {} at {} {} {} eligible file(s) of {}: {}", runId, commitSha, verb,
				eligible.size(), classified.size(), listed);
	}

	/**
	 * Whether a path is a Java source file, the one language a whole-branch review classifies. Matched on
	 * the extension alone and case-insensitively, so {@code Foo.JAVA} counts too.
	 */
	private static boolean isJavaFile(String path) {
		return path != null && path.toLowerCase(Locale.ROOT).endsWith(JAVA_EXTENSION);
	}

	/**
	 * Number of lines in a snapshot, used to check that a finding's line reference exists. A trailing
	 * newline terminates the last line rather than starting an empty one, so it is not counted.
	 */
	private static int countLines(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		var lines = 1;
		for (var i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				lines++;
			}
		}
		return text.endsWith("\n") ? lines - 1 : lines;
	}
}
