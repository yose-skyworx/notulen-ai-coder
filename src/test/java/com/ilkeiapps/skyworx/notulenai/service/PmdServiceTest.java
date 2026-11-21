package com.ilkeiapps.skyworx.notulenai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ilkeiapps.skyworx.notulenai.service.PmdService.PmdViolation;

/**
 * Covers the {@code PMD} review mechanism end to end, against real source text rather than a fixture AST.
 * <p>
 * The interesting cases are not the violation but the two ways this rule can be <em>wrong</em>: flagging a
 * method-level {@code @Transactional}, which is exactly what AGENTS.md asks for, and flagging an unrelated
 * annotation that happens to share the simple name {@code Transactional}. Both would be silent in
 * production — the first as noise nobody trusts, the second as a finding that sends someone to fix code
 * that was never wrong.
 * <p>
 * Also pinned here: that PMD parses Java 25 source. The whole mechanism rests on it, PMD's supported
 * language levels move with its releases, and a parser that quietly gave up on a file would report zero
 * violations and look like a clean review.
 */
class PmdServiceTest {

	private static final String RULE = "ClassLevelTransactional";

	private final PmdService pmd = new PmdService();

	private List<PmdViolation> analyse(String source) {
		return pmd.analyse("src/main/java/com/example/Subject.java", source);
	}

	@Test
	void flagsTransactionalOnTheClass() {
		var found = analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;
				import org.springframework.stereotype.Service;

				@Service
				@Transactional
				public class Subject {
					public void save() { }
				}
				""");

		assertEquals(1, found.size());
		// The class declaration line, not the annotation's — which is the line a reviewer needs, since
		// that is where the fix goes.
		assertEquals(8, found.getFirst().beginLine());
		assertEquals(8, found.getFirst().endLine());
		assertEquals(RULE, found.getFirst().ruleName());
	}

	/** The false positive that would destroy trust in the rule: method-level is what AGENTS.md wants. */
	@Test
	void doesNotFlagTransactionalOnAMethod() {
		var found = analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;

				public class Subject {
					@Transactional
					public void save() { }

					@Transactional(readOnly = true)
					public void find() { }
				}
				""");

