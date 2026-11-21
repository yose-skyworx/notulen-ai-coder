package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;

import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTExpression;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.ModifierOwner.Visibility;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;
import net.sourceforge.pmd.lang.java.types.TypeTestUtil;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleContext;
import net.sourceforge.pmd.reporting.RuleViolation;

import lombok.extern.slf4j.Slf4j;

/**
 * The {@code PMD} review mechanism: deterministic static analysis of source text held in memory, with no
 * model in the loop.
 * <p>
 * This is what lets the code-review module produce findings before a capable chat model exists. PMD is
 * local and reports exact lines, so its findings satisfy {@link FindingValidator} by construction rather
 * than by a model's self-assessment — the line is in range, the evidence is sliceable from the same source
 * string this was given, and confidence is {@code 1.00}. It also gives a baseline an eventual {@code LLM}
 * mechanism can be measured against.
 * <p>
 * <b>PMD is the only engine that can work here.</b> SpotBugs, Error Prone and ArchUnit all need compiled
 * bytecode or a build, and this pipeline never has one: it only ever holds source text fetched from GitHub
 * into memory. {@code PmdAnalysis} accepts exactly that through
 * {@code FileCollector#addSourceFile(FileId, String)}, so nothing is ever written to disk to be analysed.
 * <p>
 * <b>The rules live in this class for now.</b> §13 of {@code code-review-plan.md} stores each rule in its
 * own {@code code_review_guidelines} row with the PMD reference in {@code content}; until that library is
 * loaded and handed in, the rules live here so the mechanism can be proven end to end. Moving them out means
 * {@link #analyse} takes a rule set rather than using this one, and the {@code severity} starts coming from
 * the guideline row instead of from PMD's priority.
 * <p>
 * The rules express {@code AGENTS.md} conventions that no shipped PMD rule covers. What PMD's own
 * {@code category/java/*.xml} rules check is generic Java hygiene, not this project's standard, so a clean
 * run here never means "this file follows AGENTS.md" — only that the rules that were run did not fire.
 */
@Slf4j
@Service
public class PmdService {

	private static final String JAVA_EXTENSION = ".java";

	/**
	 * Built in code, not parsed from an XML rule set. {@link RuleSet#create} takes already constructed
	 * {@link net.sourceforge.pmd.lang.rule.Rule} instances, so there is no rule-set document, no
	 * {@code RuleSetLoader}, and no {@code xsi:schemaLocation} to leave off — which matters because PMD's own
	 * rule sets point that attribute at {@code pmd.sourceforge.io}, and a validating parse would put a network
	 * round trip inside what must be a local, deterministic check.
	 * <p>
	 * Constructed once and reused. A {@link RuleSet} is safe to hand to successive analyses, and none of these
	 * rules keeps state between them: everything each is set to is assigned in its constructor, and every
	 * {@code apply} receives the context to report into rather than holding one. The empty include/exclude
	 * pattern lists mean the set applies to every file handed to it; the file-name argument is a label PMD
	 * only uses in diagnostics, never a path it reads.
	 */
	private final RuleSet ruleSet = RuleSet.create("notulen-ai transaction rules",
			"AGENTS.md transaction conventions that no shipped PMD rule covers", "transaction-rules",
			List.of(), List.of(), List.of(new ClassLevelTransactionalRule(),
					new NonAdvisableTransactionalMethodRule(), new NoRestCallInTransactionRule()));

	/**
	 * One rule that fired.
	 * <p>
	 * The line numbers are 1-based offsets into the exact {@code source} string this was analysed with,
	 * which is what makes them usable by the caller: evidence is sliced from that string and
	 * {@link FindingValidator} checks the line against that same snapshot. A caller that passes a different
	 * snapshot than the one it validates against gets lines that mean nothing.
	 * <p>
	 * {@code priority} is PMD's own 1–5 scale, 1 being most severe. It is reported rather than translated
	 * because §13 gives the guideline row's {@code severity} precedence over it.
	 *
	 * @param ruleName  the rule that fired, which is what a finding cites as its {@code rule_id}
	 * @param message   the rule's message, already resolved by PMD
	 * @param beginLine first line of the offending construct, 1-based
	 * @param endLine   last line of it, 1-based
	 * @param priority  PMD priority, 1 (most severe) to 5
	 */
	public record PmdViolation(String ruleName, String message, int beginLine, int endLine, int priority) {
	}

