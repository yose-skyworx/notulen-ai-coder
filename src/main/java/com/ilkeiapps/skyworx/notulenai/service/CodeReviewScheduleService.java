package com.ilkeiapps.skyworx.notulenai.service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViewSetting;
import com.ilkeiapps.ilkeitool.validator.IlKeiValidator;
import com.ilkeiapps.ilkeitool.validator.ProcessResult;
import com.ilkeiapps.ilkeitoolentityblazebit.validator.IlKeiEntityValidator;
import com.ilkeiapps.skyworx.notulenai.bean.CodeReviewScheduleRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewScheduleRequestParameter;
import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewSchedule;
import com.ilkeiapps.skyworx.notulenai.entity.Project;
import com.ilkeiapps.skyworx.notulenai.entity.QCodeReviewSchedule;
import com.ilkeiapps.skyworx.notulenai.entityview.codereview.CodeReviewScheduleListView;
import com.ilkeiapps.skyworx.notulenai.utility.Utility;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Maintains each repository's daily review schedule.
 * <p>
 * Saving is an upsert keyed on {@code repositoryId}: the {@code uq_crs_repository} unique constraint
 * allows one schedule per repository, so there is no separate update payload and no id for a client to
 * supply. Both the cron expression and the timezone are parsed here rather than at scheduling time, so
 * an unusable value is rejected at the boundary instead of silently never firing.
 * <p>
 * The stored cron and timezone are the per-repository configuration git.md §10.3 asks for. Resolving
 * which repositories are actually due is the scheduler's job, not this service's.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeReviewScheduleService {

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;
	private final EntityViewManager viewManager;
	private final CodeRepositoryService codeRepositoryService;
	private final CodeReviewProperties properties;

	@Transactional
	public ApiResponse<CodeReviewScheduleRequest> saveSchedule(CodeReviewScheduleRequest src) {
		var wrap = new ApiResponse<CodeReviewScheduleRequest>();
		wrap.setStatus(false);

		if (!properties.isEnabled()) {
			wrap.setMessage(Utility.getErrorMessage("codereview.module_disabled"));
			return wrap;
		}

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(CodeReviewScheduleRequest.Fields.repositoryId, "Repository").isMandatory().asLong().withMinLen(1)
				.pack()
				.pick(CodeReviewScheduleRequest.Fields.cronExpression, "Cron").isMandatory().asString().withMinLen(6)
				.pack()
				.pick(CodeReviewScheduleRequest.Fields.timezone, "Zona Waktu").isMandatory().asString().withMinLen(1)
				.pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		try {
			CronExpression.parse(src.getCronExpression().trim());
		} catch (IllegalArgumentException e) {
			wrap.setMessage(Utility.getErrorMessage("codereview.schedule.cron_invalid"));
			return wrap;
		}

		var timezone = src.getTimezone().trim();
		try {
			ZoneId.of(timezone);
		} catch (DateTimeException e) {
			wrap.setMessage(Utility.getErrorMessage("codereview.schedule.timezone_invalid"));
			return wrap;
		}

		var repository = codeRepositoryService.findActiveRepository(src.getRepositoryId());
		if (repository == null) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.item_not_found"));
			return wrap;
		}
		if (!projectIsActive(repository.getProject().getId())) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.project_not_found"));
			return wrap;
		}

		var now = Instant.now();
		var enabled = src.getEnabled() == null || src.getEnabled();
		var existing = findByRepositoryId(repository.getId());
		var isNew = existing == null;

		var schedule = isNew ? new CodeReviewSchedule() : existing;
		if (isNew) {
			schedule.setRepository(repository);
			schedule.setCreatedAt(now);
		}
		schedule.setCronExpression(src.getCronExpression().trim());
		schedule.setTimezone(timezone);
		schedule.setEnabled(enabled);
		schedule.setUpdatedAt(now);

		if (isNew) {
			this.em.persist(schedule);
		}
		// A loaded row is already managed here, so dirty checking carries the change.
		this.em.flush();

		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	@Transactional(readOnly = true)
	public ApiResponse<CodeReviewScheduleListView> listSchedule(CodeReviewScheduleRequestParameter filter) {
		var wrap = new ApiResponse<CodeReviewScheduleListView>();
		wrap.setStatus(false);

		if (filter.getProjectId() == null) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.project_not_found"));
			return wrap;
		}
		if (!projectIsActive(filter.getProjectId())) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.project_not_found"));
			return wrap;
		}

		var qs = new QCodeReviewSchedule("s");
		var query = configBuilder.create(em, CodeReviewSchedule.class)
				.from(CodeReviewSchedule.class, qs.getMetadata().getName())
				.where(qs.repository.project.id.toString()).eq(filter.getProjectId())
				.where(qs.repository.status.code.toString()).notEq("REM");

		var num = Utility.getPerPage(filter.getPerPage());
		var of = Utility.getOffsetPage(filter.getPage(), num);
		query = query.orderByDesc(qs.id.toString());

		var res = viewManager.applySetting(EntityViewSetting.create(CodeReviewScheduleListView.class, of, num), query)
				.withKeysetExtraction(true)
				.getResultList();

		wrap.setMaxPage(res.getTotalPages());
		wrap.setData(res.stream().toList());
		wrap.setStatus(true);
		return wrap;
	}

	private CodeReviewSchedule findByRepositoryId(Long repositoryId) {
		var qs = new QCodeReviewSchedule("s");
		var rows = configBuilder.create(em, CodeReviewSchedule.class)
				.from(CodeReviewSchedule.class, qs.getMetadata().getName())
				.where(qs.repository.id.toString()).eq(repositoryId)
				.setMaxResults(1)
				.getResultList();
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private boolean projectIsActive(Long projectId) {
		var vr = IlKeiEntityValidator.builder().withCriteriaBuilderFactory(em, configBuilder)
				.pick().withEntity(Project.class, "p").whereCriteriaEqual("p.id", projectId)
				.whereCriteriaEqual("p.status.code", "AKT")
				.withErrorMessage(Utility.getErrorMessage("codereview.repository.project_not_found")).pack()
				.validate();
		return vr.getResult();
	}
}
