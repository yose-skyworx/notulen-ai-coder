package com.ilkeiapps.skyworx.notulenai.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ilkeiapps.skyworx.notulenai.bean.CodeRepositoryRequest;
import com.ilkeiapps.skyworx.notulenai.bean.CodeRepositoryUpdateRequest;
import com.ilkeiapps.skyworx.notulenai.bean.CodeReviewBranchRequest;
import com.ilkeiapps.skyworx.notulenai.bean.CodeReviewScheduleRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeRepositoryIdRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeRepositoryRequestParameter;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewBranchRequestParameter;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewBranchTriggerRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewProjectTriggerRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewRunResult;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewScheduleRequestParameter;
import com.ilkeiapps.skyworx.notulenai.entityview.codereview.CodeRepositoryListView;
import com.ilkeiapps.skyworx.notulenai.entityview.codereview.CodeReviewBranchListView;
import com.ilkeiapps.skyworx.notulenai.entityview.codereview.CodeReviewScheduleListView;
import com.ilkeiapps.skyworx.notulenai.service.CodeRepositoryService;
import com.ilkeiapps.skyworx.notulenai.service.CodeReviewBranchService;
import com.ilkeiapps.skyworx.notulenai.service.CodeReviewRunService;
import com.ilkeiapps.skyworx.notulenai.service.CodeReviewScheduleService;

import lombok.RequiredArgsConstructor;

/**
 * Configuration endpoints for the automated code review module: which repositories are registered,
 * which of their branches are selected, and when they are reviewed. Plus the two triggers that run a
 * review now — one over a project's selected branches by commit date, one over a single branch in full.
 * <p>
 * Routes sit under {@code /api/**} and therefore require a valid JWT (see {@code SecurityConfiguration}).
 * Per-project scoping is enforced in the services, which reject any project that is not {@code AKT}.
 * <p>
 * Reads return Entity Views. Writes return the service's {@link ApiResponse} directly, wrapping the
 * request bean — never a managed entity, and never the GitHub token, which is deployment-wide
 * configuration rather than stored state and so never appears in a payload at all.
 * <p>
 * The remaining run endpoints (history, per-file breakdown, findings, dashboard) arrive with later
 * phases and are not declared here yet.
 */
@RestController
@RequestMapping(path = "/api/code-review")
@RequiredArgsConstructor
public class CodeReviewController {

	private final CodeRepositoryService codeRepositoryService;
	private final CodeReviewBranchService codeReviewBranchService;
	private final CodeReviewScheduleService codeReviewScheduleService;
	private final CodeReviewRunService codeReviewRunService;