	/**
	 * Runs the rule set over one file's source text.
	 * <p>
	 * Never throws for a file PMD cannot handle: an unrecognised extension or unparseable source yields no
	 * violations, with the reason in the log. That is a deliberate gap the caller has to close — an empty
	 * result cannot distinguish "clean" from "PMD never looked", and recording the file as reviewed with
	 * zero findings would be a false clean. Surfacing it as a distinct per-file outcome belongs to the
	 * orchestrator, which owns the skip reasons.
	 *
	 * @param filePath repository-relative path; its extension selects the language, so a non-{@code .java}
	 *                 path is not analysed at all
	 * @param source   the file's full text as retrieved, which is also what the reported lines index into
	 * @return violations in PMD's own order, never null
	 */
	public List<PmdViolation> analyse(String filePath, String source) {
		if (filePath == null || !filePath.toLowerCase(Locale.ROOT).endsWith(JAVA_EXTENSION)) {
			// PMD picks the language from the extension and rejects anything else, which makes the analysis
			// run over no files at all and log a warning about it. Checking here keeps that warning out of
			// the log for every non-Java file a run meets. It is also where run_files.language stops being
			// informational: this mechanism is Java-only.
			log.debug("pmd: {} is not a Java file, not analysed", filePath);
			return List.of();
		}
		if (source == null || source.isBlank()) {
			return List.of();
		}

		var config = new PMDConfiguration();
		// The orchestrator already bounds concurrency per file; a second pool inside each file would
		// multiply it, and AGENTS.md forbids starting threads the caller did not configure.
		config.setThreads(1);
		// There is nothing on disk to key an incremental cache against, and leaving this unset makes PMD
		// log a suggestion to use one on every single file.
		config.setIgnoreIncrementalAnalysis(true);

		try (var analysis = PmdAnalysis.create(config)) {
			analysis.addRuleSet(ruleSet);
			analysis.files().addSourceFile(FileId.fromPathLikeString(filePath), source);
			return toViolations(analysis.performAnalysisAndCollectReport(), filePath);
		} catch (RuntimeException e) {
			// One file's analysis failing must not abort a run, which is the same isolation the orchestrator
			// applies everywhere else. The cause is kept because a PMD failure here is a bug in this class
			// or in the rule set, not a property of the file.
			log.warn("pmd: analysis of {} failed", filePath, e);
			return List.of();
		}
	}

	private List<PmdViolation> toViolations(Report report, String filePath) {
		for (Report.ProcessingError error : report.getProcessingErrors()) {
			// A parse failure, not a clean file. Logged rather than returned because this method's contract
			// is violations; the orchestrator still cannot tell the two apart, which is the gap noted above.
			log.warn("pmd: {} could not be parsed, so no rule was applied to it: {}", filePath, error.getMsg());
		}
		report.getConfigurationErrors().forEach(error ->
				log.error("pmd: the rule set is misconfigured, so {} was not reviewed: {}", filePath, error));

		var violations = new ArrayList<PmdViolation>();
		for (RuleViolation violation : report.getViolations()) {
			var rule = violation.getRule();
			violations.add(new PmdViolation(rule.getName(), violation.getDescription(),
					violation.getBeginLine(), violation.getEndLine(), rule.getPriority().getPriority()));
		}
		return List.copyOf(violations);
	}

	/**
	 * Flags {@code @Transactional} on a type rather than on a method.
	 * <p>
	 * AGENTS.md puts transaction boundaries on public service methods that represent one business operation,
	 * and says not to annotate every method by default. A type-level {@code @Transactional} does exactly that:
	 * it applies to every method, including reads that should be {@code readOnly = true}, private and
	 * package-private helpers the proxy never sees, and any method making a remote call — which then holds a
	 * pooled connection across the network.
	 * <p>
	 * Written as a Java rule rather than an XPath one for two reasons that both came out of running it. The
	 * node interface {@link ASTTypeDeclaration} is the single supertype of every kind of type declaration —
	 * class, interface, enum, record, annotation type, anonymous and implicit class — so the coverage is
	 * structural; the XPath equivalent had to union four node names by hand because {@code //ClassDeclaration}
	 * silently misses enums and records, and a list like that goes stale when the grammar grows a node. And
	 * {@code isAnyAnnotationPresent} resolves each annotation to its fully-qualified name through the same
	 * symbol table XPath's {@code pmd-java:hasAnnotation} used, so a project's own
	 * {@code com.example.own.Transactional} is not a false positive here either.
	 * <p>
	 * The violation is reported on the declaration node, whose source range is just the type's simple name —
	 * not the annotation lines above it, and not the body. So {@code beginLine} and {@code endLine} are always
	 * equal, and the line they name is the one carrying the {@code class}/{@code enum}/{@code record} keyword
	 * under ordinary formatting. That is the line a reviewer needs, because it is where the fix goes.
	 * <p>
	 * {@code priority} is {@link RulePriority#MEDIUM_HIGH} to match the value the rule carried as XML. It is
	 * a fallback: §13 has the guideline row's {@code severity} take precedence over it.
	 */
	private static final class ClassLevelTransactionalRule extends AbstractJavaRule {

