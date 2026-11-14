package com.ilkeiapps.skyworx.notulenai.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.CommitInfo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Chooses the single commit a review run examines: the newest commit on the selected branch whose
 * committer date falls inside the review day.
 * <p>
 * One commit per branch per day is what makes {@code uq_crr_identity} — (repository, branch,
 * review_date, strategy) — a meaningful identity. Re-running the same day re-examines the same commit
 * rather than picking up whatever landed since, which is what allows a run to resume instead of
 * duplicating findings.
 * <p>
 * The window is derived from the committer date, not the author date. Author dates are preserved
 * across rebases and cherry-picks and can be months older than the moment the commit actually reached
 * the branch, so filtering on them would review a commit on a day it was not there.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommitSelectionService {

	/**
	 * Git's empty tree. A root commit has no parent, and diffing against this SHA is how git itself
	 * represents "everything in this commit is new" — which is exactly the right baseline for the first
	 * commit of a repository.
	 */
	private static final String EMPTY_TREE_SHA = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";

	private final GithubApiClient githubApiClient;
	private final CodeReviewProperties properties;

	/** The chosen commit and the baseline its diff is taken against. */
	public record Selection(CommitInfo commit, String parentCommitSha) {
	}

	/**
	 * The zone the review day is measured in. Exposed because the orchestrator derives {@code
	 * reviewDate} from it and must not resolve a second, possibly different, zone.
	 *
	 * @throws IllegalStateException when {@code code-review.timezone} is not a valid IANA zone. Failing
	 *         loudly is deliberate: silently substituting the JVM default would shift the window by
	 *         hours and select a different commit, and a wrong commit is far harder to notice than a
	 *         failed run.
	 */
	public ZoneId reviewZone() {
		var configured = properties.getTimezone();
		try {
			return ZoneId.of(configured);
		} catch (DateTimeException e) {
			throw new IllegalStateException("code-review.timezone is not a valid IANA zone: '" + configured + "'", e);
		}
	}

	/**
	 * @return the newest in-window commit, or empty when the branch received no commit that day — a
	 *         normal outcome, recorded as {@code NO_COMMIT} rather than treated as a failure
	 */
	public Optional<Selection> selectForDay(String owner, String repo, String branchName, LocalDate reviewDate) {
		var zone = reviewZone();
		var since = reviewDate.atStartOfDay(zone).toInstant();
		var until = reviewDate.plusDays(1).atStartOfDay(zone).toInstant();

		var commits = githubApiClient.listCommits(owner, repo, branchName, since, until);

		CommitInfo latest = null;
		for (CommitInfo candidate : commits) {
			if (candidate.committerDate() == null) {
				continue;
			}
			// Re-checked locally because GitHub's since/until filtering is documented as approximate.
			// Half-open [since, until) so a commit exactly at midnight belongs to one day only.
			if (candidate.committerDate().isBefore(since) || !candidate.committerDate().isBefore(until)) {
				continue;
			}
			if (latest == null || isNewer(candidate, latest)) {
				latest = candidate;
			}
		}

		if (latest == null) {
			log.info("commit selection: no commit on {}:{} within {} ({} of {} candidate(s) in window)",
					owner + "/" + repo, branchName, reviewDate, 0, commits.size());
			return Optional.empty();
		}

		var parent = latest.parentShas().isEmpty() ? EMPTY_TREE_SHA : latest.parentShas().getFirst();
		log.info("commit selection: {}:{} {} -> {} ({} candidate(s) in window)", owner + "/" + repo, branchName,
				reviewDate, latest.sha(), commits.size());
		return Optional.of(new Selection(latest, parent));
	}

	/**
	 * Orders by committer date, then by SHA. The tie-break is not cosmetic: two commits on a branch can
	 * share a committer timestamp after a squash or a scripted rebase, and without a deterministic
	 * second key the same run could select a different commit on each attempt and never resume.
	 */
	private static boolean isNewer(CommitInfo candidate, CommitInfo current) {
		var comparison = candidate.committerDate().compareTo(current.committerDate());
		return comparison != 0 ? comparison > 0 : candidate.sha().compareTo(current.sha()) > 0;
	}
}
