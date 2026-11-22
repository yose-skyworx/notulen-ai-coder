package com.ilkeiapps.skyworx.notulenai.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViewSetting;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ilkeiapps.ilkeitoolentityblazebit.validator.IlKeiEntityValidator;
import com.ilkeiapps.skyworx.notulenai.bean.enums.BranchReviewStatus;
import com.ilkeiapps.skyworx.notulenai.bean.enums.DiffSide;
import com.ilkeiapps.skyworx.notulenai.bean.enums.FindingValidationStatus;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewRoleType;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewSeverity;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewBranchFilesResult;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewRunResult;
import com.ilkeiapps.skyworx.notulenai.entity.CodeRepository;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewBranch;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewBranchResult;
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
import com.ilkeiapps.skyworx.notulenai.service.FindingValidator.CandidateFinding;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.CommitInfo;
import com.ilkeiapps.skyworx.notulenai.utility.Utility;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Every database operation a code review run performs, each public method its own short Spring
 * transaction.
 * <p>
 * {@link CodeReviewRunService} orchestrates a run untransactioned — it makes the GitHub and model calls,
 * which can each take seconds — and calls into this bean only for the persistence steps, so a pooled
 * connection is never held across a remote call. The two classes are separate beans precisely so these
 * {@code @Transactional} methods are invoked through the Spring proxy: a same-class call would bypass the
 * proxy and run with no transaction at all (see {@code AGENTS.md}, "Spring proxy constraints").
 * <p>
 * Each write therefore commits on its own. A late failure cannot roll back the files already reviewed,
 * and a run's terminal status plus error message are written in their own committed transaction via
 * {@link #failRun}/{@link #finalizeRun} so the cause survives. Reads are {@code readOnly = true} and
 * return plain values or fully materialized Entity Views, never managed entities.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeReviewRunPersistence {

	private static final String STATUS_PROCESSING = "PROCESSING";
	private static final String STATUS_COMPLETED = "COMPLETED";
	private static final String STATUS_COMPLETED_WITH_WARNING = "COMPLETED_WITH_WARNING";
	private static final String STATUS_FAILED = "FAILED";
	private static final String STATUS_SKIPPED = "SKIPPED";
	private static final String STATUS_NO_COMMIT = "NO_COMMIT";

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

	/** Target type for turning the whole-branch result into the {@code jsonb} payload. */
	private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
	};

	/**
	 * Local Jackson (2.x) mapper, used only to convert the whole-branch result into the {@code jsonb} map.
	 * Spring Boot 4 auto-configures a Jackson 3 mapper, so no {@code com.fasterxml.jackson.databind.ObjectMapper}
	 * bean exists to inject — same as {@link GithubApiClient}.
	 */
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;
	private final EntityViewManager viewManager;
	private final StatusService statusService;
	private final FindingValidator findingValidator;

	// ------------------------------------------------------------------ shared records

	/** A registered repository, reduced to what the non-transactional stages need. */
	public record RepositoryRef(Long id, String owner, String repoName) {
		public String slug() {
			return owner + "/" + repoName;
		}
	}

	/** A branch selected for review under a repository. */
	public record BranchRef(Long id, String branchName) {
	}

	/** A claimed run row plus the GitHub coordinates captured inside the claiming transaction. */
	public record ClaimedRun(Long runId, String owner, String repoName, String branchName) {
	}

	/** Outcome of persisting a run's file list: the counters and the files still to review. */
	public record PersistedFiles(int totalFiles, int skippedFiles, Map<String, Long> pendingIdsByPath) {
	}

	// ------------------------------------------------------------------ writes

	/**
	 * Persists one whole-branch review as a flat {@code code_review_branch_results} row.
	 * <p>
	 * Its own short transaction, invoked by {@link CodeReviewRunService} after the GitHub listing and PMD
	 * analysis have already run untransactioned — so no pooled connection is held across a network call (see
	 * the class note and {@code AGENTS.md}, "Remote calls MUST run outside the database transaction"). The
	 * repository and branch are attached by reference, not loaded: their ids were already validated to exist
	 * when the trigger resolved them, so {@code getReference} writes the foreign keys without a select.
	 * <p>
	 * {@code result} is the whole {@code CodeReviewBranchFilesResult}, converted to a map so Hibernate stores
	 * it as {@code jsonb}; it is null on a {@code FAILED} row, where there is no result to keep.
	 *
	 * @param repositoryId the registered repository's id, used as the {@code repository_id} foreign key
	 * @param branchId     the branch's id, used as the {@code branch_id} foreign key
	 * @param startedAt    when the trigger began, captured before any GitHub call
	 * @param completedAt  when the trigger finished
	 * @param status       the outcome; {@code COMPLETED} carries a {@code result}, {@code FAILED} does not
	 * @param result       the whole-branch result to store as jsonb, or null for a failed attempt
	 * @return the generated row id, so the caller can later attach a report to the same row
	 */
	@Transactional
	public Long saveBranchResult(Long repositoryId, Long branchId, Instant startedAt, Instant completedAt,
			BranchReviewStatus status, CodeReviewBranchFilesResult result) {
		var row = new CodeReviewBranchResult();
		row.setRepository(em.getReference(CodeRepository.class, repositoryId));
		row.setBranch(em.getReference(CodeReviewBranch.class, branchId));
		row.setStartedAt(startedAt);
		row.setCompletedAt(completedAt);
		row.setStatus(status);
		row.setResult(result == null ? null : OBJECT_MAPPER.convertValue(result, JSON_MAP));
		em.persist(row);
		// Flush inside the transaction so a jsonb/constraint failure surfaces here rather than after the
		// caller has already returned the result to the client, and so the generated id is available.
		em.flush();
		return row.getId();
	}

	/**
	 * Attaches the generated report text to an already-persisted branch-result row, in its own short
	 * transaction. Separate from {@link #saveBranchResult} on purpose: the report comes from an LLM call,
	 * which the caller makes untransactioned after the row is committed, so writing it is its own unit of
	 * work rather than a connection held across that remote call.
	 * <p>
	 * A missing row (e.g. deleted between the two steps) is a no-op logged at debug, not an error — the
	 * review itself already succeeded and the report is a best-effort enrichment.
	 *
	 * @param id         the row returned by {@link #saveBranchResult}
	 * @param reportText the rendered report; stored as-is on {@code report_text}
	 */
	@Transactional
	public void updateBranchReport(Long id, String reportText) {
		var row = em.find(CodeReviewBranchResult.class, id);
		if (row == null) {
			log.debug("code review: branch result {} is gone, report not stored", id);
			return;
		}
		// Managed entity: dirty checking writes the column on commit; flush to surface any failure here.
		row.setReportText(reportText);
		em.flush();
	}

	/**
	 * Finds the run for this identity or creates it. Relies on {@code uq_crr_identity} rather than a
	 * check-then-insert across transactions: two simultaneous triggers for the same repository, branch,
	 * day and strategy cannot both insert, and the loser surfaces as a failure on its own response rather
	 * than as a duplicate run. The scheduler is unaffected because it writes {@code DAILY}.
	 */
	@Transactional
	public ClaimedRun claimRun(RepositoryRef repository, BranchRef branch, LocalDate reviewDate, String strategy) {
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

	@Transactional
	public void recordCommit(Long runId, CommitInfo commit, String parentSha) {
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
	@Transactional
	public PersistedFiles persistRunFiles(Long runId, List<ClassifiedFile> classified, String commitSha) {
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
			// here and the per-file writer fills it in once the content has settled it; skipped rows keep
			// whatever classification decided, which is why this is written unconditionally rather than merged.
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

	@Transactional
	public void recordNoCommit(Long runId) {
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

	/**
	 * Skips a file, recording the role/guideline it was being reviewed as (when known) alongside the
	 * reason. {@code role}/{@code guidelineId} are null when the snapshot was never read or no role
	 * resolved; a null value leaves that column untouched.
	 */
	@Transactional
	public void skipFile(Long runFileId, ReviewRoleType role, Long guidelineId, String reason) {
		var row = this.em.find(CodeReviewRunFile.class, runFileId);
		if (row == null) {
			return;
		}
		var now = Instant.now();
		applyBasis(row, role, guidelineId);
		row.setStatus(processStatus(STATUS_SKIPPED));
		row.setSkipReason(truncate(reason, MAX_DETAIL_LENGTH));
		row.setStartedAt(null);
		row.setCompletedAt(now);
		row.setUpdatedAt(now);
		this.em.flush();
	}

	@Transactional
	public void failFile(Long runFileId, String error) {
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
	 * Records the basis, validates and persists every candidate finding (valid or not), then closes the
	 * file as {@code COMPLETED} — all in one transaction, so a file produces a single terminal commit.
	 * <p>
	 * Rejected findings are kept because discarding them would make the pipeline look more accurate than
	 * it is; the {@code validation_status} and {@code validation_detail} columns are what make model
	 * reliability measurable. Only {@code VALID} rows are counted in {@code code_review_runs
	 * .total_findings} — a headline count that included rejected claims would misrepresent the review.
	 */
	@Transactional
	public void completeFile(Long runId, Long runFileId, String path, DiffSide side, int lineCount,
			ReviewRoleType role, Long guidelineId, List<CandidateFinding> candidates) {
		var run = this.em.find(CodeReviewRun.class, runId);
		var runFile = this.em.find(CodeReviewRunFile.class, runFileId);
		if (run == null || runFile == null) {
			return;
		}

		applyBasis(runFile, role, guidelineId);
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
	 * Closes the run with its aggregate counters and a terminal status.
	 * <p>
	 * {@code COMPLETED_WITH_WARNING} covers both "some files failed" and "no file was actually
	 * analyzed". The second case matters: with inference disabled every file is skipped, and that run
	 * must not look identical to one that reviewed everything and found nothing.
	 *
	 * @param note appended to the summary, or null. Carries a caveat the counters cannot express — a
	 *             truncated tree listing means the run covered fewer files than the branch holds.
	 */
	@Transactional
	public void finalizeRun(Long runId, String note) {
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

	@Transactional
	public void failRun(Long runId, String error) {
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
	@Transactional(readOnly = true)
	public CodeReviewRunResult loadRunResult(Long runId) {
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
	}

	@Transactional(readOnly = true)
	public List<RepositoryRef> findEnabledRepositories(Long projectId) {
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
	}

	/**
	 * One registered repository by id, or null. Soft-deleted rows are excluded — a repository removed from
	 * the module must not be reviewable by id.
	 */
	@Transactional(readOnly = true)
	public RepositoryRef findRepositoryRef(Long repositoryId) {
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
	@Transactional(readOnly = true)
	public BranchRef findBranchRef(Long repositoryId, Long branchId) {
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

	@Transactional(readOnly = true)
	public List<BranchRef> findEnabledBranches(Long repositoryId) {
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
	}

	@Transactional(readOnly = true)
	public boolean projectIsActive(Long projectId) {
		var vr = IlKeiEntityValidator.builder().withCriteriaBuilderFactory(em, configBuilder)
				.pick().withEntity(Project.class, "p").whereCriteriaEqual("p.id", projectId)
				.whereCriteriaEqual("p.status.code", "AKT")
				.withErrorMessage(Utility.getErrorMessage("codereview.repository.project_not_found")).pack()
				.validate();
		return vr.getResult();
	}

	// ------------------------------------------------------------------ helpers

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

	/**
	 * Writes the resolved role and guideline version onto a row, as part of whichever terminal write runs
	 * for the file. Both columns are only ever set, never cleared, so a row keeps saying what it was
	 * reviewed as even across a resume. A null role or guideline id leaves that column untouched.
	 */
	private void applyBasis(CodeReviewRunFile row, ReviewRoleType role, Long guidelineId) {
		if (role != null) {
			row.setRoleType(role);
		}
		if (guidelineId != null) {
			// A reference, not a find: the guideline id was resolved from the library the orchestrator
			// already read, so there is no reason to pull its text back through the session.
			row.setGuideline(this.em.getReference(CodeReviewGuideline.class, guidelineId));
		}
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

	private static String truncate(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max);
	}
}