		assertTrue(found.isEmpty(), "method-level @Transactional is correct and must not be flagged: " + found);
	}

	@Test
	void doesNotFlagAClassWithNoTransactionAnnotation() {
		assertTrue(analyse("""
				package com.example;

				public class Subject {
					public void run() { }
				}
				""").isEmpty());
	}

	/**
	 * The second false positive: a project's own {@code Transactional} that is not Spring's. Matching on
	 * the simple name would flag this; resolving the fully-qualified name does not.
	 */
	@Test
	void doesNotFlagAnUnrelatedAnnotationSharingTheSimpleName() {
		var found = analyse("""
				package com.example;

				import com.example.own.Transactional;

				@Transactional
				public class Subject {
					public void save() { }
				}
				""");

		assertTrue(found.isEmpty(), "com.example.own.Transactional is not a Spring annotation: " + found);
	}

	@Test
	void flagsTheJakartaVariantToo() {
		var found = analyse("""
				package com.example;

				import jakarta.transaction.Transactional;

				@Transactional
				public class Subject {
					public void save() { }
				}
				""");

		assertEquals(1, found.size());
	}

	/** {@code ClassDeclaration} alone covers classes and interfaces but not enums or records. */
	@Test
	void coversEveryKindOfTypeDeclaration() {
		var found = analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;

				@Transactional
				enum AnEnum { ONE }

				@Transactional
				record ARecord(long id) { }

				@Transactional
				interface AnInterface { void save(); }
				""");

		assertEquals(3, found.size());
		assertEquals(List.of(6, 9, 12), found.stream().map(PmdViolation::beginLine).toList());
	}

	@Test
	void flagsANestedClass() {
		var found = analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;

				public class Subject {
					@Transactional
					static class Inner {
						void save() { }
					}
				}
				""");

		assertEquals(1, found.size());
		assertEquals(7, found.getFirst().beginLine());
	}

	/** Resolves the annotation whether it was imported, star-imported, or written out in full. */
	@Test
	void flagsItHoweverTheAnnotationWasImported() {
		assertEquals(1, analyse("""
				package com.example;

				import org.springframework.transaction.annotation.*;

				@Transactional
				public class Subject { }
				""").size());

		assertEquals(1, analyse("""
				package com.example;

				@org.springframework.transaction.annotation.Transactional
				public class Subject { }
				""").size());
	}

	/**
	 * Records patterns, sealed classes, an unnamed variable and {@code case null} in one file. If a future
	 * PMD release drops support for the project's language level this is what fails first.
	 */
	@Test
	void parsesJava25Source() {
		var found = analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;

				@Transactional
				public sealed class Subject permits Subject.Inner {

					public record Order(long id, String sku) {
						public Order {
							if (id < 0) throw new IllegalArgumentException("bad id");
						}
					}

					public static final class Inner extends Subject { }

					public String describe(Object o) {
						if (o instanceof Order(long id, var sku)) {
							return id + "/" + sku;
						}
						var _ = o.hashCode();
						return switch (o) {
							case String s -> s;
							case null, default -> "other";
						};
					}
				}
				""");

		assertEquals(1, found.size(), "Java 25 source must parse, or the rule silently never fires");
		assertEquals(6, found.getFirst().beginLine());
	}

	/** PMD selects the language from the extension and rejects anything else; this mechanism is Java-only. */
	@Test
	void doesNotAnalyseANonJavaPath() {
		assertTrue(pmd.analyse("src/main/resources/db/schema.sql", "@Transactional SELECT 1;").isEmpty());
		assertTrue(pmd.analyse("README", "@Transactional").isEmpty());
	}

	@Test
	void returnsNothingForBlankSource() {
		assertTrue(pmd.analyse("src/main/java/com/example/Empty.java", "").isEmpty());
		assertTrue(pmd.analyse("src/main/java/com/example/Empty.java", "   ").isEmpty());
	}

	// ----------------------------------------------------------- NonAdvisableTransactionalMethod (Rule 2)

	private static final String RULE_METHOD = "NonAdvisableTransactionalMethod";

	private static List<PmdViolation> only(List<PmdViolation> all, String ruleName) {
		return all.stream().filter(v -> v.ruleName().equals(ruleName)).toList();
	}

	/** The correct placement AGENTS.md asks for: public method with a body. Must stay clean. */
	@Test
	void doesNotFlagAPublicTransactionalMethod() {
		var found = only(analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;

				public class Subject {
					@Transactional
					public void save() { }

					@Transactional(readOnly = true)
					public void find() { }
				}
				"""), RULE_METHOD);

		assertTrue(found.isEmpty(), "public method-level @Transactional is correct: " + found);
	}

	@Test
	void flagsATransactionalPrivateMethod() {
		var found = only(analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;

				public class Subject {
					@Transactional
					private void save() { }
				}
				"""), RULE_METHOD);

		assertEquals(1, found.size());
		// Reported on the method declaration line, where the fix goes.
		assertEquals(7, found.getFirst().beginLine());
	}

	@Test
	void flagsATransactionalPackagePrivateAndProtectedMethod() {
		assertEquals(1, only(analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;

				public class Subject {
					@Transactional
					void save() { }
				}
				"""), RULE_METHOD).size());

		assertEquals(1, only(analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;

				public class Subject {
					@Transactional
					protected void save() { }
				}
				"""), RULE_METHOD).size());
	}

	/** No body for the proxy to wrap: an interface method's @Transactional is inert. */
	@Test
	void flagsATransactionalInterfaceMethod() {
		var found = only(analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;

				public interface Subject {
					@Transactional
					void save();
				}
				"""), RULE_METHOD);

		assertEquals(1, found.size());
	}

	/**
	 * The class-level case belongs to {@code ClassLevelTransactional} alone. Rule 2 must not also fire on it,
	 * or every class-level annotation would be reported twice.
	 */
	@Test
	void doesNotDoubleReportClassLevelTransactional() {
		var all = analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;

				@Transactional
				public class Subject {
					public void save() { }
				}
				""");

		assertEquals(1, only(all, RULE).size(), "class-level is the class rule's finding");
		assertTrue(only(all, RULE_METHOD).isEmpty(), "the method rule must not fire on a type: " + all);
	}

	// --------------------------------------------------------------- NoRestCallInTransaction (Rule 3)

	private static final String RULE_REST = "NoRestCallInTransaction";

	@Test
	void flagsARestClientCallInATransactionalMethod() {
		var found = only(analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;
				import org.springframework.web.client.RestClient;

				public class Subject {
					private RestClient restClient;

					@Transactional
					public void save() {
						restClient.post().uri("/x").retrieve().body(String.class);
					}
				}
				"""), RULE_REST);

		assertEquals(1, found.size(), "the whole fluent chain is one violation, at the client call: " + found);
		// The restClient.post() line, not the method and not the chained calls after it.
		assertEquals(11, found.getFirst().beginLine());
	}

	@Test
	void flagsRestTemplateAndWebClientToo() {
		assertEquals(1, only(analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;
				import org.springframework.web.client.RestTemplate;

				public class Subject {
					private RestTemplate restTemplate;

					@Transactional
					public void save() {
						restTemplate.getForObject("/x", String.class);
					}
				}
				"""), RULE_REST).size());

		// WebClient (spring-webflux) is not on this project's classpath, so it exercises name-only
		// resolution from the import — the case the rule must still catch.
		assertEquals(1, only(analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;
				import org.springframework.web.reactive.function.client.WebClient;

				public class Subject {
					private WebClient webClient;

					@Transactional
					public void save() {
						webClient.get().uri("/x").retrieve().bodyToMono(String.class).block();
					}
				}
				"""), RULE_REST).size());
	}

	/** The false positive that would sink the rule: an unrelated object with a post()/get() method. */
	@Test
	void doesNotFlagAnUnrelatedObjectWithTheSameMethodNames() {
		var found = only(analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;

				public class Subject {
					private PaymentRepository repository;

					@Transactional
					public void save() {
						repository.post();
						repository.get();
					}
				}

				class PaymentRepository {
					void post() { }
					void get() { }
				}
				"""), RULE_REST);

		assertTrue(found.isEmpty(), "post()/get() on a non-client type must not be flagged: " + found);
	}

	@Test
	void flagsRestClientCallsNestedInControlFlowAndLambdas() {
		var found = only(analyse("""
				package com.example;

				import java.util.List;
				import org.springframework.transaction.annotation.Transactional;
				import org.springframework.web.client.RestClient;

				public class Subject {
					private RestClient restClient;

					@Transactional
					public void save(boolean flag, List<String> items) {
						if (flag) {
							restClient.get().retrieve();
						}
						for (String item : items) {
							restClient.post().retrieve();
						}
						try {
							restClient.put().retrieve();
						} catch (RuntimeException e) {
							restClient.delete().retrieve();
						}
						items.forEach(item -> restClient.head().retrieve());
						Runnable r = () -> restClient.options().retrieve();
						r.run();
					}
				}
				"""), RULE_REST);

		assertEquals(6, found.size(), "if, for, try, catch, and both lambda calls are reached: " + found);
	}

	/**
	 * A client call inside an anonymous class declared in the method is out of scope: that {@code run()} body
	 * executes in a different context (here, whenever the task is run), not as part of the transaction, so it
	 * must not be attributed to the transactional method.
	 */
	@Test
	void doesNotFlagARestClientCallInsideANestedAnonymousClassMethod() {
		var found = only(analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;
				import org.springframework.web.client.RestClient;

				public class Subject {
					private RestClient restClient;

					@Transactional
					public void save() {
						Runnable task = new Runnable() {
							@Override
							public void run() {
								restClient.post().retrieve();
							}
						};
						task.run();
					}
				}
				"""), RULE_REST);

		assertTrue(found.isEmpty(), "a call in a nested class's own method is deferred and out of scope: " + found);
	}

	@Test
	void doesNotFlagARestClientCallOutsideAnyTransaction() {
		var found = only(analyse("""
				package com.example;

				import org.springframework.web.client.RestClient;

				public class Subject {
					private RestClient restClient;

					public void save() {
						restClient.post().retrieve();
					}
				}
				"""), RULE_REST);

		assertTrue(found.isEmpty(), "no @Transactional, so the rule does not apply: " + found);
	}

	@Test
	void flagsAFullyQualifiedRestClientFieldType() {
		var found = only(analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;

				public class Subject {
					private org.springframework.web.client.RestClient restClient;

					@Transactional
					public void save() {
						restClient.post().retrieve();
					}
				}
				"""), RULE_REST);

		assertEquals(1, found.size());
	}

	/**
	 * An unresolved receiver type must not be reported. {@code mystery} has no declaration PMD can resolve,
	 * so the rule cannot confirm it is a client and stays silent rather than guessing.
	 */
	@Test
	void doesNotFlagAnUnresolvedReceiverType() {
		var found = only(analyse("""
				package com.example;

				import org.springframework.transaction.annotation.Transactional;

				public class Subject {
					@Transactional
					public void save() {
						mystery.post().retrieve();
					}
				}
				"""), RULE_REST);

		assertTrue(found.isEmpty(), "an unresolved receiver is never a confirmed violation: " + found);
	}
}
