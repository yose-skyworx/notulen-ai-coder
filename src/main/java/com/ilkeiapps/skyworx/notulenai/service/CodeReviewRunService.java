package com.ilkeiapps.skyworx.notulenai.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViewSetting;
import com.ilkeiapps.ilkeitool.validator.IlKeiValidator;
import com.ilkeiapps.ilkeitool.validator.ProcessResult;
import com.ilkeiapps.ilkeitoolentityblazebit.validator.IlKeiEntityValidator;
import com.ilkeiapps.skyworx.notulenai.bean.enums.DiffSide;
import com.ilkeiapps.skyworx.notulenai.bean.enums.FileChangeStatus;
import com.ilkeiapps.skyworx.notulenai.bean.enums.FindingValidationStatus;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewRoleType;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewSeverity;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewBranchTriggerRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewProjectTriggerRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewRunResult;
import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;
import com.ilkeiapps.skyworx.notulenai.entity.CodeRepository;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewBranch;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewFinding;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewGuideline;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewRun;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewRunFile;
import com.ilkeiapps.skyworx.notulenai.entity.Project;
import com.ilkeiapps.skyworx.notulenai.entity.QCodeRepository;
import com.ilkeiapps.skyworx.notulenai.entity.QCodeReviewBranch;
import com.ilkeiapps.skyworx.notulenai.entity.QCodeReviewRun;
import com.ilkeiapps.skyworx.notulenai.entity.QCodeReviewRunFile;
import com.ilkeiapps.skyworx.notulenai.entity.StatusProcess;
import com.ilkeiapps.skyworx.notulenai.entityview.codereview.CodeReviewRunDetailView;
import com.ilkeiapps.skyworx.notulenai.entityview.codereview.CodeReviewRunFileListView;
import com.ilkeiapps.skyworx.notulenai.service.ChangedFileClassifier.ClassifiedFile;
import com.ilkeiapps.skyworx.notulenai.service.CodeReviewGuidelineService.ActiveGuideline;
import com.ilkeiapps.skyworx.notulenai.service.FindingValidator.CandidateFinding;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.CommitInfo;
import com.ilkeiapps.skyworx.notulenai.service.ReviewAgent.ReviewTarget;
import com.ilkeiapps.skyworx.notulenai.utility.Utility;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
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
 * application to GitHub's latency, so the orchestrator method runs untransactioned and each persistence
 * step commits on its own via {@link TransactionTemplate} — the same shape as
 * {@code KnowledgeProcessingService}. A late failure therefore cannot roll back the files already
 * reviewed, and the terminal status plus error message are always written in their own committed
 * transaction so the cause survives.
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

	private static final String STATUS_PROCESSING = "PROCESSING";
	private static final String STATUS_COMPLETED = "COMPLETED";
	private static final String STATUS_COMPLETED_WITH_WARNING = "COMPLETED_WITH_WARNING";
	private static final String STATUS_FAILED = "FAILED";
	private static final String STATUS_SKIPPED = "SKIPPED";
	private static final String STATUS_NO_COMMIT = "NO_COMMIT";

	/**
	 * Skip reasons recorded on {@code code_review_run_files.skip_reason}. The other two —
	 * {@code ChangedFileClassifier.SKIP_NOT_TARGET_ROLE} and {@code SKIP_NO_GUIDELINE} — are emitted during
	 * classification; these three can only be discovered once a file's content is in hand.
	 * <p>
	 * There is deliberately no run-level status for a missing guideline. Guidelines are library rows keyed
	 * by role, so a gap is per-file, not per-run: a library missing only the entity standard still reviews
	 * every service and controller normally, and those files are skipped with a reason while the run ends
	 * {@code COMPLETED_WITH_WARNING} through the ordinary "nothing was analyzed" path in
	 * {@link #finalizeRun}. A terminal state would have claimed the whole run failed when part of it
	 * succeeded.
	 */
	private static final String SKIP_SOURCE_NOT_FOUND = "SOURCE_NOT_FOUND";
	private static final String SKIP_FILE_TOO_LARGE = "FILE_TOO_LARGE";
	private static final String SKIP_INFERENCE_DISABLED = "INFERENCE_DISABLED";

	/** Column limits, applied here so a long GitHub value cannot fail an insert. */
	private static final int MAX_ERROR_LENGTH = 4000;
	private static final int MAX_PATH_LENGTH = 1024;
	/**
	 * Limit shared by the {@code varchar(512)} columns written here: {@code skip_reason},
	 * {@code guideline_reference}, and {@code validation_detail}. Truncating beats failing an insert over a
	 * long path or a verbose reason.
	 */
	private static final int MAX_DETAIL_LENGTH = 512;

	/** Used when a finding is rejected for having no severity: the column is {@code NOT NULL}. */
	private static final ReviewSeverity FALLBACK_SEVERITY = ReviewSeverity.INFO;

	/**
	 * Ceiling on how many paths one "eligible file(s)" log line names. Set to the default
	 * {@code branch-review.max-files} so a whole-branch run lists everything it is about to review rather
	 * than trailing off into "... and N more" — the point of the line is to see the selection.
	 */
	private static final int MAX_LOGGED_PATHS = 200;

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;
	private final EntityViewManager viewManager;
	private final StatusService statusService;
	private final CodeReviewProperties properties;
	private final PlatformTransactionManager transactionManager;
	private final GithubApiClient githubApiClient;
	private final CommitSelectionService commitSelectionService;
	private final ChangedFileClassifier changedFileClassifier;
	private final ReviewRoleResolver roleResolver;
	private final CodeReviewGuidelineService guidelineService;
	private final ReviewContextBuilder reviewContextBuilder;
	private final FindingValidator findingValidator;
	private final ReviewAgent reviewAgent;

	private TransactionTemplate tx;

	@PostConstruct
	void init() {
		this.tx = new TransactionTemplate(transactionManager);
	}

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

		if (!projectIsActive(src.getProjectId())) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.project_not_found"));
			return wrap;
		}

		var token = properties.getGithub().getToken();
		if (token == null || token.isBlank()) {
			wrap.setMessage(Utility.getErrorMessage("codereview.github.token_missing"));
			return wrap;
		}

		var repositories = findEnabledRepositories(src.getProjectId());
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
			var branches = findEnabledBranches(repository.id());
			if (branches.isEmpty()) {
				missingBranches = true;
				log.warn("code review: repository {} has no enabled branch, nothing to review", repository.slug());
				continue;
			}

			for (BranchRef branch : branches) {
				try {
					var runId = executeRun(repository, branch, reviewDate, STRATEGY_MANUAL,
							(claimed, guidelines) -> runReview(claimed, guidelines, reviewDate));
					var result = loadRunResult(runId);
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
	 * Reviews one branch in full: every file it holds at its current head, rather than the diff of one
	 * commit that reached it on one day. Blocks until the run finishes and returns the run with its
	 * per-file breakdown.
	 * <p>
	 * Use it when the question is "what is wrong with this branch" rather than "what changed yesterday" —
	 * a first review of a repository that was never covered, or a re-review after the guidelines changed.
	 * It is the more expensive of the two by a wide margin, so {@code code-review.branch-review.max-files}
	 * bounds how many files it examines and marks the remainder {@code BRANCH_FILE_LIMIT} instead of
	 * dropping them.
	 * <p>
	 * Both ids are mandatory and the branch must belong to the repository. A branch <em>name</em> was
	 * deliberately not accepted: {@code code_review_runs.branch_id} is {@code NOT NULL} and part of the run
	 * identity, so a name would have to be resolved to a registered row anyway, and silently registering
	 * one on the caller's behalf would create review configuration nobody chose.
	 * <p>
	 * {@code enabled} is not required on either row. That flag governs inclusion in the automatic fan-out;
	 * an explicit request naming a branch is a deliberate act and should not be refused by a scheduling
	 * switch.
	 */
	public ApiResponse<CodeReviewRunResult> triggerBranch(CodeReviewBranchTriggerRequest src) {
		var wrap = new ApiResponse<CodeReviewRunResult>();
		wrap.setStatus(false);

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

		var repository = tx.execute(status -> findRepositoryRef(src.getRepositoryId()));
		if (repository == null) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.item_not_found"));
			return wrap;
		}
		var branch = tx.execute(status -> findBranchRef(repository.id(), src.getBranchId()));
		if (branch == null) {
			wrap.setMessage(Utility.getErrorMessage("codereview.branch.not_in_repository"));
			return wrap;
		}

		// review_date is NOT NULL and part of uq_crr_identity, but a whole-branch review covers no
		// particular day. It records the day the run was requested, which makes re-triggering on the same
		// day a resume rather than a duplicate and gives the next day a fresh run against a fresh head.
		var runDate = LocalDate.now(commitSelectionService.reviewZone());
		log.info("code review: whole-branch review triggered for {} on {}", repository.slug(), branch.branchName());

		try {
			var runId = executeRun(repository, branch, runDate, STRATEGY_BRANCH_FULL, this::runBranchReview);
			var result = loadRunResult(runId);
			if (result == null) {
				wrap.setMessage(Utility.getErrorMessage("codereview.run.item_not_found"));
				return wrap;
			}
			wrap.insertNewData(result);
			wrap.setStatus(true);
		} catch (RuntimeException e) {
			// executeRun already records failures on the run row, so reaching here means the row itself
			// could not be claimed — there is no run to report on.
			log.error("code review: could not start the whole-branch review for {} on {}", repository.slug(),
					branch.branchName(), e);
			wrap.setMessage(Utility.getErrorMessage("codereview.run.claim_failed"));
		}
		return wrap;
	}

	// ------------------------------------------------------------------ one run

	/** A registered repository, reduced to what the non-transactional stages need. */
	private record RepositoryRef(Long id, String owner, String repoName) {
		String slug() {
			return owner + "/" + repoName;
		}
	}

	/** A branch selected for review under a repository. */
	private record BranchRef(Long id, String branchName) {
	}

	/** A claimed run row plus the GitHub coordinates captured inside the claiming transaction. */
	private record ClaimedRun(Long runId, String owner, String repoName, String branchName) {
	}

	/** Outcome of persisting a commit's file list: the counters and the files still to review. */
	private record PersistedFiles(int totalFiles, int skippedFiles, Map<String, Long> pendingIdsByPath) {
	}

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
	 * empty library is reported against the run that is about to be affected by it.
	 */
	private Long executeRun(RepositoryRef repository, BranchRef branch, LocalDate reviewDate, String strategy,
			BiConsumer<ClaimedRun, Map<ReviewRoleType, ActiveGuideline>> pipeline) {
		// Carried as plain values, not entities: the pipeline below runs untransactioned by design, so
		// managed objects would either detach mid-flight or hold a persistence context open across every
		// GitHub call. loadActive() runs in its own read-only transaction and closes it.
		var guidelines = guidelineService.loadActive();
		if (guidelines.isEmpty()) {
			log.warn("code review: the guideline library has no active version, so every file in the run for "
					+ "{} on {} will be skipped as {}", repository.slug(), branch.branchName(),
					ChangedFileClassifier.SKIP_NO_GUIDELINE);
		}

		var claimed = tx.execute(status -> claimRun(repository, branch, reviewDate, strategy));
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
				tx.executeWithoutResult(status -> failRun(runId, e.getMessage()));
			} catch (RuntimeException inner) {
				log.error("code review: run {} could not record FAILED", runId, inner);
			}
		}
		return runId;
	}

	/**
	 * The pipeline itself. Remote calls run untransactioned; every persistence step is its own short
	 * transaction. One branch terminates early with its own status rather than falling through to
	 * {@code COMPLETED}: no commit reached the branch during the review window.
	 */
	private void runReview(ClaimedRun claimed, Map<ReviewRoleType, ActiveGuideline> guidelines, LocalDate reviewDate) {
		var runId = claimed.runId();

		var selection = commitSelectionService.selectForDay(claimed.owner(), claimed.repoName(),
				claimed.branchName(), reviewDate);
		if (selection.isEmpty()) {
			tx.executeWithoutResult(status -> recordNoCommit(runId));
			log.info("code review: run {} found no commit, recorded NO_COMMIT", runId);
			return;
		}

		CommitInfo commit = selection.get().commit();
		var parentSha = selection.get().parentCommitSha();
		var detail = githubApiClient.getCommit(claimed.owner(), claimed.repoName(), commit.sha());
		var classified = changedFileClassifier.classify(detail.files());
		logFiles(runId, commit.sha(), "changed", classified);

		// Written before the file rows so the resume check inside persistRunFiles can compare against it.
		tx.executeWithoutResult(status -> recordCommit(runId, commit, parentSha));
		var persisted = tx.execute(status -> persistRunFiles(runId, classified, commit.sha()));
		if (persisted == null) {
			throw new IllegalStateException("Could not persist the file list for run " + runId);
		}

		for (ClassifiedFile file : classified) {
			var runFileId = persisted.pendingIdsByPath().get(file.file().path());
			if (runFileId == null) {
				continue;
			}
			reviewOneFile(claimed, runId, runFileId, file, commit.sha(), parentSha, guidelines);
		}

		tx.executeWithoutResult(status -> finalizeRun(runId, null));
	}

	/**
	 * The whole-branch pipeline. Same shape as {@link #runReview} with commit selection replaced by a tree
	 * listing, and with three differences that follow from covering the branch instead of one commit: no
	 * parent SHA to fall back to for a removed file, no patch to put in the context, and a ceiling on how
	 * many files are examined — applied by {@link ChangedFileClassifier#classifyBranchFiles}, which marks
	 * the overflow rather than dropping it.
	 * <p>
	 * Everything is read at the head SHA the tree listing pinned, so one run reviews one immutable snapshot
	 * even if the branch moves while the run is in flight.
	 */
	private void runBranchReview(ClaimedRun claimed, Map<ReviewRoleType, ActiveGuideline> guidelines) {
		var runId = claimed.runId();

		var tree = githubApiClient.listBranchTree(claimed.owner(), claimed.repoName(), claimed.branchName());
		var headSha = tree.head().sha();
		var classified = changedFileClassifier.classifyBranchFiles(tree.files(), guidelines.keySet());
		logFiles(runId, headSha, "holds", classified);

		// Written before the file rows so the resume check inside persistRunFiles can compare against it.
		// The parent is null: with no diff there is no earlier snapshot to compare against, and findings
		// are validated against the NEW side where this content lives.
		tx.executeWithoutResult(status -> recordCommit(runId, tree.head(), null));
		var persisted = tx.execute(status -> persistRunFiles(runId, classified, headSha));
		if (persisted == null) {
			throw new IllegalStateException("Could not persist the file list for run " + runId);
		}

		for (ClassifiedFile file : classified) {
			var runFileId = persisted.pendingIdsByPath().get(file.file().path());
			if (runFileId == null) {
				continue;
			}
			reviewOneFile(claimed, runId, runFileId, file, headSha, null, guidelines);
		}

		var note = tree.truncated()
				? " GitHub truncated the tree listing, so the branch holds more files than this run covered."
				: null;
		tx.executeWithoutResult(status -> finalizeRun(runId, note));
	}

	/**
	 * Reviews one file. Remote work — fetching the snapshot, building the context, calling the model —
	 * happens outside any transaction; only the row updates commit.
	 * <p>
	 * Catches {@code RuntimeException} broadly and on purpose: a file that cannot be read, is too large,
	 * or breaks the model must be recorded as {@code FAILED} and the run must continue. Propagating would
	 * abandon every remaining file because of one.
	 */
	private void reviewOneFile(ClaimedRun claimed, Long runId, Long runFileId, ClassifiedFile file,
			String commitSha, String parentSha, Map<ReviewRoleType, ActiveGuideline> guidelines) {
		var path = file.file().path();
		tx.executeWithoutResult(status -> markFileProcessing(runFileId));

		try {
			// A removed file has no content at the reviewed commit. Its parent snapshot is the only
			// correct reference, and the side recorded on findings must match the snapshot validated
			// against, or every line number would be checked against the wrong file.
			var removed = file.changeStatus() == FileChangeStatus.REMOVED;
			var ref = removed ? parentSha : commitSha;
			var side = removed ? DiffSide.OLD : DiffSide.NEW;

			var content = githubApiClient.findFileContent(claimed.owner(), claimed.repoName(), ref, path);
			if (content.isEmpty()) {
				tx.executeWithoutResult(status -> skipFile(runFileId, SKIP_SOURCE_NOT_FOUND));
				return;
			}
			if (content.get().oversized()) {
				tx.executeWithoutResult(status -> skipFile(runFileId, SKIP_FILE_TOO_LARGE));
				return;
			}
			var text = content.get().text();

			// Settled here rather than during classification because the annotation tier reads the content,
			// and this is where the content is in hand — no extra GitHub call. A whole-branch run already
			// resolved the role from the path during classification, so for it this only looks the guideline
			// up; a commit run resolves both here. Both strategies end up recording the same reasons.
			//
			// Ahead of the context build so a rejected file costs nothing further, and ahead of the
			// inference check so a file with no standard says that rather than the blunter
			// INFERENCE_DISABLED.
			var basis = basisFor(file, text, guidelines);
			tx.executeWithoutResult(status -> recordBasis(runFileId, basis));

			if (basis.role() == null) {
				log.info("code review: {} plays none of the reviewed roles, skipped", path);
				tx.executeWithoutResult(status -> skipFile(runFileId, ChangedFileClassifier.SKIP_NOT_TARGET_ROLE));
				return;
			}
			if (!basis.reviewable()) {
				log.info("code review: {} is a {} but no guideline version is active for that role, skipped", path,
						basis.role());
				tx.executeWithoutResult(status -> skipFile(runFileId, ChangedFileClassifier.SKIP_NO_GUIDELINE));
				return;
			}

			var context = reviewContextBuilder.build(path, basis.guideline().content(), file.file().patch(), text);

			if (!properties.getInference().isEnabled()) {
				tx.executeWithoutResult(status -> skipFile(runFileId, SKIP_INFERENCE_DISABLED));
				return;
			}

			var candidates = reviewAgent.review(
					new ReviewTarget(path, file.language(), file.changeStatus(), context));
			var lineCount = countLines(text);

			tx.executeWithoutResult(status -> persistFindings(runId, runFileId, path, side, lineCount, candidates));
		} catch (RuntimeException e) {
			log.error("code review: file {} in run {} failed", path, runId, e);
			try {
				tx.executeWithoutResult(status -> failFile(runFileId, e.getMessage()));
			} catch (RuntimeException inner) {
				log.error("code review: could not record FAILED for {} in run {}", path, runId, inner);
			}
		}
	}

	// ------------------------------------------------------------------ transactional steps

	/**
	 * Finds the run for this identity or creates it. Relies on {@code uq_crr_identity} rather than a
	 * check-then-insert across transactions: two simultaneous triggers for the same repository, branch,
	 * day and strategy cannot both insert, and the loser surfaces as a failure on its own response rather
	 * than as a duplicate run. The scheduler is unaffected because it writes {@code DAILY}.
	 */
	private ClaimedRun claimRun(RepositoryRef repository, BranchRef branch, LocalDate reviewDate, String strategy) {
		var now = Instant.now();
		var existing = findRunId(repository.id(), branch.id(), reviewDate, strategy);

		if (existing != null) {
			var run = this.em.find(CodeReviewRun.class, existing);
			if (run != null) {
				run.setStatus(processStatus(STATUS_PROCESSING));
				run.setStartedAt(now);
				run.setCompletedAt(null);
				run.setErrorMessage(null);
				run.setUpdatedAt(now);
				this.em.flush();
				log.info("code review: resuming run {} for {} on {}", existing, repository.slug(), branch.branchName());
				return new ClaimedRun(existing, repository.owner(), repository.repoName(), branch.branchName());
			}
		}

		var repositoryEntity = this.em.find(CodeRepository.class, repository.id());
		if (repositoryEntity == null) {
			throw new IllegalStateException("Repository " + repository.id() + " no longer exists");
		}

		var run = new CodeReviewRun();
		// Taken from the repository rather than passed in, so a run can never be attributed to a project
		// other than the one that owns the repository it reviewed.
		run.setProject(repositoryEntity.getProject());
		run.setRepository(repositoryEntity);
		run.setBranch(this.em.find(CodeReviewBranch.class, branch.id()));
		run.setReviewDate(reviewDate);
		run.setStrategy(strategy);
		run.setStatus(processStatus(STATUS_PROCESSING));
		run.setStartedAt(now);
		run.setTotalFiles(0);
		run.setAnalyzedFiles(0);
		run.setSkippedFiles(0);
		run.setTotalFindings(0);
		run.setCreatedAt(now);
		run.setUpdatedAt(now);
		this.em.persist(run);
		this.em.flush();
		return new ClaimedRun(run.getId(), repository.owner(), repository.repoName(), branch.branchName());
	}

	private void recordCommit(Long runId, CommitInfo commit, String parentSha) {
		var run = this.em.find(CodeReviewRun.class, runId);
		if (run == null) {
			return;
		}
		run.setSelectedCommitSha(commit.sha());
		run.setParentCommitSha(parentSha);
		run.setCommitMessage(truncate(commit.message(), 2048));
		run.setCommitter(truncate(commit.committerName(), 255));
		run.setCommitDate(commit.committerDate());
		run.setUpdatedAt(Instant.now());
		this.em.flush();
	}

	/**
	 * Upserts one {@code code_review_run_files} row per file the run covers. Skipped files are written with
	 * a reason rather than omitted, so {@code total_files} always equals the real file count.
	 * <p>
	 * A row already {@code COMPLETED} against the same content hash and the same commit is left untouched —
	 * including its findings — which is what makes re-triggering the same day cheap instead of duplicating
	 * every finding. For a commit-based run that hash is of the diff; for a whole-branch run it is the git
	 * blob SHA, which changes exactly when the file's content does.
	 */
	private PersistedFiles persistRunFiles(Long runId, List<ClassifiedFile> classified, String commitSha) {
		var run = this.em.find(CodeReviewRun.class, runId);
		if (run == null) {
			return new PersistedFiles(0, 0, Map.of());
		}

		var existingByPath = loadRunFilesByPath(runId);
		var now = Instant.now();
		var pendingRows = new ArrayList<CodeReviewRunFile>();
		var skipped = 0;

		for (ClassifiedFile file : classified) {
			var path = truncate(file.file().path(), MAX_PATH_LENGTH);
			var hash = file.contentHash();
			var row = existingByPath.get(path);
			var isNew = row == null;

			if (isNew) {
				row = new CodeReviewRunFile();
				row.setRun(run);
				row.setFilePath(path);
				row.setCreatedAt(now);
			} else if (STATUS_COMPLETED.equals(statusCode(row))
					&& hash.equals(row.getDiffHash())
					&& commitSha.equals(run.getSelectedCommitSha())) {
				// Already reviewed against this exact diff. Touching nothing keeps its findings valid.
				continue;
			}

			row.setOldFilePath(truncate(file.file().previousFilename(), MAX_PATH_LENGTH));
			row.setChangeStatus(file.changeStatus());
			row.setLanguage(file.language());
			// Only a whole-branch run knows the role this early, from the path. A commit run leaves it null
			// here and recordBasis fills it in once the content has settled it; skipped rows keep whatever
			// classification decided, which is why this is written unconditionally rather than merged.
			row.setRoleType(file.role());
			row.setAdditions(file.file().additions());
			row.setDeletions(file.file().deletions());
			row.setDiffHash(hash);
			row.setUpdatedAt(now);
			row.setStartedAt(null);
			row.setCompletedAt(null);
			row.setErrorMessage(null);

			if (file.reviewable()) {
				row.setStatus(processStatus(STATUS_PROCESSING));
				row.setSkipReason(null);
				pendingRows.add(row);
			} else {
				row.setStatus(processStatus(STATUS_SKIPPED));
				row.setSkipReason(truncate(file.skipReason(), MAX_DETAIL_LENGTH));
				row.setCompletedAt(now);
				skipped++;
			}

			if (isNew) {
				this.em.persist(row);
			}
		}

		this.em.flush();

		// Ids are only assigned by the flush above, so the pending map is built after it.
		var pendingIdsByPath = new LinkedHashMap<String, Long>();
		for (CodeReviewRunFile row : pendingRows) {
			pendingIdsByPath.put(row.getFilePath(), row.getId());
		}

		run.setTotalFiles(classified.size());
		run.setSkippedFiles(skipped);
		run.setUpdatedAt(now);
		this.em.flush();

		return new PersistedFiles(classified.size(), skipped, pendingIdsByPath);
	}

	/**
	 * Writes the role and the guideline version onto the file's row as soon as both are known, before the
	 * review itself runs. Recording it here rather than at each terminal write means every outcome — skip,
	 * failure, findings, or a crash between them — leaves the row saying what it was being reviewed as, and
	 * the three writers below stay free of parameters they would only pass through.
	 * <p>
	 * Both columns are only ever set, never cleared: a resume re-examines a file whose row already carries
	 * the previous basis, and nulling it on the way would lose the record if the re-examination then failed
	 * before reaching a terminal write.
	 */
	private void recordBasis(Long runFileId, ReviewBasis basis) {
		var row = this.em.find(CodeReviewRunFile.class, runFileId);
		if (row == null) {
			return;
		}
		if (basis.role() != null) {
			row.setRoleType(basis.role());
		}
		if (basis.guideline() != null) {
			// A reference, not a find: the guideline was read moments ago by loadActive and only its id is
			// needed here, so there is no reason to pull its text back through the session.
			row.setGuideline(this.em.getReference(CodeReviewGuideline.class, basis.guideline().id()));
		}
		row.setUpdatedAt(Instant.now());
		this.em.flush();
	}

	private void recordNoCommit(Long runId) {
		var run = this.em.find(CodeReviewRun.class, runId);
		if (run == null) {
			return;
		}
		var now = Instant.now();
		run.setStatus(processStatus(STATUS_NO_COMMIT));
		run.setCompletedAt(now);
		run.setDurationMs(durationSince(run.getStartedAt(), now));
		run.setTotalFiles(0);
		run.setAnalyzedFiles(0);
		run.setSkippedFiles(0);
		run.setTotalFindings(0);
		run.setSummary("No commit reached this branch during the review day.");
		run.setErrorMessage(null);
		run.setUpdatedAt(now);
		this.em.flush();
	}

	private void markFileProcessing(Long runFileId) {
		var row = this.em.find(CodeReviewRunFile.class, runFileId);
		if (row == null) {
			return;
		}
		var now = Instant.now();
		row.setStatus(processStatus(STATUS_PROCESSING));
		row.setStartedAt(now);
		row.setSkipReason(null);
		row.setErrorMessage(null);
		row.setUpdatedAt(now);
		this.em.flush();
	}

	private void skipFile(Long runFileId, String reason) {
		var row = this.em.find(CodeReviewRunFile.class, runFileId);
		if (row == null) {
			return;
		}
		var now = Instant.now();
		row.setStatus(processStatus(STATUS_SKIPPED));
		row.setSkipReason(truncate(reason, MAX_DETAIL_LENGTH));
		row.setStartedAt(null);
		row.setCompletedAt(now);
		row.setUpdatedAt(now);
		this.em.flush();
	}

	private void failFile(Long runFileId, String error) {
		var row = this.em.find(CodeReviewRunFile.class, runFileId);
		if (row == null) {
			return;
		}
		var now = Instant.now();
		row.setStatus(processStatus(STATUS_FAILED));
		row.setErrorMessage(truncate(error, MAX_ERROR_LENGTH));
		row.setCompletedAt(now);
		row.setUpdatedAt(now);
		this.em.flush();
	}

	/**
	 * Persists every candidate finding, valid or not, then closes the file as {@code COMPLETED}.
	 * <p>
	 * Rejected findings are kept because discarding them would make the pipeline look more accurate than
	 * it is; the {@code validation_status} and {@code validation_detail} columns are what make model
	 * reliability measurable. Only {@code VALID} rows are counted in {@code code_review_runs
	 * .total_findings} — a headline count that included rejected claims would misrepresent the review.
	 */
	private void persistFindings(Long runId, Long runFileId, String path, DiffSide side, int lineCount,
			List<CandidateFinding> candidates) {
		var run = this.em.find(CodeReviewRun.class, runId);
		var runFile = this.em.find(CodeReviewRunFile.class, runFileId);
		if (run == null || runFile == null) {
			return;
		}

		var now = Instant.now();
		var rejected = 0;

		if (candidates != null) {
			for (CandidateFinding candidate : candidates) {
				var verdict = findingValidator.validate(candidate, path, side, lineCount);
				if (!verdict.valid()) {
					rejected++;
				}
				this.em.persist(toEntity(run, runFile, path, side, candidate, verdict.status(), verdict.detail(), now));
			}
		}

		runFile.setStatus(processStatus(STATUS_COMPLETED));
		runFile.setStartedAt(runFile.getStartedAt() == null ? now : runFile.getStartedAt());
		runFile.setCompletedAt(now);
		runFile.setErrorMessage(null);
		runFile.setUpdatedAt(now);
		this.em.flush();

		var produced = candidates == null ? 0 : candidates.size();
		log.info("code review: {} produced {} finding(s), {} rejected by validation", path, produced, rejected);
	}

	/**
	 * Copies a candidate onto an entity. Several columns are {@code NOT NULL} but a rejected finding may
	 * be missing exactly the value that got it rejected, so those fall back to a documented default —
	 * the rejection detail carries the truth, and the row still has to be storable.
	 */
	private CodeReviewFinding toEntity(CodeReviewRun run, CodeReviewRunFile runFile, String path, DiffSide side,
			CandidateFinding candidate, FindingValidationStatus validationStatus, String validationDetail,
			Instant now) {
		var finding = new CodeReviewFinding();
		finding.setProject(run.getProject());
		finding.setRun(run);
		finding.setRunFile(runFile);
		finding.setFilePath(truncate(path, MAX_PATH_LENGTH));
		finding.setLineNumber(candidate.lineNumber());
		finding.setEndLineNumber(candidate.endLineNumber());
		finding.setSide(candidate.side() == null ? side : candidate.side());
		finding.setRuleId(truncate(candidate.ruleId(), 64));
		finding.setSeverity(candidate.severity() == null ? FALLBACK_SEVERITY : candidate.severity());
		finding.setCategory(truncate(candidate.category(), 32));
		finding.setTitle(truncate(candidate.title() == null || candidate.title().isBlank()
				? "Untitled finding"
				: candidate.title(), 255));
		finding.setDescription(candidate.description());
		finding.setEvidence(candidate.evidence());
		finding.setGuidelineReference(truncate(candidate.guidelineReference(), MAX_DETAIL_LENGTH));
		finding.setRecommendation(candidate.recommendation());
		// Stored only when in range: numeric(3,2) cannot hold a value such as 12.5, and the out-of-range
		// number is already quoted in the validation detail.
		var confidence = candidate.confidence();
		finding.setConfidence(confidence != null
				&& confidence.compareTo(BigDecimal.ZERO) >= 0
				&& confidence.compareTo(BigDecimal.ONE) <= 0 ? confidence : null);
		finding.setValidationStatus(validationStatus);
		finding.setValidationDetail(truncate(validationDetail, MAX_DETAIL_LENGTH));
		finding.setCreatedAt(now);
		return finding;
	}

	/**
	 * Closes the run with its aggregate counters and a terminal status.
	 * <p>
	 * {@code COMPLETED_WITH_WARNING} covers both "some files failed" and "no file was actually
	 * analyzed". The second case matters: with inference disabled every file is skipped, and that run
	 * must not look identical to one that reviewed everything and found nothing.
	 *
	 * @param note appended to the summary, or null. Carries a caveat the counters cannot express — a
	 *             truncated tree listing means the run covered fewer files than the branch holds.
	 */
	private void finalizeRun(Long runId, String note) {
		var run = this.em.find(CodeReviewRun.class, runId);
		if (run == null) {
			return;
		}

		var total = (int) countFiles(runId);
		var analyzed = (int) countFilesWithStatus(runId, STATUS_COMPLETED);
		var skipped = (int) countFilesWithStatus(runId, STATUS_SKIPPED);
		var failed = (int) countFilesWithStatus(runId, STATUS_FAILED);
		var findings = (int) countValidFindings(runId);

		var code = failed > 0 || (total > 0 && analyzed == 0) ? STATUS_COMPLETED_WITH_WARNING : STATUS_COMPLETED;
		var now = Instant.now();

		run.setStatus(processStatus(code));
		run.setTotalFiles(total);
		run.setAnalyzedFiles(analyzed);
		run.setSkippedFiles(skipped);
		run.setTotalFindings(findings);
		run.setCompletedAt(now);
		run.setDurationMs(durationSince(run.getStartedAt(), now));
		// Worded for both strategies: the scope is already on the row as strategy and selected_commit_sha,
		// and a summary that claimed "in the commit" would be wrong for a whole-branch run.
		run.setSummary(total + " file(s) covered: " + analyzed + " analyzed, " + skipped + " skipped, "
				+ failed + " failed; " + findings + " valid finding(s)." + (note == null ? "" : note));
		run.setErrorMessage(null);
		run.setUpdatedAt(now);
		this.em.flush();

		log.info("code review: run {} finished as {} ({} files, {} findings)", runId, code, total, findings);
	}

	private void failRun(Long runId, String error) {
		var run = this.em.find(CodeReviewRun.class, runId);
		if (run == null) {
			return;
		}
		var now = Instant.now();
		run.setStatus(processStatus(STATUS_FAILED));
		run.setErrorMessage(truncate(error, MAX_ERROR_LENGTH));
		run.setCompletedAt(now);
		run.setDurationMs(durationSince(run.getStartedAt(), now));
		run.setUpdatedAt(now);
		this.em.flush();
	}

	// ------------------------------------------------------------------ reads

	/**
	 * Reads a finished run and its files together. Null only when the run row itself is gone; the file list
	 * is legitimately empty for a run that ended before files were persisted, such as {@code NO_COMMIT} or
	 * a failed commit fetch.
	 */
	private CodeReviewRunResult loadRunResult(Long runId) {
		return tx.execute(status -> {
			var qr = new QCodeReviewRun("r");
			var runQuery = configBuilder.create(em, CodeReviewRun.class)
					.from(CodeReviewRun.class, qr.getMetadata().getName())
					.where(qr.id.toString()).eq(runId);
			var runs = viewManager.applySetting(EntityViewSetting.create(CodeReviewRunDetailView.class), runQuery)
					.getResultList();
			if (runs.isEmpty()) {
				return null;
			}

			var qf = new QCodeReviewRunFile("f");
			var fileQuery = configBuilder.create(em, CodeReviewRunFile.class)
					.from(CodeReviewRunFile.class, qf.getMetadata().getName())
					.where(qf.run.id.toString()).eq(runId)
					// Ascending by id reproduces the order GitHub reported the commit's files, since
					// persistRunFiles inserts in that order.
					.orderByAsc(qf.id.toString());
			var files = viewManager.applySetting(EntityViewSetting.create(CodeReviewRunFileListView.class), fileQuery)
					.getResultList();

			return new CodeReviewRunResult(runs.getFirst(), files);
		});
	}

	private Long findRunId(Long repositoryId, Long branchId, LocalDate reviewDate, String strategy) {
		var qr = new QCodeReviewRun("r");
		var rows = configBuilder.create(em, CodeReviewRun.class)
				.from(CodeReviewRun.class, qr.getMetadata().getName())
				.where(qr.repository.id.toString()).eq(repositoryId)
				.where(qr.branch.id.toString()).eq(branchId)
				.where(qr.reviewDate.toString()).eq(reviewDate)
				.where(qr.strategy.toString()).eq(strategy)
				.setMaxResults(1)
				.getResultList();
		return rows.isEmpty() ? null : rows.getFirst().getId();
	}

	private Map<String, CodeReviewRunFile> loadRunFilesByPath(Long runId) {
		var qf = new QCodeReviewRunFile("f");
		var rows = configBuilder.create(em, CodeReviewRunFile.class)
				.from(CodeReviewRunFile.class, qf.getMetadata().getName())
				.where(qf.run.id.toString()).eq(runId)
				.getResultList();
		var byPath = new HashMap<String, CodeReviewRunFile>();
		for (CodeReviewRunFile row : rows) {
			byPath.put(row.getFilePath(), row);
		}
		return byPath;
	}

	private List<RepositoryRef> findEnabledRepositories(Long projectId) {
		var refs = tx.execute(status -> {
			var qr = new QCodeRepository("r");
			var rows = configBuilder.create(em, CodeRepository.class)
					.from(CodeRepository.class, qr.getMetadata().getName())
					.where(qr.project.id.toString()).eq(projectId)
					.where(qr.enabled.toString()).eq(Boolean.TRUE)
					.where(qr.status.code.toString()).notEq("REM")
					.orderByAsc(qr.id.toString())
					.getResultList();
			var found = new ArrayList<RepositoryRef>();
			for (CodeRepository row : rows) {
				found.add(new RepositoryRef(row.getId(), row.getOwner(), row.getRepoName()));
			}
			return List.copyOf(found);
		});
		return refs == null ? List.of() : refs;
	}

	/**
	 * One registered repository by id, or null. Soft-deleted rows are excluded — a repository removed from
	 * the module must not be reviewable by id.
	 */
	private RepositoryRef findRepositoryRef(Long repositoryId) {
		var qr = new QCodeRepository("r");
		var rows = configBuilder.create(em, CodeRepository.class)
				.from(CodeRepository.class, qr.getMetadata().getName())
				.where(qr.id.toString()).eq(repositoryId)
				.where(qr.status.code.toString()).notEq("REM")
				.setMaxResults(1)
				.getResultList();
		if (rows.isEmpty()) {
			return null;
		}
		var row = rows.getFirst();
		return new RepositoryRef(row.getId(), row.getOwner(), row.getRepoName());
	}

	/**
	 * One branch selected under a repository, or null when it does not exist or belongs to a different
	 * repository. Scoped by {@code repositoryId} rather than checked afterwards: a branch row of another
	 * repository must not be reviewable through this one's id, which is also what makes the "not found"
	 * answer cover both cases without leaking which of them it was.
	 */
	private BranchRef findBranchRef(Long repositoryId, Long branchId) {
		var qb = new QCodeReviewBranch("b");
		var rows = configBuilder.create(em, CodeReviewBranch.class)
				.from(CodeReviewBranch.class, qb.getMetadata().getName())
				.where(qb.id.toString()).eq(branchId)
				.where(qb.repository.id.toString()).eq(repositoryId)
				.setMaxResults(1)
				.getResultList();
		if (rows.isEmpty()) {
			return null;
		}
		var row = rows.getFirst();
		return new BranchRef(row.getId(), row.getBranchName());
	}

	private List<BranchRef> findEnabledBranches(Long repositoryId) {
		var refs = tx.execute(status -> {
			var qb = new QCodeReviewBranch("b");
			var rows = configBuilder.create(em, CodeReviewBranch.class)
					.from(CodeReviewBranch.class, qb.getMetadata().getName())
					.where(qb.repository.id.toString()).eq(repositoryId)
					.where(qb.enabled.toString()).eq(Boolean.TRUE)
					.orderByAsc(qb.id.toString())
					.getResultList();
			var found = new ArrayList<BranchRef>();
			for (CodeReviewBranch row : rows) {
				found.add(new BranchRef(row.getId(), row.getBranchName()));
			}
			return List.copyOf(found);
		});
		return refs == null ? List.of() : refs;
	}

	private long countFiles(Long runId) {
		return this.em.createQuery(
						"select count(f) from CodeReviewRunFile f where f.run.id = :runId", Long.class)
				.setParameter("runId", runId)
				.getSingleResult();
	}

	private long countFilesWithStatus(Long runId, String statusCode) {
		return this.em.createQuery(
						"select count(f) from CodeReviewRunFile f where f.run.id = :runId and f.status.code = :code",
						Long.class)
				.setParameter("runId", runId)
				.setParameter("code", statusCode)
				.getSingleResult();
	}

	private long countValidFindings(Long runId) {
		return this.em.createQuery(
						"select count(d) from CodeReviewFinding d where d.run.id = :runId "
								+ "and d.validationStatus = :status", Long.class)
				.setParameter("runId", runId)
				.setParameter("status", FindingValidationStatus.VALID)
				.getSingleResult();
	}

	private boolean projectIsActive(Long projectId) {
		var vr = IlKeiEntityValidator.builder().withCriteriaBuilderFactory(em, configBuilder)
				.pick().withEntity(Project.class, "p").whereCriteriaEqual("p.id", projectId)
				.whereCriteriaEqual("p.status.code", "AKT")
				.withErrorMessage(Utility.getErrorMessage("codereview.repository.project_not_found")).pack()
				.validate();
		return vr.getResult();
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Resolved per transaction rather than cached: the returned {@link StatusProcess} must be managed by
	 * the persistence context it is about to be written from, and a reference detached from an earlier
	 * transaction is not.
	 *
	 * @throws IllegalStateException when the status row is absent, which means the seed data was never
	 *                               applied. Failing the run is better than writing a null status into a
	 *                               {@code NOT NULL} column.
	 */
	private StatusProcess processStatus(String code) {
		var status = statusService.getStatusProcessByCode(code);
		if (status == null) {
			throw new IllegalStateException("status '" + code + "' missing — run POST /api/init/master");
		}
		return status;
	}

	private static String statusCode(CodeReviewRunFile row) {
		return row.getStatus() == null ? null : row.getStatus().getCode();
	}

	private static Long durationSince(Instant startedAt, Instant completedAt) {
		if (startedAt == null || completedAt == null) {
			return null;
		}
		return completedAt.toEpochMilli() - startedAt.toEpochMilli();
	}

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

	private static String truncate(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max);
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
