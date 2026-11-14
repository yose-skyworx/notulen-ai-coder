package com.ilkeiapps.skyworx.notulenai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ilkeiapps.skyworx.notulenai.bean.enums.FileChangeStatus;
import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;
import com.ilkeiapps.skyworx.notulenai.service.ChangedFileClassifier.ClassifiedFile;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.BranchFile;
import com.ilkeiapps.skyworx.notulenai.service.GithubApiClient.CommitFile;

/**
 * Covers the review-eligibility decisions, which are the ones that silently change what a run covers.
 * Every rejection must carry a reason: an excluded file that vanished from the results is
 * indistinguishable from a file that was reviewed and found clean.
 * <p>
 * Both entry points are covered — a commit's changed files and a branch's whole listing — because they
 * produce the same shape and the rest of the pipeline cannot tell them apart. They do not gate on the
 * same things, and the asymmetry is deliberate, so it is pinned from both sides: a branch listing is
 * narrowed to the target roles from the path alone, while a commit's file list is not narrowed here at
 * all.
 */
class ChangedFileClassifierTest {

	private final CodeReviewProperties properties = new CodeReviewProperties();
	private final ChangedFileClassifier classifier = newClassifier(properties);

	/** Both beans read the same properties, so a test that edits one must rebuild the pair together. */
	private static ChangedFileClassifier newClassifier(CodeReviewProperties properties) {
		return new ChangedFileClassifier(properties, new JavaRoleFilter(properties));
	}

	private static CommitFile file(String path, String status, String patch) {
		return new CommitFile(path, null, status, 10, 2, patch);
	}

	private ClassifiedFile classifyOne(String path, String status, String patch) {
		return classifier.classify(List.of(file(path, status, patch))).getFirst();
	}

	private ClassifiedFile classifyOneBranchFile(String path, String blobSha) {
		return classifier.classifyBranchFiles(List.of(new BranchFile(path, blobSha, 512L))).getFirst();
	}