		private static final Set<String> ANNOTATIONS = Set.of(
				"org.springframework.transaction.annotation.Transactional",
				"jakarta.transaction.Transactional");

		ClassLevelTransactionalRule() {
			setName("ClassLevelTransactional");
			setMessage("@Transactional belongs on the public service method, not on the class");
			setPriority(RulePriority.MEDIUM_HIGH);
			setLanguage(LanguageRegistry.PMD.getLanguageById("java"));
		}

		@Override
		public void apply(Node node, RuleContext ctx) {
			for (ASTTypeDeclaration declaration : node.descendants(ASTTypeDeclaration.class)) {
				if (declaration.isAnyAnnotationPresent(ANNOTATIONS)) {
					ctx.addViolation(declaration);
				}
			}
		}
	}

	/**
	 * Flags {@code @Transactional} on a method the Spring proxy can never advise: one that is not
	 * {@code public}, or one with no body (abstract, or declared on an interface).
	 * <p>
	 * This is the method-level companion to {@link ClassLevelTransactionalRule}, and it is deliberately
	 * scoped to the cases that rule does <em>not</em> see, so the two never report the same construct twice.
	 * Spring's declarative transactions are proxy-based (AGENTS.md §6): the advice is applied by a proxy that
	 * wraps the bean, so a call the proxy does not intercept runs with no transaction at all. A non-public
	 * method is invoked past the proxy — through {@code this} from inside the bean, or reflectively — so its
	 * {@code @Transactional} silently does nothing; AGENTS.md calls this out directly. A method with no body
	 * (an {@code abstract} method, or an interface method) has nothing for the proxy to wrap, so the
	 * annotation sits on a declaration that is never the thing executed.
	 * <p>
	 * A {@code public} method with a body is the one correct placement, and it is left alone — that is what
	 * AGENTS.md asks for. The class-level case is not touched here: a type is not an
	 * {@link ASTMethodDeclaration}, so it never reaches this rule, which is what keeps it distinct from
	 * {@link ClassLevelTransactionalRule} rather than doubling its findings.
	 * <p>
	 * The violation is reported on the method declaration, whose source range is the method's own name, so
	 * the cited line is where the fix goes. {@code priority} matches {@link ClassLevelTransactionalRule}'s
	 * {@link RulePriority#MEDIUM_HIGH}; §13 lets a guideline row's severity take precedence over it.
	 */
	private static final class NonAdvisableTransactionalMethodRule extends AbstractJavaRule {

		private static final Set<String> ANNOTATIONS = Set.of(
				"org.springframework.transaction.annotation.Transactional",
				"jakarta.transaction.Transactional");

		NonAdvisableTransactionalMethodRule() {
			setName("NonAdvisableTransactionalMethod");
			setMessage("@Transactional belongs on a public method with a body; the proxy cannot advise a "
					+ "non-public, abstract, or interface method");
			setPriority(RulePriority.MEDIUM_HIGH);
			setLanguage(LanguageRegistry.PMD.getLanguageById("java"));
		}

		@Override
		public void apply(Node node, RuleContext ctx) {
			for (ASTMethodDeclaration method : node.descendants(ASTMethodDeclaration.class)) {
				if (!method.isAnyAnnotationPresent(ANNOTATIONS)) {
					continue;
				}
				// Not public: invoked past the proxy, so the advice never runs. No body: abstract or on an
				// interface, so there is nothing for the proxy to wrap. Either way the annotation is inert.
				if (method.getVisibility() != Visibility.V_PUBLIC || method.getBody() == null) {
					ctx.addViolation(method);
				}
			}
		}
	}

