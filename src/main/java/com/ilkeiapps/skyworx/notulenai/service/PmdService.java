package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewRoleType;

import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.java.ast.ASTAssignableExpr.ASTNamedReferenceExpr;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTExpression;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTRecordDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.ModifierOwner.Visibility;
import net.sourceforge.pmd.lang.java.metrics.JavaMetrics;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;
import net.sourceforge.pmd.lang.java.types.TypeTestUtil;
import net.sourceforge.pmd.lang.metrics.MetricsUtil;
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
 * <b>The rule set is chosen by the file's role, because each role has its own conventions.</b> A reviewed
 * file has already resolved to a {@link ReviewRoleType} — service, controller, or entity — and each role's
 * guideline is a different thing: a service's transaction boundaries, a controller's "never open a
 * transaction", an entity's JPA-modelling constraints. Running every rule over every file would flag a
 * service's correct method-level {@code @Transactional} as a controller violation, or look for
 * {@code FetchType.EAGER} in a controller. So {@link #analyse} takes the role and applies only that role's
 * {@link RuleSet}; the per-role sets are built once in {@link #buildRuleSets()}.
 * <p>
 * <b>The rules live in this class for now.</b> §13 of {@code code-review-plan.md} stores each rule in its
 * own {@code code_review_guidelines} row with the PMD reference in {@code content}; until that library is
 * loaded and handed in, the rules live here so the mechanism can be proven end to end. Moving them out means
 * {@code buildRuleSets} takes the role's guideline rows rather than using these, and the {@code severity}
 * starts coming from the guideline row instead of from PMD's priority.
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
	 * The transaction annotations every transaction rule resolves a declaration against, fully qualified
	 * because that is what {@code isAnyAnnotationPresent} compares after symbol resolution — a project's own
	 * {@code com.example.Transactional} is not one of these and is not flagged.
	 */
	private static final Set<String> TRANSACTIONAL_ANNOTATIONS = Set.of(
			"org.springframework.transaction.annotation.Transactional",
			"jakarta.transaction.Transactional");

	/**
	 * The JPA type annotations that mark a declaration as a persistence model. The entity rules require one
	 * of these before firing, so a plain helper class or a nested enum sitting in an entity file — which the
	 * resolver still routes here as ENTITY — is not measured against entity-modelling rules it is not.
	 */
	private static final Set<String> PERSISTENCE_TYPE_ANNOTATIONS = Set.of(
			"jakarta.persistence.Entity",
			"jakarta.persistence.MappedSuperclass",
			"jakarta.persistence.Embeddable");

	// A stable, language-neutral identifier per rule, surfaced on every finding as its code. Unlike the rule
	// name it is meant never to change, and unlike the message it is not localised — so a consumer keys off
	// this (and §13's guideline rows will too), not the Indonesian message text. Grouped by concern:
	// TRANSACTION for the four transaction-boundary rules, ENTITY for the JPA-modelling rules, COMPLEXITY for
	// the metric rule.
	private static final String CODE_CLASS_LEVEL_TRANSACTIONAL = "SKY-TRANSACTION-001";
	private static final String CODE_NON_ADVISABLE_TRANSACTIONAL_METHOD = "SKY-TRANSACTION-002";
	private static final String CODE_NO_REST_CALL_IN_TRANSACTION = "SKY-TRANSACTION-003";
	private static final String CODE_NO_TRANSACTIONAL_IN_CONTROLLER = "SKY-TRANSACTION-004";
	private static final String CODE_LOMBOK_DATA_ON_ENTITY = "SKY-ENTITY-001";
	private static final String CODE_EAGER_FETCH_ON_ENTITY = "SKY-ENTITY-002";
	private static final String CODE_ENTITY_AS_RECORD = "SKY-ENTITY-003";
	private static final String CODE_SERVICE_METHOD_COMPLEXITY = "SKY-COMPLEXITY-001";

	/**
	 * Maps a rule's {@link net.sourceforge.pmd.lang.rule.Rule#getName() name} to its stable code. Keyed by
	 * name rather than held on the rule object so the lookup is unaffected by any copy PMD makes of a rule
	 * during analysis; a name with no entry resolves to an empty code rather than throwing.
	 */
	private static final Map<String, String> RULE_CODES = Map.ofEntries(
			Map.entry("ClassLevelTransactional", CODE_CLASS_LEVEL_TRANSACTIONAL),
			Map.entry("NonAdvisableTransactionalMethod", CODE_NON_ADVISABLE_TRANSACTIONAL_METHOD),
			Map.entry("NoRestCallInTransaction", CODE_NO_REST_CALL_IN_TRANSACTION),
			Map.entry("NoTransactionalInController", CODE_NO_TRANSACTIONAL_IN_CONTROLLER),
			Map.entry("LombokDataOnEntity", CODE_LOMBOK_DATA_ON_ENTITY),
			Map.entry("EagerFetchOnEntity", CODE_EAGER_FETCH_ON_ENTITY),
			Map.entry("EntityAsRecord", CODE_ENTITY_AS_RECORD),
			Map.entry("ServiceMethodCyclomaticComplexity", CODE_SERVICE_METHOD_COMPLEXITY));

	/**
	 * One {@link RuleSet} per role, built once and reused across analyses.
	 * <p>
	 * A {@link RuleSet} is safe to hand to successive analyses, and none of these rules keeps state between
	 * them: everything each is set to is assigned in its constructor, and every {@code apply} receives the
	 * context to report into rather than holding one. Keyed by {@link ReviewRoleType} so {@link #analyse}
	 * can select in O(1); {@link #buildRuleSets()} fails at construction if a role has no set, so a new role
	 * cannot silently review against nothing.
	 */
	private final Map<ReviewRoleType, RuleSet> ruleSets = buildRuleSets();

	/**
	 * Assembles the per-role rule sets and verifies every {@link ReviewRoleType} has one.
	 * <p>
	 * Built in code, not parsed from an XML rule set. {@link RuleSet#create} takes already constructed
	 * {@link net.sourceforge.pmd.lang.rule.Rule} instances, so there is no rule-set document, no
	 * {@code RuleSetLoader}, and no {@code xsi:schemaLocation} to leave off — which matters because PMD's own
	 * rule sets point that attribute at {@code pmd.sourceforge.io}, and a validating parse would put a network
	 * round trip inside what must be a local, deterministic check. The empty include/exclude pattern lists
	 * mean each set applies to every file handed to it; the file-name argument is a label PMD only uses in
	 * diagnostics, never a path it reads.
	 * <p>
	 * The size check is the guard that makes {@link ReviewRoleType} exhaustive here: adding a role without a
	 * rule set fails fast at startup rather than making every file of that role review against nothing.
	 */
	private static Map<ReviewRoleType, RuleSet> buildRuleSets() {
		var sets = new EnumMap<ReviewRoleType, RuleSet>(ReviewRoleType.class);

		// SERVICE: transaction boundaries live on the service's public methods.
		sets.put(ReviewRoleType.SERVICE, RuleSet.create("notulen-ai service rules",
				"AGENTS.md service transaction conventions that no shipped PMD rule covers", "service-rules",
				List.of(), List.of(), List.of(new ClassLevelTransactionalRule(),
						new NonAdvisableTransactionalMethodRule(), new NoRestCallInTransactionRule(),
						new ServiceMethodComplexityRule())));

		// CONTROLLER: a controller must never open a transaction at all.
		sets.put(ReviewRoleType.CONTROLLER, RuleSet.create("notulen-ai controller rules",
				"AGENTS.md controller conventions that no shipped PMD rule covers", "controller-rules",
				List.of(), List.of(), List.of(new NoTransactionalInControllerRule())));

		// ENTITY: JPA-modelling constraints on the persistence class itself.
		sets.put(ReviewRoleType.ENTITY, RuleSet.create("notulen-ai entity rules",
				"AGENTS.md JPA entity conventions that no shipped PMD rule covers", "entity-rules",
				List.of(), List.of(), List.of(new NoDataAnnotationOnEntityRule(),
						new EagerFetchOnEntityRule(), new EntityAsRecordRule())));

		if (sets.size() != ReviewRoleType.values().length) {
			throw new IllegalStateException(
					"every ReviewRoleType must have a PMD rule set; configured: " + sets.keySet());
		}
		return sets;
	}

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
	 * <p>
	 * {@code code} is the rule's stable identifier (e.g. {@code SKY-TRANSACTION-001}) from {@link #RULE_CODES}.
	 * It is what a consumer should key off rather than {@code ruleName} (which could be renamed) or
	 * {@code message} (which is localised to Indonesian); empty if the rule has no registered code.
	 * <p>
	 * {@code evidence} is the offending source line(s) themselves, trimmed — the code a reviewer reads to see
	 * what fired, rather than having to open the file at {@code beginLine}. It is sliced out of the same
	 * {@code source} the lines index into, so it always matches them, and it <b>reaches upward over the
	 * annotations immediately above {@code beginLine}</b> so the construct actually flagged is visible: a
	 * {@code ClassLevelTransactional} reports on the {@code class} line, but the subject is the
	 * {@code @Transactional} on the line above, which the evidence then carries. Extraction is by line number,
	 * not by column: the project indents with tabs, and PMD can report a column that assumes a wider tab stop
	 * than a raw string offset, so a column slice would cut in the wrong place — a whole-line slice cannot.
	 *
	 * @param ruleName  the rule that fired, which is what a finding cites as its {@code rule_id}
	 * @param code      the rule's stable, language-neutral identifier; empty when none is registered
	 * @param message   the rule's message, already resolved by PMD
	 * @param beginLine first line of the offending construct, 1-based
	 * @param endLine   last line of it, 1-based
	 * @param priority  PMD priority, 1 (most severe) to 5
	 * @param evidence  the trimmed source line(s) around {@code beginLine}, including the annotations directly
	 *                  above it, or empty when the range falls outside the source
	 */
	public record PmdViolation(String ruleName, String code, String message, int beginLine, int endLine,
			int priority, String evidence) {
	}

	/**
	 * Runs the rule set for one file's role over its source text.
	 * <p>
	 * Never throws for a file PMD cannot handle: an unrecognised extension or unparseable source yields no
	 * violations, with the reason in the log. That is a deliberate gap the caller has to close — an empty
	 * result cannot distinguish "clean" from "PMD never looked", and recording the file as reviewed with
	 * zero findings would be a false clean. Surfacing it as a distinct per-file outcome belongs to the
	 * orchestrator, which owns the skip reasons.
	 *
	 * @param role     the role the file resolved to, which selects the rule set; a {@code null} role means
	 *                 the file plays no reviewed role and is not analysed
	 * @param filePath repository-relative path; its extension selects the language, so a non-{@code .java}
	 *                 path is not analysed at all
	 * @param source   the file's full text as retrieved, which is also what the reported lines index into
	 * @return violations in PMD's own order, never null
	 */
	public List<PmdViolation> analyse(ReviewRoleType role, String filePath, String source) {
		if (role == null) {
			// A file with no role has no guideline to be measured against; the orchestrator already skips
			// these, but guarding here keeps analyse total and the rule-set lookup below non-null.
			return List.of();
		}
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
			analysis.addRuleSet(ruleSets.get(role));
			analysis.files().addSourceFile(FileId.fromPathLikeString(filePath), source);
			return toViolations(analysis.performAnalysisAndCollectReport(), filePath, source);
		} catch (RuntimeException e) {
			// One file's analysis failing must not abort a run, which is the same isolation the orchestrator
			// applies everywhere else. The cause is kept because a PMD failure here is a bug in this class
			// or in the rule set, not a property of the file.
			log.warn("pmd: analysis of {} failed", filePath, e);
			return List.of();
		}
	}

	private List<PmdViolation> toViolations(Report report, String filePath, String source) {
		for (Report.ProcessingError error : report.getProcessingErrors()) {
			// A parse failure, not a clean file. Logged rather than returned because this method's contract
			// is violations; the orchestrator still cannot tell the two apart, which is the gap noted above.
			log.warn("pmd: {} could not be parsed, so no rule was applied to it: {}", filePath, error.getMsg());
		}
		report.getConfigurationErrors().forEach(error ->
				log.error("pmd: the rule set is misconfigured, so {} was not reviewed: {}", filePath, error));

		var lines = source.split("\n", -1);
		var violations = new ArrayList<PmdViolation>();
		for (RuleViolation violation : report.getViolations()) {
			var rule = violation.getRule();
			var code = RULE_CODES.getOrDefault(rule.getName(), "");
			violations.add(new PmdViolation(rule.getName(), code, violation.getDescription(),
					violation.getBeginLine(), violation.getEndLine(), rule.getPriority().getPriority(),
					evidence(lines, violation.getBeginLine(), violation.getEndLine())));
		}
		return List.copyOf(violations);
	}

	/**
	 * The trimmed source line(s) for a violation's evidence, including the annotations directly above the
	 * reported line.
	 * <p>
	 * A rule that reports on a declaration — {@code ClassLevelTransactional} on the {@code class} line, the
	 * complexity rule on the method signature — names a line that does not by itself show what fired: the
	 * subject is the {@code @Transactional} (or other annotation) sitting just above it. So the slice walks
	 * upward from {@code beginLine} over each immediately-preceding line whose trimmed text starts with
	 * {@code @}, bringing the annotation(s) into the evidence; it stops at the first non-annotation line, so a
	 * previous field or a blank line bounds it and unrelated code is never pulled in.
	 * <p>
	 * By line number, not column, on purpose: see the note on {@link PmdViolation}. {@code lines} is the
	 * source already split on {@code \n}, so indices are {@code line - 1}; a range that falls outside it
	 * yields the empty string rather than throwing, since a bad range must not sink the whole file's report.
	 * Interior {@code \r} from CRLF endings is left as-is — the common single-line case trims it off the one
	 * line's ends, and GitHub content is {@code \n}-terminated in practice.
	 */
	private static String evidence(String[] lines, int beginLine, int endLine) {
		if (beginLine < 1 || beginLine > lines.length) {
			return "";
		}
		// Reach up over the annotations above the reported line so the flagged construct (e.g. @Transactional
		// above a class) is in the evidence; stop at the first line that is not an annotation.
		var start = beginLine;
		while (start > 1 && lines[start - 2].strip().startsWith("@")) {
			start--;
		}
		var end = Math.min(endLine, lines.length);
		var snippet = new StringBuilder();
		for (int line = start; line <= end; line++) {
			if (line > start) {
				snippet.append('\n');
			}
			snippet.append(lines[line - 1]);
		}
		return snippet.toString().strip();
	}

	// =========================================================================== SERVICE rules

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

		ClassLevelTransactionalRule() {
			setName("ClassLevelTransactional");
			setMessage("@Transactional harus diterapkan pada metode service publik, bukan pada kelas");
			setPriority(RulePriority.MEDIUM_HIGH);
			setLanguage(LanguageRegistry.PMD.getLanguageById("java"));
		}

		@Override
		public void apply(Node node, RuleContext ctx) {
			for (ASTTypeDeclaration declaration : node.descendants(ASTTypeDeclaration.class)) {
				if (declaration.isAnyAnnotationPresent(TRANSACTIONAL_ANNOTATIONS)) {
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

		NonAdvisableTransactionalMethodRule() {
			setName("NonAdvisableTransactionalMethod");
			setMessage("@Transactional harus diterapkan pada metode publik yang memiliki badan; proxy tidak "
					+ "dapat menerapkan transaksi pada metode non-publik, abstrak, atau metode antarmuka");
			setPriority(RulePriority.MEDIUM_HIGH);
			setLanguage(LanguageRegistry.PMD.getLanguageById("java"));
		}

		@Override
		public void apply(Node node, RuleContext ctx) {
			for (ASTMethodDeclaration method : node.descendants(ASTMethodDeclaration.class)) {
				if (!method.isAnyAnnotationPresent(TRANSACTIONAL_ANNOTATIONS)) {
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
			setMessage("Klien REST dipanggil di dalam metode @Transactional: lakukan panggilan remote di luar "
					+ "transaksi agar koneksi basis data tidak tertahan selama panggilan jaringan berlangsung");
			setPriority(RulePriority.HIGH);
			setLanguage(LanguageRegistry.PMD.getLanguageById("java"));
		}

		@Override
		public void apply(Node node, RuleContext ctx) {
			for (ASTMethodDeclaration method : node.descendants(ASTMethodDeclaration.class)) {
				if (!method.isAnyAnnotationPresent(TRANSACTIONAL_ANNOTATIONS)) {
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

	/**
	 * Flags a service method whose cyclomatic complexity exceeds {@link #MAX_COMPLEXITY}.
	 * <p>
	 * AGENTS.md asks for focused, reasonably small methods and prefers clarity over cleverness. Cyclomatic
	 * complexity counts the linearly independent paths through a method — one plus every decision point
	 * ({@code if}, {@code for}, {@code while}, {@code case}, {@code catch}, a ternary, and each {@code &&} /
	 * {@code ||}) — so a high number is a method doing too much branching to read, test, or change safely. A
	 * service, where the business branching lives, is exactly where this creeps in, which is why the rule is
	 * scoped to the service role.
	 * <p>
	 * <b>The count is PMD's own metric, not a hand-rolled one.</b> {@link JavaMetrics#CYCLO} is the same
	 * computation PMD's shipped complexity rule uses, so the number is the canonical one rather than an
	 * approximation this class would have to keep correct as the grammar grows new branching forms.
	 * {@link MetricsUtil#supportsAll} first skips any method the metric cannot measure — an {@code abstract}
	 * or interface method has no body — so those never produce a spurious zero-complexity finding.
	 * <p>
	 * The violation is reported on the method declaration, whose source range is the method name, and the
	 * message carries the method's name, its measured complexity, and the threshold. {@link #MAX_COMPLEXITY}
	 * is the project's threshold for this metric (above PMD's own default of 10); §13 lets a guideline row
	 * override the severity, and a future guideline row could carry the threshold too. {@code priority} is
	 * {@link RulePriority#MEDIUM}: a maintainability concern, below the transaction-correctness rules.
	 */
	private static final class ServiceMethodComplexityRule extends AbstractJavaRule {

		private static final int MAX_COMPLEXITY = 15;

		ServiceMethodComplexityRule() {
			setName("ServiceMethodCyclomaticComplexity");
			setMessage("Metode service ''{0}'' memiliki kompleksitas siklomatik {1}, melebihi batas {2}: pecah "
					+ "menjadi beberapa metode yang lebih kecil dan terfokus");
			setPriority(RulePriority.MEDIUM);
			setLanguage(LanguageRegistry.PMD.getLanguageById("java"));
		}

		@Override
		public void apply(Node node, RuleContext ctx) {
			for (ASTMethodDeclaration method : node.descendants(ASTMethodDeclaration.class)) {
				// A method with no body (abstract or on an interface) has no paths to count; the metric does
				// not support it, so skip rather than report a meaningless zero.
				if (!MetricsUtil.supportsAll(method, JavaMetrics.CYCLO)) {
					continue;
				}
				int complexity = MetricsUtil.computeMetric(JavaMetrics.CYCLO, method);
				if (complexity > MAX_COMPLEXITY) {
					ctx.addViolation(method, method.getName(), complexity, MAX_COMPLEXITY);
				}
			}
		}
	}

	// ======================================================================== CONTROLLER rules

	/**
	 * Flags {@code @Transactional} anywhere in a controller — on the type or on any method.
	 * <p>
	 * AGENTS.md is unambiguous: controllers must not open transactions; the transaction boundary belongs on
	 * the service. So, unlike the service rules, there is no "correct placement" to spare here: a controller
	 * with {@code @Transactional} on a method is as wrong as one with it on the class, because the controller
	 * should not be the unit of work at all. That is why this is a single controller rule rather than the two
	 * service rules routed at controllers — the service rules exist precisely to leave a method-level
	 * {@code @Transactional} alone, which is the opposite of what a controller needs.
	 * <p>
	 * Both the type declaration and the method declaration are checked, each reported on its own node, so a
	 * class-level annotation lands on the {@code class} keyword line and a method-level one on the method
	 * name — in both cases the line the reviewer removes the annotation from. A field can never carry
	 * {@code @Transactional} (it targets types and methods only), so there is no third case to cover.
	 * <p>
	 * {@code priority} is {@link RulePriority#MEDIUM_HIGH}; §13 lets a guideline row's severity take
	 * precedence over it.
	 */
	private static final class NoTransactionalInControllerRule extends AbstractJavaRule {

		NoTransactionalInControllerRule() {
			setName("NoTransactionalInController");
			setMessage("Controller tidak boleh membuka transaksi: hapus @Transactional dan pertahankan batas "
					+ "transaksi pada lapisan service");
			setPriority(RulePriority.MEDIUM_HIGH);
			setLanguage(LanguageRegistry.PMD.getLanguageById("java"));
		}

		@Override
		public void apply(Node node, RuleContext ctx) {
			for (ASTTypeDeclaration declaration : node.descendants(ASTTypeDeclaration.class)) {
				if (declaration.isAnyAnnotationPresent(TRANSACTIONAL_ANNOTATIONS)) {
					ctx.addViolation(declaration);
				}
			}
			for (ASTMethodDeclaration method : node.descendants(ASTMethodDeclaration.class)) {
				if (method.isAnyAnnotationPresent(TRANSACTIONAL_ANNOTATIONS)) {
					ctx.addViolation(method);
				}
			}
		}
	}

	// ============================================================================ ENTITY rules

	/**
	 * Flags Lombok {@code @Data} on a JPA entity.
	 * <p>
	 * AGENTS.md states it as a "never": {@code @Data} generates {@code equals}, {@code hashCode} and
	 * {@code toString} over every field, which on an entity traverses lazy relationships (triggering queries
	 * or {@code LazyInitializationException}), can recurse through bidirectional links, and bases identity on
	 * mutable state and on an id that is {@code null} before persistence — breaking the entity's identity
	 * semantics once it enters a {@code HashSet}. The fix is to drop {@code @Data} and define equality and a
	 * safe {@code toString} explicitly.
	 * <p>
	 * <b>Guarded by the persistence annotation, resolved by type.</b> The rule fires only on a type that is
	 * itself {@code @Entity}, {@code @MappedSuperclass} or {@code @Embeddable} and carries {@code @Data} —
	 * both resolved to their fully-qualified names through the symbol table, so a {@code @Data} on a plain
	 * nested helper in the same file, or a {@code @Data} that is not Lombok's, is not flagged. It is reported
	 * on the type declaration, where the annotation is removed.
	 */
	private static final class NoDataAnnotationOnEntityRule extends AbstractJavaRule {

		private static final Set<String> LOMBOK_DATA = Set.of("lombok.Data");

		NoDataAnnotationOnEntityRule() {
			setName("LombokDataOnEntity");
			setMessage("@Data pada entitas JPA menghasilkan equals/hashCode/toString atas seluruh field, yang "
					+ "menelusuri relasi lazy dan merusak identitas entitas; definisikan secara eksplisit");
			setPriority(RulePriority.HIGH);
			setLanguage(LanguageRegistry.PMD.getLanguageById("java"));
		}

		@Override
		public void apply(Node node, RuleContext ctx) {
			for (ASTTypeDeclaration declaration : node.descendants(ASTTypeDeclaration.class)) {
				if (declaration.isAnyAnnotationPresent(PERSISTENCE_TYPE_ANNOTATIONS)
						&& declaration.isAnyAnnotationPresent(LOMBOK_DATA)) {
					ctx.addViolation(declaration);
				}
			}
		}
	}

	/**
	 * Flags {@code FetchType.EAGER} in an entity mapping.
	 * <p>
	 * AGENTS.md: do not use {@code FetchType.EAGER} to fix lazy loading. Eager fetching loads the association
	 * on every read of the owner whether the caller needs it or not, multiplies rows when several are eager,
	 * and cannot be undone per-query — the opposite of the explicit, lazy-by-default fetch the project asks
	 * for. The fix is {@code LAZY} plus a deliberate {@code JOIN FETCH} / entity graph where a use case needs
	 * the association.
	 * <p>
	 * <b>Matched by type, not by the bare name.</b> Each name/field reference named {@code EAGER} is resolved
	 * and kept only when its type is {@code jakarta.persistence.FetchType}, so an unrelated constant that
	 * happens to be called {@code EAGER} is not flagged, while both {@code FetchType.EAGER} and a
	 * fully-qualified {@code jakarta.persistence.FetchType.EAGER} are. The violation lands on the reference
	 * itself, i.e. the {@code fetch = FetchType.EAGER} attribute line where the fix goes.
	 * <p>
	 * <b>Limitation:</b> a value reached only through a static-import alias that type resolution cannot tie
	 * back to {@code FetchType} is outside scope, by the same no-guessing rule the REST-client rule follows —
	 * an unresolved reference is never reported.
	 */
	private static final class EagerFetchOnEntityRule extends AbstractJavaRule {

		private static final String FETCH_TYPE = "jakarta.persistence.FetchType";

		EagerFetchOnEntityRule() {
			setName("EagerFetchOnEntity");
			setMessage("FetchType.EAGER memuat asosiasi pada setiap pembacaan dan tidak dapat dibatalkan per "
					+ "kueri: gunakan LAZY serta JOIN FETCH atau entity graph secara sengaja ketika suatu kasus "
					+ "penggunaan membutuhkannya");
			setPriority(RulePriority.MEDIUM_HIGH);
			setLanguage(LanguageRegistry.PMD.getLanguageById("java"));
		}

		@Override
		public void apply(Node node, RuleContext ctx) {
			// Both a qualified FetchType.EAGER (a field access) and a bare EAGER from a static import (a
			// variable access) are ASTNamedReferenceExpr, so one walk covers both; the type check below is
			// what keeps an unrelated constant named EAGER out.
			for (ASTNamedReferenceExpr reference : node.descendants(ASTNamedReferenceExpr.class)) {
				if ("EAGER".equals(reference.getName()) && TypeTestUtil.isA(FETCH_TYPE, reference)) {
					ctx.addViolation(reference);
				}
			}
		}
	}

	/**
	 * Flags a JPA entity declared as a {@code record}.
	 * <p>
	 * AGENTS.md: do not make entities Java records. A record is final and its components are final, so
	 * Hibernate cannot proxy it for lazy loading, cannot use the required no-arg constructor, and cannot
	 * manage its identity across the persistence lifecycle — a record models an immutable value, which an
	 * entity is not. The fix is an ordinary class with JPA-compatible construction.
	 * <p>
	 * <b>Guarded by the persistence annotation.</b> Only a record that is itself {@code @Entity},
	 * {@code @MappedSuperclass} or {@code @Embeddable} is flagged, so a plain record used as a projection or a
	 * value carrier in an entity file is left alone. It is reported on the record declaration, whose source
	 * range is the record name — the declaration to convert to a class.
	 */
	private static final class EntityAsRecordRule extends AbstractJavaRule {

		EntityAsRecordRule() {
			setName("EntityAsRecord");
			setMessage("Entitas JPA tidak boleh berupa record: Hibernate memerlukan kelas yang dapat di-proxy "
					+ "dengan konstruktor tanpa argumen, yang tidak dapat disediakan oleh record yang bersifat final");
			setPriority(RulePriority.HIGH);
			setLanguage(LanguageRegistry.PMD.getLanguageById("java"));
		}

		@Override
		public void apply(Node node, RuleContext ctx) {
			for (ASTRecordDeclaration record : node.descendants(ASTRecordDeclaration.class)) {
				if (record.isAnyAnnotationPresent(PERSISTENCE_TYPE_ANNOTATIONS)) {
					ctx.addViolation(record);
				}
			}
		}
	}
}
