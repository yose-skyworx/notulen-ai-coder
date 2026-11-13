package com.ilkeiapps.skyworx.notulenai.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViewSetting;
import com.ilkeiapps.ilkeitool.validator.IlKeiValidator;
import com.ilkeiapps.ilkeitool.validator.ProcessResult;
import com.ilkeiapps.ilkeitoolentityblazebit.validator.IlKeiEntityValidator;
import com.ilkeiapps.skyworx.notulenai.bean.ProjectRequest;
import com.ilkeiapps.skyworx.notulenai.bean.ProjectUpdateRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.ProjectIdRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.ProjectRequestParameter;
import com.ilkeiapps.skyworx.notulenai.entity.Client;
import com.ilkeiapps.skyworx.notulenai.entity.Project;
import com.ilkeiapps.skyworx.notulenai.entity.QProject;
import com.ilkeiapps.skyworx.notulenai.entityview.project.ProjectListView;
import com.ilkeiapps.skyworx.notulenai.utility.Utility;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Project CRUD, following the com.ilkeiapps house style: Blaze-Persistence queries with QueryDSL path
 * metadata, {@code IlKeiValidator} / {@code IlKeiEntityValidator} for input and foreign-key checks,
 * Entity Views for reads, and a soft delete that flips the general status to {@code REM}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;
	private final EntityViewManager viewManager;
	private final StatusService statusService;

	@Transactional
	public ApiResponse<ProjectRequest> saveProject(ProjectRequest src) {
		var wrap = new ApiResponse<ProjectRequest>();
		wrap.setStatus(false);

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(ProjectRequest.Fields.code, "Kode").isMandatory().asString().withMinLen(2).pack()
				.pick(ProjectRequest.Fields.name, "Nama").isMandatory().asString().withMinLen(2).pack()
				.pick(ProjectRequest.Fields.clientId, "Client").isMandatory().asLong().withMinLen(1).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		var cpr = IlKeiEntityValidator.builder().withCriteriaBuilderFactory(em, configBuilder)
				.pick().withEntity(Client.class, "c").whereCriteriaEqual("c.id", src.getClientId())
				.whereCriteriaEqual("c.status.code", "AKT")
				.withErrorMessage(Utility.getErrorMessage("project.project.client_not_found")).pack()
				.validate();
		if (!cpr.getResult()) {
			wrap.setMessage(cpr.getMessage());
			return wrap;
		}

		var qp = new QProject("p");
		var cu = configBuilder.create(em, Project.class).from(Project.class, qp.getMetadata().getName())
				.where(qp.code.toString()).eq(src.getCode())
				.where(qp.status.code.toString()).notEq("REM")
				.getCountQuery().getSingleResult();
		if (cu > 0) {
			wrap.setMessage(Utility.getErrorMessage("project.project.duplicate_item"));
			return wrap;
		}

		var project = new Project();
		project.setCode(src.getCode());
		project.setName(src.getName());
		project.setClient(this.em.find(Client.class, src.getClientId()));
		project.setStatus(statusService.getStatusGeneralByCode("AKT"));

		this.em.persist(project);
		this.em.flush();

		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	@Transactional
	public ApiResponse<ProjectUpdateRequest> updateProject(ProjectUpdateRequest src) {
		var wrap = new ApiResponse<ProjectUpdateRequest>();
		wrap.setStatus(false);

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(ProjectUpdateRequest.Fields.id, "Id Project").isMandatory().asLong().withMinLen(1).pack()
				.pick(ProjectUpdateRequest.Fields.code, "Kode").isMandatory().asString().withMinLen(2).pack()
				.pick(ProjectUpdateRequest.Fields.name, "Nama").isMandatory().asString().withMinLen(2).pack()
				.pick(ProjectUpdateRequest.Fields.clientId, "Client").isMandatory().asLong().withMinLen(1).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		var cpr = IlKeiEntityValidator.builder().withCriteriaBuilderFactory(em, configBuilder)
				.pick().withEntity(Client.class, "c").whereCriteriaEqual("c.id", src.getClientId())
				.whereCriteriaEqual("c.status.code", "AKT")
				.withErrorMessage(Utility.getErrorMessage("project.project.client_not_found")).pack()
				.validate();
		if (!cpr.getResult()) {
			wrap.setMessage(cpr.getMessage());
			return wrap;
		}

		var qp = new QProject("p");
		var cu = configBuilder.create(em, Project.class).from(Project.class, qp.getMetadata().getName())
				.where(qp.code.toString()).eq(src.getCode())
				.where(qp.status.code.toString()).notEq("REM")
				.where(qp.id.toString()).notEq(src.getId())
				.getCountQuery().getSingleResult();
		if (cu > 0) {
			wrap.setMessage(Utility.getErrorMessage("project.project.duplicate_item"));
			return wrap;
		}

		var project = this.em.find(Project.class, src.getId());
		if (project == null) {
			wrap.setMessage(Utility.getErrorMessage("project.project.item_not_found"));
			return wrap;
		}

		project.setCode(src.getCode());
		project.setName(src.getName());
		project.setClient(this.em.find(Client.class, src.getClientId()));

		this.em.merge(project);
		this.em.flush();

		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	@Transactional(readOnly = true)
	public ApiResponse<ProjectListView> listProject(ProjectRequestParameter filter) {
		var wrap = new ApiResponse<ProjectListView>();
		wrap.setStatus(false);

		var qp = new QProject("p");
		var query = configBuilder.create(em, Project.class).from(Project.class, qp.getMetadata().getName());
		if (filter.getKeyword() != null && filter.getKeyword().length() > 2) {
			var kw = "%" + filter.getKeyword().toLowerCase() + "%";
			query = query.whereOr()
					.where("LOWER(" + qp.code.toString() + ")").like().value(kw).noEscape()
					.where("LOWER(" + qp.name.toString() + ")").like().value(kw).noEscape()
					.endOr();
		}
		if (filter.getClientId() != null) {
			query = query.where(qp.client.id.toString()).eq(filter.getClientId());
		}
		query = query.where(qp.status.code.toString()).notEq("REM");

		var num = Utility.getPerPage(filter.getPerPage());
		var of = Utility.getOffsetPage(filter.getPage(), num);
		query = query.orderByDesc(qp.id.toString());

		var res = viewManager.applySetting(EntityViewSetting.create(ProjectListView.class, of, num), query)
				.withKeysetExtraction(true)
				.getResultList();

		wrap.setMaxPage(res.getTotalPages());
		wrap.setData(res.stream().toList());
		wrap.setStatus(true);
		return wrap;
	}

	/**
	 * Soft-deletes a project by setting the general status to {@code REM}. The row is kept so existing
	 * references remain resolvable; every list query filters {@code REM} out.
	 */
	@Transactional
	public ApiResponse<ProjectIdRequest> deleteProject(ProjectIdRequest src) {
		var wrap = new ApiResponse<ProjectIdRequest>();
		wrap.setStatus(false);

		if (src.getId() == null) {
			wrap.setMessage(Utility.getErrorMessage("project.project.item_not_found"));
			return wrap;
		}

		var project = this.em.find(Project.class, src.getId());
		if (project == null) {
			wrap.setMessage(Utility.getErrorMessage("project.project.item_not_found"));
			return wrap;
		}

		var removed = statusService.getStatusGeneralByCode("REM");
		if (removed == null) {
			wrap.setMessage(Utility.getErrorMessage("project.project.status_missing"));
			return wrap;
		}

		project.setStatus(removed);
		this.em.flush();

		src.setId(project.getId());
		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}
}