	private static String sha256(String text) throws Exception {
		var digest = MessageDigest.getInstance("SHA-256");
		return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void marksAnOrdinarySourceChangeReviewable() {
		var result = classifyOne("src/main/java/OrderService.java", "modified", "@@ -1 +1 @@\n-old\n+new");
		assertTrue(result.reviewable());
		assertNull(result.skipReason());
		assertEquals(FileChangeStatus.MODIFIED, result.changeStatus());
		assertEquals("Java", result.language());
	}

	@Test
	void excludesConfiguredPatterns() {
		assertFalse(classifyOne("web/node_modules/react/index.js", "modified", "@@ patch @@").reviewable());
		assertEquals(ChangedFileClassifier.SKIP_EXCLUDED,
				classifyOne("web/node_modules/react/index.js", "modified", "@@ patch @@").skipReason());

		assertEquals(ChangedFileClassifier.SKIP_EXCLUDED,
				classifyOne("src/main/resources/logo.png", "added", "@@ patch @@").skipReason());
		assertEquals(ChangedFileClassifier.SKIP_EXCLUDED,
				classifyOne("package-lock.json", "modified", "@@ patch @@").skipReason());
		assertEquals(ChangedFileClassifier.SKIP_EXCLUDED,
				classifyOne("target/classes/Order.class", "added", "@@ patch @@").skipReason());
	}

	@Test
	void skipsFilesGitHubGaveNoDiffFor() {
		// A null patch means binary or too-large-to-inline, not "no changes".
		var result = classifyOne("docs/spec.bin", "modified", null);
		assertFalse(result.reviewable());
		assertEquals(ChangedFileClassifier.SKIP_NO_PATCH, result.skipReason());

		assertEquals(ChangedFileClassifier.SKIP_NO_PATCH, classifyOne("docs/spec.bin", "modified", "   ")
				.skipReason());
	}

	@Test
	void reportsExclusionRatherThanAMissingPatch() {
		// Both apply here; the configured exclusion is the reason a reviewer would actually care about.
		var result = classifyOne("web/node_modules/react/index.js", "modified", null);
		assertEquals(ChangedFileClassifier.SKIP_EXCLUDED, result.skipReason());
	}

	@Test
	void mapsEveryGitHubStatusOntoTheEnum() {
		assertEquals(FileChangeStatus.ADDED, classifyOne("a/New.java", "added", "@@ p @@").changeStatus());
		assertEquals(FileChangeStatus.ADDED, classifyOne("a/Copy.java", "copied", "@@ p @@").changeStatus());
		assertEquals(FileChangeStatus.REMOVED, classifyOne("a/Gone.java", "removed", "@@ p @@").changeStatus());
		assertEquals(FileChangeStatus.RENAMED, classifyOne("a/Moved.java", "renamed", "@@ p @@").changeStatus());
		assertEquals(FileChangeStatus.MODIFIED, classifyOne("a/Same.java", "modified", "@@ p @@").changeStatus());
		assertEquals(FileChangeStatus.MODIFIED, classifyOne("a/Same.java", "changed", "@@ p @@").changeStatus());
	}

	@Test
	void fallsBackToModifiedForAnUnknownOrMissingStatus() {
		// MODIFIED is the only value whose handling stays correct for a shape of change we did not expect.
		assertEquals(FileChangeStatus.MODIFIED, classifyOne("a/X.java", "weird-future-status", "@@ p @@")
				.changeStatus());
		assertEquals(FileChangeStatus.MODIFIED, classifyOne("a/X.java", null, "@@ p @@").changeStatus());
	}

	@Test
	void keepsARemovedFileReviewable() {
		// Deleted code is still reviewable — it is what DiffSide.OLD exists for.
		assertTrue(classifyOne("a/Gone.java", "removed", "@@ -1,5 +0,0 @@\n-removed").reviewable());
	}

	@Test
	void leavesLanguageNullRatherThanGuessing() {
		assertNull(classifyOne("Makefile", "modified", "@@ p @@").language());
		assertNull(classifyOne("src/main.rs.bak", "modified", "@@ p @@").language());
		// A dotfile's leading dot is a naming convention, not a type separator.
		assertNull(classifyOne(".gitignore", "modified", "@@ p @@").language());
		assertNull(classifyOne("config/.env", "modified", "@@ p @@").language());
	}

	@Test
	void derivesLanguageFromTheExtensionRegardlessOfDirectoryDots() {
		assertEquals("TypeScript", classifyOne("web/src/app.component.ts", "modified", "@@ p @@").language());
		assertEquals("YAML", classifyOne(".github/workflows/ci.yml", "modified", "@@ p @@").language());
		assertEquals("SQL", classifyOne("db/migrations/V1__init.sql", "added", "@@ p @@").language());
	}

	@Test
	void handlesAnEmptyCommit() {
		assertTrue(classifier.classify(List.of()).isEmpty());
		assertTrue(classifier.classify(null).isEmpty());
	}

	@Test
	void honoursCustomisedExclusions() {
		properties.getExclusions().setPatterns(List.of("**/*.generated.java"));
		var custom = newClassifier(properties);
		var excluded = custom.classify(List.of(file("src/Api.generated.java", "modified", "@@ p @@"))).getFirst();
		assertEquals(ChangedFileClassifier.SKIP_EXCLUDED, excluded.skipReason());
		// A pattern list that replaced the defaults no longer excludes node_modules.
		assertTrue(custom.classify(List.of(file("web/node_modules/x.js", "modified", "@@ p @@"))).getFirst()
				.reviewable());
	}

	// ------------------------------------------------------------------ content hash

	@Test
	void hashesThePatchForACommitFile() throws Exception {
		// Recomputed independently rather than compared against a literal: this hash is the idempotency key
		// for rows already in the database, so it has to stay byte-identical to SHA-256 of the patch or every
		// existing COMPLETED row would stop matching and a re-trigger would re-review the whole commit.
		var patch = "@@ -1,3 +1,4 @@\n-old\n+new";
		assertEquals(sha256(patch), classifyOne("src/main/java/OrderService.java", "modified", patch).contentHash());
	}

	@Test
	void stillHashesAPatchGitHubOmittedOrLeftBlank() throws Exception {
		// A null patch becomes the empty string, but a blank one is hashed as it arrived — which is what the
		// hash has always done, and changing it would invalidate rows already stored. Neither value can gate
		// a resume anyway: both files are skipped for NO_PATCH_CONTENT, so neither ever reaches COMPLETED.
		assertEquals(sha256(""), classifyOne("docs/spec.bin", "modified", null).contentHash());
		assertEquals(sha256("   "), classifyOne("docs/spec.bin", "modified", "   ").contentHash());
	}

	@Test
	void distinguishesOnePatchFromAnother() {
		assertNotEquals(classifyOne("a/X.java", "modified", "@@ -1 +1 @@\n-a\n+b").contentHash(),
				classifyOne("a/X.java", "modified", "@@ -1 +1 @@\n-a\n+c").contentHash());
	}

	@Test
	void usesTheBlobShaAsTheContentHashForABranchFile() {
		// A git blob SHA changes exactly when the content does, so it needs no hashing of its own. At 40 hex
		// characters it also fits the varchar(64) column the commit hash is written to.
		var blobSha = "0123456789abcdef0123456789abcdef01234567";
		assertEquals(blobSha, classifyOneBranchFile("src/main/java/OrderService.java", blobSha).contentHash());
	}

	@Test
	void fallsBackToAHashOfThePathWhenGitHubReportsNoBlobSha() throws Exception {
		// Never null: persistRunFiles compares this value against the stored one, and a null would fail the
		// run rather than re-review the file.
		assertEquals(sha256("src/main/java/OrderService.java"),
				classifyOneBranchFile("src/main/java/OrderService.java", null).contentHash());
		assertEquals(sha256("src/main/java/OrderService.java"),
				classifyOneBranchFile("src/main/java/OrderService.java", "  ").contentHash());
	}

	// ------------------------------------------------------------------ whole-branch listing

	@Test
	void marksARoleFileExaminableEvenThoughThereIsNoPatch() {
		// A branch listing has no patch to be missing, so nothing is rejected for that reason.
		var result = classifyOneBranchFile("src/main/java/OrderService.java", "abc123");
		assertTrue(result.reviewable());
		assertNull(result.skipReason());
		assertEquals("Java", result.language());
	}

	@Test
	void reportsNoChangeForABranchFileBecauseThereIsNothingToCompareAgainst() {
		var result = classifyOneBranchFile("src/main/java/OrderService.java", "abc123");
		// MODIFIED because the column is NOT NULL and this is the value whose handling is correct for an
		// unknown shape of change: the file is read at the head commit and validated against the NEW side.
		assertEquals(FileChangeStatus.MODIFIED, result.changeStatus());
		assertNull(result.file().additions());
		assertNull(result.file().deletions());
		assertNull(result.file().previousFilename());
		assertNull(result.file().patch());
		assertNull(result.file().status());
		assertEquals("src/main/java/OrderService.java", result.file().path());
	}

	@Test
	void appliesTheSameExclusionsToBranchFiles() {
		// None of these are target roles either, so each one also proves the ordering: exclusion is checked
		// first and reports the reason a reviewer would actually care about.
		assertEquals(ChangedFileClassifier.SKIP_EXCLUDED,
				classifyOneBranchFile("web/node_modules/react/index.js", "abc123").skipReason());
		assertEquals(ChangedFileClassifier.SKIP_EXCLUDED,
				classifyOneBranchFile("src/main/resources/logo.png", "abc123").skipReason());
		assertEquals(ChangedFileClassifier.SKIP_EXCLUDED,
				classifyOneBranchFile("package-lock.json", "abc123").skipReason());
		assertEquals(ChangedFileClassifier.SKIP_EXCLUDED,
				classifyOneBranchFile("target/classes/Order.class", "abc123").skipReason());
	}

	@Test
	void keepsBranchFilesInTheOrderGitHubListedThem() {
		// The whole-branch file ceiling keeps the first N in this order, so a stable listing is what makes
		// re-triggering resume the same slice rather than an arbitrary different one.
		var paths = List.of("z/ZService.java", "a/AService.java", "m/MService.java");
		var files = paths.stream().map(path -> new BranchFile(path, "abc", 1L)).toList();

		var classified = classifier.classifyBranchFiles(files);

		assertEquals(paths, classified.stream().map(entry -> entry.file().path()).toList());
	}

	@Test
	void handlesAnEmptyBranch() {
		assertTrue(classifier.classifyBranchFiles(List.of()).isEmpty());
		assertTrue(classifier.classifyBranchFiles(null).isEmpty());
	}

	@Test
	void neverLeavesAContentHashNull() {
		for (ClassifiedFile result : classifier.classifyBranchFiles(List.of(
				new BranchFile("a/A.java", "abc123", 1L),
				new BranchFile("web/node_modules/x.js", null, 1L),
				new BranchFile("Makefile", "", 1L)))) {
			assertNotNull(result.contentHash());
		}
	}

	// ------------------------------------------------------------------ whole-branch target-role whitelist

	@Test
	void narrowsAWholeBranchListingToTheTargetRoles() {
		// A branch listing is the entire repository. Without this a whole-branch review would examine every
		// Vue component, migration and README in it, and the file ceiling would be spent on those.
		assertTrue(classifyOneBranchFile("src/main/java/com/x/OrderService.java", "abc").reviewable());
		assertTrue(classifyOneBranchFile("src/main/java/com/x/OrderServiceImpl.java", "abc").reviewable());
		assertTrue(classifyOneBranchFile("src/main/java/com/x/OrderController.java", "abc").reviewable());
		assertTrue(classifyOneBranchFile("src/main/java/com/x/OrderRestController.java", "abc").reviewable());
		assertTrue(classifyOneBranchFile("src/main/java/com/x/OrderEntity.java", "abc").reviewable());

		assertEquals(ChangedFileClassifier.SKIP_NOT_TARGET_ROLE,
				classifyOneBranchFile("src/main/java/com/x/OrderProcessor.java", "abc").skipReason());
		assertEquals(ChangedFileClassifier.SKIP_NOT_TARGET_ROLE,
				classifyOneBranchFile("web/src/app.component.ts", "abc").skipReason());
		assertEquals(ChangedFileClassifier.SKIP_NOT_TARGET_ROLE,
				classifyOneBranchFile("db/migrations/V1__init.sql", "abc").skipReason());
		assertEquals(ChangedFileClassifier.SKIP_NOT_TARGET_ROLE, classifyOneBranchFile("README.md", "abc")
				.skipReason());
	}

	@Test
	void examinesEveryFileUnderARoleDirectoryWhateverItsLanguage() {
		// The directory tier is not gated on .java: "all files in the service folder" means all of them, so a
		// helper or a query that lives beside the services is reviewed too.
		assertTrue(classifyOneBranchFile("src/main/java/com/x/service/OrderProcessor.java", "abc").reviewable());
		assertTrue(classifyOneBranchFile("src/main/java/com/x/controller/OrderApi.java", "abc").reviewable());
		assertTrue(classifyOneBranchFile("src/main/java/com/x/entity/Order.java", "abc").reviewable());
		assertTrue(classifyOneBranchFile("web/src/services/order-client.ts", "abc").reviewable());
	}

	@Test
	void doesNotScanAnnotationsWhenNothingHasBeenFetched() {
		var path = "src/main/java/com/x/OrderProcessor.java";

		// The deliberate false negative: this file is a @Service, and a commit run would examine it because
		// the annotation tier reads content the pipeline has already fetched. A branch run would have to
		// fetch every Java file that missed both pattern tiers to find out, which is the cost the ceiling
		// exists to prevent.
		assertEquals(ChangedFileClassifier.SKIP_NOT_TARGET_ROLE, classifyOneBranchFile(path, "abc").skipReason());
		assertTrue(new JavaRoleFilter(properties).isExamined(path, "@Service public class OrderProcessor { }"));
	}

	@Test
	void keepsExclusionAheadOfTheRoleWhitelist() {
		// Both apply; the exclusion is the reason a reviewer would act on.
		assertEquals(ChangedFileClassifier.SKIP_EXCLUDED,
				classifyOneBranchFile("target/classes/service/OrderService.java", "abc").skipReason());
	}

	@Test
	void examinesEveryBranchFileWhenTheRoleFilterIsDisabled() {
		properties.getJavaFilter().setEnabled(false);

		var classified = newClassifier(properties).classifyBranchFiles(List.of(
				new BranchFile("README.md", "abc", 1L),
				new BranchFile("web/src/app.component.ts", "abc", 1L),
				new BranchFile("src/main/java/com/x/OrderProcessor.java", "abc", 1L)));

		assertTrue(classified.stream().allMatch(ClassifiedFile::reviewable));
	}

	@Test
	void leavesTheRoleWhitelistAloneForACommitBasedRun() {
		// A commit's file list is already short and already interesting — someone changed those files — so
		// classify must not narrow it. The role question for a commit is settled later by isExamined, on
		// content that has been fetched anyway, and that keeps the annotation tier available.
		assertTrue(classifyOne("README.md", "modified", "@@ p @@").reviewable());
		assertTrue(classifyOne("db/migrations/V1__init.sql", "added", "@@ p @@").reviewable());
		assertTrue(classifyOne("src/main/java/com/x/OrderProcessor.java", "modified", "@@ p @@").reviewable());
	}

	@Test
	void aFileOutsideTheTargetRolesCostsNothingAgainstTheCeiling() {
		properties.getBranchReview().setMaxFiles(2);

		var classified = classifier.classifyBranchFiles(List.of(
				branchFile("README.md"), branchFile("a/OneService.java"),
				branchFile("web/src/app.component.ts"), branchFile("b/TwoService.java"),
				branchFile("c/ThreeService.java")));

		// The whitelist runs before the ceiling, so the budget is spent on files that will actually be
		// reviewed rather than on whatever git listed first.
		assertEquals(ChangedFileClassifier.SKIP_NOT_TARGET_ROLE, classified.get(0).skipReason());
		assertTrue(classified.get(1).reviewable());
		assertEquals(ChangedFileClassifier.SKIP_NOT_TARGET_ROLE, classified.get(2).skipReason());
		assertTrue(classified.get(3).reviewable());
		assertEquals(ChangedFileClassifier.SKIP_BRANCH_FILE_LIMIT, classified.get(4).skipReason());
	}

	// ------------------------------------------------------------------ whole-branch file ceiling

	private static BranchFile branchFile(String path) {
		return new BranchFile(path, "abc123", 1L);
	}

	@Test
	void marksFilesPastTheBranchCeilingRatherThanDroppingThem() {
		properties.getBranchReview().setMaxFiles(2);

		var classified = classifier.classifyBranchFiles(List.of(
				branchFile("a/OneService.java"), branchFile("b/TwoService.java"),
				branchFile("c/ThreeService.java"), branchFile("d/FourService.java")));

		// All four are still reported: a file the ceiling rejected must be distinguishable from a file the
		// listing never mentioned.
		assertEquals(4, classified.size());
		assertTrue(classified.get(0).reviewable());
		assertTrue(classified.get(1).reviewable());
		assertEquals(ChangedFileClassifier.SKIP_BRANCH_FILE_LIMIT, classified.get(2).skipReason());
		assertEquals(ChangedFileClassifier.SKIP_BRANCH_FILE_LIMIT, classified.get(3).skipReason());
	}

	@Test
	void countsOnlyExaminableFilesTowardTheBranchCeiling() {
		properties.getBranchReview().setMaxFiles(2);

		var classified = classifier.classifyBranchFiles(List.of(
				branchFile("web/node_modules/skip.js"), branchFile("a/OneService.java"),
				branchFile("target/skip.class"), branchFile("b/TwoService.java"),
				branchFile("c/ThreeService.java")));

		// An excluded file costs nothing against the ceiling, so the two real files still fit and only the
		// third is over it.
		assertEquals(ChangedFileClassifier.SKIP_EXCLUDED, classified.get(0).skipReason());
		assertTrue(classified.get(1).reviewable());
		assertEquals(ChangedFileClassifier.SKIP_EXCLUDED, classified.get(2).skipReason());
		assertTrue(classified.get(3).reviewable());
		assertEquals(ChangedFileClassifier.SKIP_BRANCH_FILE_LIMIT, classified.get(4).skipReason());
	}

	@Test
	void examinesEveryRoleFileWhenTheCeilingIsNotPositive() {
		properties.getBranchReview().setMaxFiles(0);

		var classified = classifier.classifyBranchFiles(List.of(
				branchFile("a/OneService.java"), branchFile("b/TwoService.java"),
				branchFile("c/ThreeService.java")));

		assertTrue(classified.stream().allMatch(ClassifiedFile::reviewable));
	}

	@Test
	void leavesTheCeilingAloneForACommitBasedRun() {
		// A commit's file list is already bounded by what that commit touched, so the branch ceiling must
		// not reach into it.
		properties.getBranchReview().setMaxFiles(1);

		var classified = classifier.classify(List.of(
				file("a/OneService.java", "modified", "@@ p @@"),
				file("b/TwoService.java", "modified", "@@ p @@"),
				file("c/ThreeService.java", "modified", "@@ p @@")));

		assertTrue(classified.stream().allMatch(ClassifiedFile::reviewable));
	}
}
