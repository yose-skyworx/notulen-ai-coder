package com.ilkeiapps.skyworx.notulenai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.CommitInfo;

/**
 * Covers which commit a run examines. This is the decision that {@code uq_crr_identity} is built on:
 * if the same day can resolve to two different commits, a re-trigger creates a second run instead of
 * resuming the first, and findings get duplicated.
 */
class CommitSelectionServiceTest {

	private static final String EMPTY_TREE_SHA = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
	private static final LocalDate REVIEW_DATE = LocalDate.of(2026, 11, 13);

	private final CodeReviewProperties properties = new CodeReviewProperties();
	private final StubGithubApiClient github = new StubGithubApiClient(properties);
	private final CommitSelectionService service = new CommitSelectionService(github, properties);

	/**
	 * Stands in for the real client so no HTTP is involved. The superclass constructor takes the
	 * {@code RestClient} it would otherwise use; passing null is safe because the overridden method
	 * never reaches it.
	 */
	private static final class StubGithubApiClient extends GithubApiClient {

		private List<CommitInfo> commits = List.of();
		private Instant capturedSince;
		private Instant capturedUntil;

		StubGithubApiClient(CodeReviewProperties properties) {
			super(null, properties);
		}

		@Override
		public List<CommitInfo> listCommits(String owner, String repo, String branchRef, Instant since,
				Instant until) {
			this.capturedSince = since;
			this.capturedUntil = until;
			return this.commits;
		}
	}

	private static CommitInfo commit(String sha, Instant committerDate, String... parents) {
		return new CommitInfo(sha, "message for " + sha, "Author", committerDate, "Committer", committerDate,
				List.of(parents));
	}

	private Optional<CommitSelectionService.Selection> select() {
		return service.selectForDay("skyworx", "notulen-ai", "dev", REVIEW_DATE);
	}

	@Test
	void asksGithubForTheHalfOpenWindowInTheConfiguredZone() {
		properties.setTimezone("UTC");

		select();

		assertEquals(Instant.parse("2026-11-13T00:00:00Z"), github.capturedSince);
		assertEquals(Instant.parse("2026-11-14T00:00:00Z"), github.capturedUntil);
	}

	@Test
	void selectsTheNewestCommitOfTheDay() {
		properties.setTimezone("UTC");
		github.commits = List.of(
				commit("1111111111111111111111111111111111111111", Instant.parse("2026-11-13T09:00:00Z"),
						"0000000000000000000000000000000000000000"),
				commit("2222222222222222222222222222222222222222", Instant.parse("2026-11-13T17:30:00Z"),
						"1111111111111111111111111111111111111111"),
				commit("3333333333333333333333333333333333333333", Instant.parse("2026-11-13T11:00:00Z"),
						"1111111111111111111111111111111111111111"));

		var selection = select().orElseThrow();

		assertEquals("2222222222222222222222222222222222222222", selection.commit().sha());
		assertEquals("1111111111111111111111111111111111111111", selection.parentCommitSha());
	}

	@Test
	void reChecksTheWindowBecauseGithubsFilteringIsApproximate() {
		properties.setTimezone("UTC");
		// Both out-of-window commits are newer than the in-window one, so trusting GitHub's filtering
		// would select either of them and review a day that was not asked for.
		github.commits = List.of(
				commit("4444444444444444444444444444444444444444", Instant.parse("2026-11-14T00:00:00Z"),
						"5555555555555555555555555555555555555555"),
				commit("5555555555555555555555555555555555555555", Instant.parse("2026-11-13T10:00:00Z"),
						"6666666666666666666666666666666666666666"),
				commit("6666666666666666666666666666666666666666", Instant.parse("2026-11-12T23:59:59Z")));

		var selection = select().orElseThrow();

		// Midnight of the following day belongs to the next review day, which is what makes the window
		// half-open rather than merely inclusive at both ends.
		assertEquals("5555555555555555555555555555555555555555", selection.commit().sha());
	}

	@Test
	void ignoresACommitWithNoCommitterDateRatherThanAssumingItFits() {
		properties.setTimezone("UTC");
		github.commits = List.of(
				commit("7777777777777777777777777777777777777777", null,
						"8888888888888888888888888888888888888888"),
				commit("8888888888888888888888888888888888888888", Instant.parse("2026-11-13T08:00:00Z")));

		assertEquals("8888888888888888888888888888888888888888", select().orElseThrow().commit().sha());
	}

	@Test
	void diffsARootCommitAgainstGitsEmptyTree() {
		properties.setTimezone("UTC");
		github.commits = List.of(commit("9999999999999999999999999999999999999999",
				Instant.parse("2026-11-13T06:00:00Z")));

		assertEquals(EMPTY_TREE_SHA, select().orElseThrow().parentCommitSha());
	}

	@Test
	void reportsNoCommitWhenTheBranchWasUntouchedThatDay() {
		properties.setTimezone("UTC");
		github.commits = List.of();

		assertTrue(select().isEmpty());
	}

	@Test
	void breaksTimestampTiesByShaSoAResumedRunSelectsTheSameCommit() {
		properties.setTimezone("UTC");
		var sameInstant = Instant.parse("2026-11-13T12:00:00Z");
		// A squash or a scripted rebase can leave two commits sharing a committer timestamp. Without a
		// deterministic second key the selection would depend on the order GitHub happened to return them.
		github.commits = List.of(
				commit("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", sameInstant),
				commit("cccccccccccccccccccccccccccccccccccccccc", sameInstant),
				commit("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", sameInstant));

		assertEquals("cccccccccccccccccccccccccccccccccccccccc", select().orElseThrow().commit().sha());
	}

	@Test
	void measuresTheWindowInTheConfiguredZoneNotTheJvmDefault() {
		properties.setTimezone("Asia/Jakarta");

		select();

		// UTC+7 with no daylight saving, so the local midnight is the previous evening in UTC.
		assertEquals(Instant.parse("2026-11-12T17:00:00Z"), github.capturedSince);
		assertEquals(Instant.parse("2026-11-13T17:00:00Z"), github.capturedUntil);
	}

	@Test
	void rejectsAnInvalidZoneInsteadOfSubstitutingTheJvmDefault() {
		properties.setTimezone("Not/AZone");

		var failure = assertThrows(IllegalStateException.class, () -> service.reviewZone());

		assertTrue(failure.getMessage().contains("Not/AZone"));
	}
}