	/**
	 * Flags a call to a Spring REST client made inside a {@code @Transactional} method.
	 * <p>
	 * AGENTS.md is explicit: a remote call must not run inside a database transaction. The transactional
	 * method holds a pooled connection for its whole duration, so a network round trip inside it keeps that
	 * connection checked out for the latency of a <em>different</em> service — starving the pool and coupling
	 * every other request to that service's response time — and a late failure rolls back work that already
	 * succeeded. The fix is to do the remote call outside the transaction and persist its result in a short
	 * one.
	 * <p>
	 * <b>Which calls count is decided by type, not by name.</b> The receiver of each call is resolved to its
	 * actual Java type and compared, by {@link TypeTestUtil#isA(String, net.sourceforge.pmd.lang.java.ast.TypeNode)},
	 * against the three configured client types. This is what separates {@code restClient.post()} from an
	 * unrelated {@code repository.post()} or a domain object's own {@code get()}: matching the method name
	 * alone would flag both. PMD resolves the receiver's type name from the file's {@code import}s without the
	 * client library being on any classpath — the same symbol resolution {@code isAnyAnnotationPresent} relies
	 * on above — so an imported, star-imported, or fully qualified field type all resolve.
	 * <p>
	 * <b>The report lands on the call itself</b> ({@code restClient.post()}), not on the method, so the cited
	 * line is the offending invocation. {@code if}, loops, and {@code try}/{@code catch} are reached by an
	 * ordinary descendant walk; a lambda body is a PMD <em>find boundary</em> that the walk skips, so the
	 * traversal is told to cross boundaries and then kept to calls whose nearest enclosing executable is
	 * still this method — which lets a lambda's body in (a lambda is no executable boundary) while leaving a
	 * nested class's own method out. A fluent chain reports once: only the innermost call whose receiver is
	 * the client ({@code restClient.post()}) matches; the {@code .uri(...).retrieve().body(...)} that follow
	 * have receivers of other types and do not.
	 * <p>
	 * <b>Unresolved receivers are never reported.</b> When PMD cannot resolve the receiver's type to one of
	 * the configured names — an inferred {@code var} whose type is unknown, a receiver that is itself an
	 * unresolved call result — {@code isA} returns false and nothing is flagged, so an unknown type is never
	 * treated as a confirmed violation. <b>Limitations:</b> the analysis is intraprocedural. A client reached
	 * through a wrapper method, through a method reference ({@code restClient::post}), or from inside an
	 * anonymous or local class declared in the method — whose method body runs in a different context, often
	 * another thread after the transaction commits — is outside this rule's scope.
	 */
	private static final class NoRestCallInTransactionRule extends AbstractJavaRule {

		private static final Set<String> ANNOTATIONS = Set.of(
				"org.springframework.transaction.annotation.Transactional",
				"jakarta.transaction.Transactional");

		/**
		 * The REST client types a call's receiver is matched against. Fully qualified because that is what
		 * {@link TypeTestUtil#isA(String, net.sourceforge.pmd.lang.java.ast.TypeNode)} compares, and the set
		 * is closed on purpose — only these three are in scope, so a project's own HTTP helper is not flagged.
		 */
		private static final List<String> REST_CLIENTS = List.of(
				"org.springframework.web.client.RestClient",
				"org.springframework.web.client.RestTemplate",
				"org.springframework.web.reactive.function.client.WebClient");

		NoRestCallInTransactionRule() {
			setName("NoRestCallInTransaction");
			setMessage("REST client called inside a @Transactional method: do the remote call outside the "
					+ "transaction so it does not hold the database connection across the network");
			setPriority(RulePriority.HIGH);
			setLanguage(LanguageRegistry.PMD.getLanguageById("java"));
		}

		@Override
		public void apply(Node node, RuleContext ctx) {
			for (ASTMethodDeclaration method : node.descendants(ASTMethodDeclaration.class)) {
				if (!method.isAnyAnnotationPresent(ANNOTATIONS)) {
					continue;
				}
				for (ASTMethodCall call : method.descendants(ASTMethodCall.class).crossFindBoundaries()) {
					// crossFindBoundaries reaches into lambda bodies, which descendants() treats as a find
					// boundary and would otherwise skip (plain if/for/try/catch blocks are not boundaries, so
					// those are already reached). It also reaches into anonymous and local classes declared in
					// the method, whose own methods execute in a different context — often another thread,
					// after the transaction has committed — so keep only a call whose nearest enclosing
					// executable is still this method. A lambda introduces no executable boundary, so its body
					// passes this check; a nested class's method body does not.
					if (call.ancestors(ASTExecutableDeclaration.class).first() != method) {
						continue;
					}
					ASTExpression receiver = call.getQualifier();
					// An unqualified call is an implicit this.method() — a local method, never a client call
					// on a field or variable. Only a qualified call has a receiver whose type can be a client.
					if (receiver == null) {
						continue;
					}
					if (isRestClient(receiver)) {
						ctx.addViolation(call);
					}
				}
			}
		}

		private static boolean isRestClient(ASTExpression receiver) {
			for (String client : REST_CLIENTS) {
				if (TypeTestUtil.isA(client, receiver)) {
					return true;
				}
			}
			return false;
		}
	}
}
