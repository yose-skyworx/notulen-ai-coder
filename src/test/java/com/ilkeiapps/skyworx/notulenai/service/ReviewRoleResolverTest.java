package com.ilkeiapps.skyworx.notulenai.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;

/**
 * Covers which files the review narrows itself to. Every case here is a decision that changes what a run
 * examines, and the interesting ones are the tier boundaries: a filter that quietly falls through to the
 * annotation scan when a pattern should have matched still returns the right answer, so the tiers have to
 * be pinned individually rather than only by outcome.
 */
class JavaRoleFilterTest {

	private final CodeReviewProperties properties = new CodeReviewProperties();
	private final JavaRoleFilter filter = new JavaRoleFilter(properties);

	/** A path outside every default directory pattern, so only the filename or the content can match. */
	private static final String NEUTRAL_DIR = "src/main/java/com/example/report/";

	private static String noAnnotations() {
		return "package com.example.report;\n\npublic class OrderHandler {\n}\n";
	}

	@Test
	void leavesFilesOfOtherLanguagesAlone() {
		// The filter narrows Java; a SQL migration or a Vue component is not its business.
		assertTrue(filter.isExamined("db/migration/V12__add_orders.sql", "create table orders ();"));
		assertTrue(filter.isExamined("web/src/App.vue", "<template></template>"));
		assertTrue(filter.isExamined("src/main/resources/application.yml", "spring:"));
		assertTrue(filter.isExamined("Dockerfile", "FROM eclipse-temurin:25"));
		assertTrue(filter.isExamined("README.md", "# title"));
	}

	@Test
	void acceptsAJavaFileWhoseNameMatchesWithoutConsultingItsContent() {
		// Content carries no annotation at all, so a fall-through to tier three would reject these.
		assertTrue(filter.isExamined(NEUTRAL_DIR + "OrderService.java", noAnnotations()));
		assertTrue(filter.isExamined(NEUTRAL_DIR + "OrderController.java", noAnnotations()));
		assertTrue(filter.isExamined(NEUTRAL_DIR + "OrderEntity.java", noAnnotations()));
	}

	@Test
	void acceptsAFileAtTheRepositoryRoot() {
		// **/ has to match zero directories for a file with no package path at all.
		assertTrue(filter.isExamined("OrderService.java", noAnnotations()));
	}

	@Test
	void acceptsAJavaFileWhoseDirectoryMatchesWhenItsNameDoesNot() {
		// The JPA convention in this codebase names entities after their table, not *Entity.
		assertTrue(filter.isExamined("src/main/java/com/example/entity/Order.java", noAnnotations()));
		assertTrue(filter.isExamined("src/main/java/com/example/service/OrderHandler.java", noAnnotations()));
		assertTrue(filter.isExamined("src/main/java/com/example/controller/Orders.java", noAnnotations()));
	}

	@Test
	void fallsBackToTheAnnotationWhenNeitherPatternTierMatches() {
		assertTrue(filter.isExamined(NEUTRAL_DIR + "OrderHandler.java",
				"package com.example.report;\n\n@Service\npublic class OrderHandler {\n}\n"));
		assertTrue(filter.isExamined(NEUTRAL_DIR + "OrdersApi.java",
				"package com.example.report;\n\n@RestController\npublic class OrdersApi {\n}\n"));
		assertTrue(filter.isExamined(NEUTRAL_DIR + "Order.java",
				"package com.example.report;\n\n@Entity\npublic class Order {\n}\n"));
	}

	@Test
	void rejectsAJavaFileThatMatchesNoTier() {
		assertFalse(filter.isExamined(NEUTRAL_DIR + "OrderHandler.java", noAnnotations()));
		assertFalse(filter.isExamined(NEUTRAL_DIR + "OrderMapper.java",
				"package com.example.report;\n\n@Mapper\npublic interface OrderMapper {\n}\n"));
	}

	@Test
	void matchesAnnotationsOnAWholeTokenBoundary() {
		// @ControllerAdvice is an exception handler, not a controller; @ServiceLevel is not a stereotype.
		assertFalse(filter.isExamined(NEUTRAL_DIR + "GlobalHandler.java",
				"package com.example.report;\n\n@RestControllerAdvice\npublic class GlobalHandler {\n}\n"));
		assertFalse(filter.isExamined(NEUTRAL_DIR + "ServiceLevels.java",
				"package com.example.report;\n\npublic enum ServiceLevels { @ServiceLevel LOW }\n"));
	}

	@Test
	void keepsScanningPastANearMiss() {
		// The first occurrence is a prefix of a longer identifier; the real one follows it.
		assertTrue(filter.isExamined(NEUTRAL_DIR + "OrderHandler.java",
				"package com.example.report;\n\n// @Servicing the queue\n@Service\npublic class OrderHandler {\n}\n"));
	}