	@GetMapping(path = "/repository/list", produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<CodeRepositoryListView> listRepository(CodeRepositoryRequestParameter filter) {
		return codeRepositoryService.listRepository(filter);
	}

	@PostMapping(path = "/repository/save",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<CodeRepositoryRequest> saveRepository(@RequestBody CodeRepositoryRequest req) {
		return codeRepositoryService.saveRepository(req);
	}

	@PostMapping(path = "/repository/update",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<CodeRepositoryUpdateRequest> updateRepository(@RequestBody CodeRepositoryUpdateRequest req) {
		return codeRepositoryService.updateRepository(req);
	}

	@PostMapping(path = "/repository/delete",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<CodeRepositoryIdRequest> deleteRepository(@RequestBody CodeRepositoryIdRequest req) {
		return codeRepositoryService.deleteRepository(req);
	}

	@GetMapping(path = "/branch/list", produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<CodeReviewBranchListView> listBranch(CodeReviewBranchRequestParameter filter) {
		return codeReviewBranchService.listBranch(filter);
	}

	@PostMapping(path = "/branch/save",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<CodeReviewBranchRequest> saveBranch(@RequestBody CodeReviewBranchRequest req) {
		return codeReviewBranchService.saveBranches(req);
	}

	@GetMapping(path = "/schedule/list", produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<CodeReviewScheduleListView> listSchedule(CodeReviewScheduleRequestParameter filter) {
		return codeReviewScheduleService.listSchedule(filter);
	}

	@PostMapping(path = "/schedule/save",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<CodeReviewScheduleRequest> saveSchedule(@RequestBody CodeReviewScheduleRequest req) {
		return codeReviewScheduleService.saveSchedule(req);
	}

	/**
	 * Runs a review now for every enabled repository of the project and every enabled branch selected
	 * under it.
	 * <p>
	 * Body is {@code {"projectId": 1}} or {@code {"projectId": 1, "reviewDate": "2026-11-10"}}. Omitting
	 * {@code reviewDate} reviews the previous calendar day in {@code code-review.timezone}, which is what
	 * the scheduled job targets; naming a day is how a review is aimed at the last day that actually
	 * received a commit, since a quiet branch makes the default target come back {@code NO_COMMIT}. Every
	 * repository in the fan-out reviews the same day, so the results stay comparable.
	 * <p>
	 * <b>Synchronous and potentially slow.</b> The request blocks until every repository×branch pair has
	 * finished, and each pair makes a series of GitHub calls plus, when inference is enabled, a model
	 * call per file. A project with several active repositories can hold this connection open for
	 * minutes. Callers must set a client read timeout well above the usual default.
	 * <p>
	 * The response carries one {@link CodeReviewRunResult} per run: the run with its own terminal status,
	 * plus the per-file breakdown of what it covered. Skipped files are entries too, each with a
	 * {@code skipReason}, so the counts on the run can be reconciled against actual paths instead of being
	 * taken on trust. A partial failure is reported as data rather than as an error: {@code status=true}
	 * means at least one run executed, and each entry says whether that run {@code COMPLETED},
	 * {@code COMPLETED_WITH_WARNING}, {@code NO_COMMIT}, or {@code FAILED}.
	 * Re-triggering the same project on the same day resumes the existing runs rather than duplicating
	 * them, so a retry after a partial failure only redoes the work that is still outstanding — and a
	 * wrong date costs nothing to correct by triggering again with the right one.
	 */
	@PostMapping(path = "/run/trigger-project",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<CodeReviewRunResult> triggerProjectReview(
			@RequestBody CodeReviewProjectTriggerRequest req) {
		return codeReviewRunService.triggerProject(req);
	}

	/**
	 * Runs a review now over one branch in full: the files it holds at its current head that fall inside the
	 * target roles, rather than the diff of one commit that reached it on one day.
	 * <p>
	 * Body is {@code {"repositoryId": 1, "branchId": 2}}. Both are mandatory and the branch must belong to
	 * the repository. The ids come from {@code /repository/list} and {@code /branch/list}; a branch
	 * <em>name</em> is not accepted, because a run is keyed on the registered branch row and accepting a name
	 * would mean either refusing unregistered branches or silently registering one on the caller's behalf.
	 * <p>
	 * Use it when the question is "what is wrong with this branch" rather than "what changed yesterday" — a
	 * first review of a repository that was never covered, or a re-review after the guidelines changed.
	 * <p>
	 * A branch listing is the whole repository, so {@code code-review.java-filter} acts as a whitelist here
	 * rather than as a narrowing of Java: what is examined is the service, controller and entity files plus
	 * everything under those directories, and the rest — every Vue component, migration, README — is
	 * reported with {@code skipReason: NOT_A_TARGET_ROLE}. The filter's annotation list does not apply,
	 * because reading annotations would mean fetching every Java file in the branch first.
	 * {@code code-review.branch-review.max-files} then bounds how many of the selected files are examined,
	 * and the remainder are reported as {@code BRANCH_FILE_LIMIT}. Nothing is dropped: every file GitHub
	 * listed comes back as an entry, with the reason it was not examined when it was not.
	 * <p>
	 * <b>Synchronous and potentially slow</b> — much more so than the project trigger. Every examinable file
	 * costs one GitHub content fetch and, when inference is enabled, one model call, at a file concurrency
	 * of one. Callers must set a client read timeout well above the usual default.
	 * <p>
	 * There is no diff, so findings cite whole files rather than changed lines: {@code side} is always
	 * {@code NEW} and {@code parentCommitSha} is null. The response is the same
	 * {@link CodeReviewRunResult} shape as the project trigger, carrying one run — the branch review with
	 * its terminal status plus every file it covered, each with a {@code skipReason} when it was not
	 * examined. Re-triggering the same branch on the same day resumes that day's run rather than creating a
	 * new one, so a retry only redoes the work still outstanding.
	 */
	@PostMapping(path = "/run/trigger-branch",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<CodeReviewRunResult> triggerBranchReview(
			@RequestBody CodeReviewBranchTriggerRequest req) {
		return codeReviewRunService.triggerBranch(req);
	}
}
