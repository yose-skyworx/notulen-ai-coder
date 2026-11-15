package com.ilkeiapps.skyworx.notulenai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewRoleType;
import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;

/**
 * Covers which review role a file resolves to. The role is what selects the guideline a run judges the
 * file against, so every case here changes what standard gets applied — and the interesting ones are the
 * tier boundaries. A resolver that quietly falls through to the annotation scan when a pattern should have
 * matched can still return a role, so the tiers have to be pinned individually rather than only by
 * outcome.
 */
class ReviewRoleResolverTest {

	private final CodeReviewProperties properties = new CodeReviewProperties();
	private final ReviewRoleResolver resolver = new ReviewRoleResolver(properties);

	/** A path outside every default directory pattern, so only the filename or the content can match. */
	private static final String NEUTRAL_DIR = "src/main/java/com/example/report/";

	private static String noAnnotations() {
		return "package com.example.report;\n\npublic class OrderHandler {\n}\n";
	}

	// ------------------------------------------------------------------ the two pattern tiers

	@Test
	void resolvesTheRoleFromTheFilenameWithoutConsultingContent() {
		// Content carries no annotation at all, so a fall-through to tier three would resolve nothing.
		assertEquals(ReviewRoleType.SERVICE, resolver.roleOf(NEUTRAL_DIR + "OrderService.java", noAnnotations()));
		assertEquals(ReviewRoleType.SERVICE, resolver.roleOf(NEUTRAL_DIR + "OrderServiceImpl.java", noAnnotations()));
		assertEquals(ReviewRoleType.CONTROLLER, resolver.roleOf(NEUTRAL_DIR + "OrderController.java", noAnnotations()));
		assertEquals(ReviewRoleType.CONTROLLER,
				resolver.roleOf(NEUTRAL_DIR + "OrderRestController.java", noAnnotations()));
		assertEquals(ReviewRoleType.ENTITY, resolver.roleOf(NEUTRAL_DIR + "OrderEntity.java", noAnnotations()));
	}

	@Test
	void resolvesAFileAtTheRepositoryRoot() {
		// The leading wildcard has to match zero directories for a file with no package path at all.
		assertEquals(ReviewRoleType.SERVICE, resolver.roleOfPath("OrderService.java"));
	}

	@Test
	void resolvesTheRoleFromTheDirectoryWhenTheFilenameDoesNot() {
		// The JPA convention in this codebase names entities after their table, not *Entity.
		assertEquals(ReviewRoleType.ENTITY, resolver.roleOfPath("src/main/java/com/example/entity/Order.java"));
		assertEquals(ReviewRoleType.SERVICE, resolver.roleOfPath("src/main/java/com/example/service/OrderHandler.java"));
		assertEquals(ReviewRoleType.CONTROLLER, resolver.roleOfPath("src/main/java/com/example/controller/Orders.java"));
	}

	@Test
	void letsTheFilenameTierBeatTheDirectoryTier() {
		// The tier beats the role: this file sits in an entity package, but its name is the more specific
		// claim. Consulting one role's three tiers before moving to the next role would answer ENTITY here.
		assertEquals(ReviewRoleType.SERVICE, resolver.roleOfPath("src/main/java/com/example/entity/OrderService.java"));
		assertEquals(ReviewRoleType.CONTROLLER,
				resolver.roleOfPath("src/main/java/com/example/service/OrdersController.java"));
	}

	@Test
	void breaksATieWithinOneTierByDeclarationOrder() {
		// Both SERVICE and CONTROLLER directory patterns match this path, and the filename matches neither.
		// ReviewRoleType declares SERVICE first, so SERVICE wins — which is behaviour, not an accident of
		// how a map happened to iterate.
		assertEquals(ReviewRoleType.SERVICE,
				resolver.roleOfPath("src/main/java/com/example/service/controller/Orders.java"));
	}

	@Test
	void reachesEveryLanguageUnderARoleDirectory() {
		// The directory tier is not gated on .java, deliberately: a migration or a query file living beside
		// the services is part of that role. A file of any language OUTSIDE those directories resolves to
		// nothing.
		assertEquals(ReviewRoleType.SERVICE, resolver.roleOfPath("src/main/resources/service/queries.sql"));
		assertEquals(ReviewRoleType.CONTROLLER, resolver.roleOfPath("web/src/controllers/orders.ts"));
		assertNull(resolver.roleOfPath("db/migration/V12__add_orders.sql"));
		assertNull(resolver.roleOfPath("web/src/App.vue"));
		assertNull(resolver.roleOfPath("src/main/resources/application.yml"));
		assertNull(resolver.roleOfPath("Dockerfile"));
		assertNull(resolver.roleOfPath("README.md"));
	}

	@Test
	void returnsNoRoleForAMissingPath() {
		assertNull(resolver.roleOfPath(null));
		assertNull(resolver.roleOf(null, "@Service public class OrderHandler { }"));
	}

	// ------------------------------------------------------------------ the annotation tier

	@Test
	void fallsBackToTheAnnotationWhenNeitherPatternTierMatches() {
		assertEquals(ReviewRoleType.SERVICE, resolver.roleOf(NEUTRAL_DIR + "OrderHandler.java",
				"package com.example.report;\n\n@Service\npublic class OrderHandler {\n}\n"));
		assertEquals(ReviewRoleType.CONTROLLER, resolver.roleOf(NEUTRAL_DIR + "OrdersApi.java",
				"package com.example.report;\n\n@RestController\npublic class OrdersApi {\n}\n"));
		assertEquals(ReviewRoleType.ENTITY, resolver.roleOf(NEUTRAL_DIR + "Order.java",
				"package com.example.report;\n\n@Entity\npublic class Order {\n}\n"));
	}