	@Test
	void matchesAnAnnotationAtTheVeryEndOfTheContent() {
		assertTrue(filter.isExamined(NEUTRAL_DIR + "OrderHandler.java", "public class OrderHandler {} @Service"));
	}

	@Test
	void countsAnAnnotationMentionedInACommentBecauseItScansTextNotSyntax() {
		// Documented bias: a false positive costs one review, a false negative silently drops a file the
		// user asked to have examined. Parsing Java properly would need a parser for a noise filter.
		assertTrue(filter.isExamined(NEUTRAL_DIR + "OrderHandler.java",
				"package com.example.report;\n\n// TODO split this into a @Service\npublic class OrderHandler {\n}\n"));
	}

	@Test
	void treatsAbsentContentAsNoAnnotationMatch() {
		assertFalse(filter.isExamined(NEUTRAL_DIR + "OrderHandler.java", null));
		assertFalse(filter.isExamined(NEUTRAL_DIR + "OrderHandler.java", ""));
	}

	@Test
	void examinesEverythingWhenDisabled() {
		properties.getJavaFilter().setEnabled(false);
		assertTrue(filter.isExamined(NEUTRAL_DIR + "OrderHandler.java", noAnnotations()));
	}

	@Test
	void honoursReplacedPatternsRatherThanAddingToTheDefaults() {
		var javaFilter = properties.getJavaFilter();
		javaFilter.setFilenamePatterns(List.of("**/*Handler.java"));
		javaFilter.setPathPatterns(List.of());
		javaFilter.setAnnotations(List.of());

		assertTrue(filter.isExamined(NEUTRAL_DIR + "OrderHandler.java", noAnnotations()));
		// The default **/*Service.java no longer applies, and there is no annotation tier left to catch it.
		assertFalse(filter.isExamined(NEUTRAL_DIR + "OrderService.java",
				"package com.example.report;\n\n@Service\npublic class OrderService {\n}\n"));
	}

	@Test
	void ignoresBlankPatternEntries() {
		var javaFilter = properties.getJavaFilter();
		javaFilter.setFilenamePatterns(List.of("", "  ", "**/*Service.java"));
		javaFilter.setAnnotations(List.of("", "@Service"));

		assertTrue(filter.isExamined(NEUTRAL_DIR + "OrderService.java", noAnnotations()));
		assertTrue(filter.isExamined(NEUTRAL_DIR + "OrderHandler.java",
				"package com.example.report;\n\n@Service\npublic class OrderHandler {\n}\n"));
	}

	// ------------------------------------------------------------------ path-only whitelist

	@Test
	void whitelistsOnlyTheTargetRolesForAWholeBranchListing() {
		assertTrue(filter.isTargetByPath(NEUTRAL_DIR + "OrderService.java"));
		assertTrue(filter.isTargetByPath(NEUTRAL_DIR + "OrderController.java"));
		assertTrue(filter.isTargetByPath("src/main/java/com/example/entity/Order.java"));
		assertTrue(filter.isTargetByPath("OrderService.java"));

		assertFalse(filter.isTargetByPath(NEUTRAL_DIR + "OrderHandler.java"));
		// Unlike isExamined this is a whitelist over the whole tree, so the other languages are its business:
		// a branch listing holds every file in the repository, not just the ones someone changed.
		assertFalse(filter.isTargetByPath("db/migration/V12__add_orders.sql"));
		assertFalse(filter.isTargetByPath("web/src/App.vue"));
		assertFalse(filter.isTargetByPath("README.md"));
	}

	@Test
	void doesNotFallBackToTheAnnotationScanForAWholeBranchListing() {
		var path = NEUTRAL_DIR + "OrderHandler.java";
		var content = "package com.example.report;\n\n@Service\npublic class OrderHandler {\n}\n";

		// The same file, two answers, and both are correct for their strategy: a commit run has already paid
		// for the fetch, a branch run would have to pay it for every Java file that missed both patterns.
		assertTrue(filter.isExamined(path, content));
		assertFalse(filter.isTargetByPath(path));
	}

	@Test
	void examinesEverythingForAWholeBranchListingWhenTheFilterIsDisabled() {
		properties.getJavaFilter().setEnabled(false);

		assertTrue(filter.isTargetByPath("README.md"));
		assertTrue(filter.isTargetByPath(NEUTRAL_DIR + "OrderHandler.java"));
	}

	@Test
	void honoursReplacedPatternsForAWholeBranchListing() {
		var javaFilter = properties.getJavaFilter();
		javaFilter.setFilenamePatterns(List.of("**/*Handler.java"));
		javaFilter.setPathPatterns(List.of());

		assertTrue(filter.isTargetByPath(NEUTRAL_DIR + "OrderHandler.java"));
		assertFalse(filter.isTargetByPath(NEUTRAL_DIR + "OrderService.java"));
	}
}
