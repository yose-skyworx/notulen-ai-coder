package com.ilkeiapps.skyworx.notulenai.service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewRoleType;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewGuideline;
import com.ilkeiapps.skyworx.notulenai.entity.QCodeReviewGuideline;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads the review standard a run judges files against: the active guideline version for each role.
 * <p>
 * The standard lives in this application's database, not in the repository under review. That is the
 * whole reason a run can be triggered against any repository and behave the same way — the rules do not
 * depend on the target having documented anything, and a repository cannot change its own verdict by
 * editing a file. It also means the standard is versioned and auditable in a way a file in someone else's
 * branch never is.
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
	 * The guideline one role is currently reviewed against.
	 * <p>
	 * A value object on purpose: it outlives the transaction that loaded it, and carrying a managed
	 * {@link CodeReviewGuideline} into the untransactioned part of a run would either detach it mid-flight
	 * or keep a connection checked out for the duration of the review.
	 */
	public record ActiveGuideline(Long id, ReviewRoleType role, int version, String content) {

		/**
		 * Content is excluded deliberately. A guideline is thousands of characters and is prompt material;
		 * a log line only needs to say which version was chosen.
		 */
		@Override
		public String toString() {
			return role + " v" + version + " (guideline " + id + ")";
		}
	}

	/**
	 * Loads the active version of every role's guideline.
	 * <p>
	 * A role with no active version is simply absent from the result, and that absence is meaningful: it is
	 * how the caller learns that files of that role cannot be reviewed, and it records them as skipped
	 * rather than reviewing them against another role's rules. A run where no role has an active version
	 * therefore reviews nothing and says so per file, instead of failing as a whole.
	 *
	 * @return active guideline per role, never null
	 */
	@Transactional(readOnly = true)
	public Map<ReviewRoleType, ActiveGuideline> loadActive() {
		var q = new QCodeReviewGuideline("g");
		var rows = configBuilder.create(em, CodeReviewGuideline.class)
				.from(CodeReviewGuideline.class, q.getMetadata().getName())
				.where(q.status.code.toString()).eq(STATUS_ACTIVE)
				.getResultList();

		var active = new EnumMap<ReviewRoleType, ActiveGuideline>(ReviewRoleType.class);
		var contested = EnumSet.noneOf(ReviewRoleType.class);

		for (CodeReviewGuideline row : rows) {
			var role = row.getRoleType();
			var version = row.getVersion() == null ? 0 : row.getVersion();
			var current = active.get(role);
			if (current == null || version > current.version()) {
				active.put(role, new ActiveGuideline(row.getId(), role, version, row.getContent()));
			}
			if (current != null) {
				contested.add(role);
			}
		}

		if (!contested.isEmpty()) {
			// One active version per role is enforced by the service that activates a version, not by the
			// database, because the only way to express it in PostgreSQL is a partial unique index and
			// ddl-auto=update will not create one. So a manual edit can leave two. Taking the highest
			// version is at least deterministic; taking whichever row the query returned first would make a
			// run's verdict depend on nothing at all.
			log.warn("guideline library: role(s) {} have more than one active version, the highest was used",
					contested);
		}

		log.info("guideline library: {} active version(s) {}", active.size(), active.values());
		return active;
	}
}