	@Test
	void returnsNoRoleForAJavaFileThatMatchesNoTier() {
		assertNull(resolver.roleOf(NEUTRAL_DIR + "OrderHandler.java", noAnnotations()));
		assertNull(resolver.roleOf(NEUTRAL_DIR + "OrderMapper.java",
				"package com.example.report;\n\n@Mapper\npublic interface OrderMapper {\n}\n"));
	}

	@Test
	void matchesAnnotationsOnAWholeTokenBoundary() {
		// @RestControllerAdvice is an exception handler, not a controller; @ServiceLevel is not a stereotype.
		assertNull(resolver.roleOf(NEUTRAL_DIR + "GlobalHandler.java",
				"package com.example.report;\n\n@RestControllerAdvice\npublic class GlobalHandler {\n}\n"));
		assertNull(resolver.roleOf(NEUTRAL_DIR + "ServiceLevels.java",
				"package com.example.report;\n\npublic enum ServiceLevels { @ServiceLevel LOW }\n"));
	}

	@Test
	void keepsScanningPastANearMiss() {
		// The first occurrence is a prefix of a longer identifier; the real one follows it.
		assertEquals(ReviewRoleType.SERVICE, resolver.roleOf(NEUTRAL_DIR + "OrderHandler.java",
				"package com.example.report;\n\n// @Servicing the queue\n@Service\npublic class OrderHandler {\n}\n"));
	}

	@Test
	void matchesAnAnnotationAtTheVeryEndOfTheContent() {
		assertEquals(ReviewRoleType.SERVICE,
				resolver.roleOf(NEUTRAL_DIR + "OrderHandler.java", "public class OrderHandler {} @Service"));
	}

	@Test
	void countsAnAnnotationMentionedInACommentBecauseItScansTextNotSyntax() {
		// Documented bias: a false positive costs one review, a false negative silently drops a file the
		// user asked to have examined. Parsing Java properly would need a parser for a noise filter.
		assertEquals(ReviewRoleType.SERVICE, resolver.roleOf(NEUTRAL_DIR + "OrderHandler.java",
				"package com.example.report;\n\n// TODO split this into a @Service\npublic class OrderHandler {\n}\n"));
	}

	@Test
	void treatsAbsentContentAsNoAnnotationMatch() {
		assertNull(resolver.roleOf(NEUTRAL_DIR + "OrderHandler.java", null));
		assertNull(resolver.roleOf(NEUTRAL_DIR + "OrderHandler.java", ""));
	}

	@Test
	void neverScansAnnotationsInANonJavaFile() {
		// Scanning a SQL or markdown file for a Java stereotype would only ever produce a false positive.
		assertNull(resolver.roleOf("db/migration/V12__add_orders.sql", "-- @Service\ncreate table orders ();"));
		assertNull(resolver.roleOf("README.md", "# Services\n@Entity"));
	}

	// ------------------------------------------------------------------ the two entry points

	@Test
	void roleOfPathNeverConsultsContent() {
		var path = NEUTRAL_DIR + "OrderHandler.java";
		var content = "package com.example.report;\n\n@Service\npublic class OrderHandler {\n}\n";

		// The same file, two answers, and both are correct for their strategy: a commit run has already paid
		// for the fetch, a branch run would have to pay it for every Java file that missed both patterns.
		assertEquals(ReviewRoleType.SERVICE, resolver.roleOf(path, content));
		assertNull(resolver.roleOfPath(path));
	}

	// ------------------------------------------------------------------ configuration

	@Test
	void honoursReplacedPatternsRatherThanAddingToTheDefaults() {
		var service = properties.getJavaFilter().getService();
		service.setFilenamePatterns(List.of("**/*Handler.java"));
		service.setPathPatterns(List.of());
		service.setAnnotations(List.of());

		assertEquals(ReviewRoleType.SERVICE, resolver.roleOf(NEUTRAL_DIR + "OrderHandler.java", noAnnotations()));
		// The default filename pattern no longer applies, and this role has no annotation tier left to
		// catch it. No other role claims the name either.
		assertNull(resolver.roleOf(NEUTRAL_DIR + "OrderService.java",
				"package com.example.report;\n\n@Service\npublic class OrderService {\n}\n"));
	}

	@Test
	void emptyingAllThreeOfARolesListsTurnsThatRoleOff() {
		var entity = properties.getJavaFilter().getEntity();
		entity.setFilenamePatterns(List.of());
		entity.setPathPatterns(List.of());
		entity.setAnnotations(List.of());

		assertNull(resolver.roleOfPath(NEUTRAL_DIR + "OrderEntity.java"));
		assertNull(resolver.roleOfPath("src/main/java/com/example/entity/Order.java"));
		assertNull(resolver.roleOf(NEUTRAL_DIR + "Order.java", "@Entity public class Order { }"));
		// Each role is configured on its own, so turning one off is not a global switch.
		assertEquals(ReviewRoleType.SERVICE, resolver.roleOfPath(NEUTRAL_DIR + "OrderService.java"));
	}

	@Test
	void ignoresBlankPatternEntries() {
		var service = properties.getJavaFilter().getService();
		service.setFilenamePatterns(List.of("", "  ", "**/*Service.java"));
		service.setAnnotations(List.of("", "@Service"));

		assertEquals(ReviewRoleType.SERVICE, resolver.roleOfPath(NEUTRAL_DIR + "OrderService.java"));
		assertEquals(ReviewRoleType.SERVICE, resolver.roleOf(NEUTRAL_DIR + "OrderHandler.java",
				"package com.example.report;\n\n@Service\npublic class OrderHandler {\n}\n"));
	}
}
