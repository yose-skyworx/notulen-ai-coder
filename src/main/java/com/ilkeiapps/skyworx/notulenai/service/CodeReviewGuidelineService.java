package com.ilkeiapps.skyworx.notulenai.service;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewMechanism;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewRoleType;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewSeverity;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewGuideline;
import com.ilkeiapps.skyworx.notulenai.entity.QCodeReviewGuideline;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads the review rules a run judges files against: every active rule of every role.
 * <p>
 * The library lives in this application's database, not in the repository under review. That is the whole
 * reason a run can be triggered against any repository and behave the same way — the rules do not depend on
 * the target having documented anything, and a repository cannot change its own verdict by editing a file.
 * <p>
 * One read per run, before any file is fetched. The result is a map of plain values rather than managed
 * entities, so the orchestrator can carry it across the untransactioned GitHub calls that follow without
 * holding a persistence context open — the same rule that keeps the remote calls out of the transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeReviewGuidelineService {

	private static final String STATUS_ACTIVE = "AKT";

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;

	/**
	 * One active rule.
	 * <p>
	 * A value object on purpose: it outlives the transaction that loaded it, and carrying a managed
	 * {@link CodeReviewGuideline} into the untransactioned part of a run would either detach it mid-flight
	 * or keep a connection checked out for the duration of the review.
	 * <p>
	 * {@code mechanism} decides how {@code content} is read — prompt prose for {@code LLM}, a reference into
	 * the shipped {@code pmd-java} ruleset for {@code PMD} — and whether the rule can run while inference is
	 * disabled. {@code severity} is what a finding from this rule carries, so it never has to come from a
	 * model's self-assessment.
	 */
	public record ActiveGuideline(Long id, ReviewRoleType role, String code, String title,
			ReviewMechanism mechanism, ReviewSeverity severity, String content) {

		/**
		 * Content is excluded deliberately. A rule's content is prompt material; a log line only needs to
		 * say which rule was chosen.
		 */
		@Override
		public String toString() {
			return role + " " + code + " (" + mechanism + ", rule " + id + ")";
		}
	}

	/**
	 * Loads the active rules, one per role.
	 * <p>
	 * A role with no active rule is simply absent from the result, and that absence is meaningful: it is how
	 * the caller learns that files of that role cannot be reviewed, and it records them as skipped rather
	 * than reviewing them against another role's rules. A run where no role has an active rule therefore
	 * reviews nothing and says so per file, instead of failing as a whole.
	 * <p>
	 * <b>Transitional shape.</b> The table now holds many rules per role, but this still returns one, because
	 * the orchestrator consumes a single guideline per role — {@code ReviewBasis} holds one, and
	 * {@code reviewOneFile} builds one prompt from it. Returning a list would change the review from "judge
	 * this file against the standard" to "judge it against each rule", which is a change to
	 * {@code CodeReviewRunService}, not to this reader. So the extra rules are dropped here, loudly, rather
	 * than silently: the winner is the lowest id, which is deterministic but arbitrary, and the warning
	 * names how many rules were not applied. This method returns every active rule per role as soon as the
	 * orchestrator is cut over, and the warning goes with the transition rather than staying as a net.
	 *
	 * @return one active rule per role, never null
	 */
	@Transactional(readOnly = true)
	public Map<ReviewRoleType, ActiveGuideline> loadActive() {
		var q = new QCodeReviewGuideline("g");
		var rows = configBuilder.create(em, CodeReviewGuideline.class)
				.from(CodeReviewGuideline.class, q.getMetadata().getName())
				.where(q.status.code.toString()).eq(STATUS_ACTIVE)
				// Deterministic winner for the transitional one-per-role shape. Without an ordering, which
				// rule a run applied would depend on nothing at all.
				.orderByAsc(q.id.toString())
				.getResultList();

		var active = new EnumMap<ReviewRoleType, ActiveGuideline>(ReviewRoleType.class);
		var dropped = new EnumMap<ReviewRoleType, Integer>(ReviewRoleType.class);

		for (CodeReviewGuideline row : rows) {
			var role = row.getRoleType();
			var rule = new ActiveGuideline(row.getId(), role, row.getCode(), row.getTitle(),
					row.getMechanism(), row.getSeverity(), row.getContent());
			if (active.putIfAbsent(role, rule) != null) {
				dropped.merge(role, 1, Integer::sum);
			}
		}

		if (!dropped.isEmpty()) {
			log.warn("review library: more active rules than the orchestrator can apply, per role {} — "
					+ "the lowest id was used and the rest were not", dropped);
		}

		log.info("review library: {} active rule(s) {}", active.size(), active.values());
		return active;
	}
}
