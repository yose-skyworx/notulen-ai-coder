package com.ilkeiapps.skyworx.notulenai.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViewSetting;
import com.ilkeiapps.ilkeitool.validator.IlKeiValidator;
import com.ilkeiapps.ilkeitool.validator.ProcessResult;
import com.ilkeiapps.ilkeitoolentityblazebit.validator.IlKeiEntityValidator;
import com.ilkeiapps.skyworx.notulenai.bean.CodeRepositoryRequest;
import com.ilkeiapps.skyworx.notulenai.bean.CodeRepositoryUpdateRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeRepositoryIdRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeRepositoryRequestParameter;
import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;
import com.ilkeiapps.skyworx.notulenai.entity.CodeRepository;
import com.ilkeiapps.skyworx.notulenai.entity.Project;
import com.ilkeiapps.skyworx.notulenai.entity.QCodeRepository;
import com.ilkeiapps.skyworx.notulenai.entityview.codereview.CodeRepositoryListView;
import com.ilkeiapps.skyworx.notulenai.utility.Utility;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CRUD for the GitHub repositories registered for review, following the {@code ProjectService} house
 * style: Blaze-Persistence queries with QueryDSL path metadata, {@code IlKeiValidator} /
 * {@code IlKeiEntityValidator} for input and foreign-key checks, Entity Views for reads, and a soft
 * delete that flips the general status to {@code REM}.
 * <p>
 * No method here touches a credential or calls GitHub. The personal access token is deployment-wide
 * configuration ({@code code-review.github.token}), never per-repository state, so registering a
 * repository is a purely local write — which also keeps the remote round trip outside the
 * {@code @Transactional} boundary, where it would hold a pooled connection for the duration. Live
 * connectivity checking is a separate, non-transactional operation.
 * <p>
 * {@code defaultBranch} is never taken from the request — it is discovered from the GitHub API, so
 * accepting it would let a client point reviews at a branch that does not exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeRepositoryService {

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;
	private final EntityViewManager viewManager;
	private final StatusService statusService;
	private final CodeReviewProperties properties;

	@Transactional
	public ApiResponse<CodeRepositoryRequest> saveRepository(CodeRepositoryRequest src) {
		var wrap = new ApiResponse<CodeRepositoryRequest>();
		wrap.setStatus(false);

		if (!properties.isEnabled()) {
			wrap.setMessage(Utility.getErrorMessage("codereview.module_disabled"));
			return wrap;
		}

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(CodeRepositoryRequest.Fields.projectId, "Project").isMandatory().asLong().withMinLen(1).pack()
				.pick(CodeRepositoryRequest.Fields.owner, "Owner").isMandatory().asString().withMinLen(1).pack()
				.pick(CodeRepositoryRequest.Fields.repoName, "Nama Repository").isMandatory().asString().withMinLen(1)
				.pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		if (!projectIsActive(src.getProjectId())) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.project_not_found"));
			return wrap;
		}

		var qr = new QCodeRepository("r");
		var cu = configBuilder.create(em, CodeRepository.class).from(CodeRepository.class, qr.getMetadata().getName())
				.where(qr.project.id.toString()).eq(src.getProjectId())
				.where("LOWER(" + qr.owner.toString() + ")").eq(src.getOwner().toLowerCase())
				.where("LOWER(" + qr.repoName.toString() + ")").eq(src.getRepoName().toLowerCase())
				.where(qr.status.code.toString()).notEq("REM")
				.getCountQuery().getSingleResult();
		if (cu > 0) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.duplicate_item"));
			return wrap;
		}

		var active = statusService.getStatusGeneralByCode("AKT");
		if (active == null) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.status_missing"));
			return wrap;
		}

		var now = Instant.now();
		var repository = new CodeRepository();
		repository.setProject(this.em.find(Project.class, src.getProjectId()));
		repository.setOwner(src.getOwner().trim());
		repository.setRepoName(src.getRepoName().trim());
		repository.setEnabled(src.getEnabled() == null || src.getEnabled());
		repository.setStatus(active);
		repository.setCreatedAt(now);
		repository.setUpdatedAt(now);

		this.em.persist(repository);
		this.em.flush();

		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	@Transactional
	public ApiResponse<CodeRepositoryUpdateRequest> updateRepository(CodeRepositoryUpdateRequest src) {
		var wrap = new ApiResponse<CodeRepositoryUpdateRequest>();
		wrap.setStatus(false);

		if (!properties.isEnabled()) {
			wrap.setMessage(Utility.getErrorMessage("codereview.module_disabled"));
			return wrap;
		}

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(CodeRepositoryUpdateRequest.Fields.id, "Id Repository").isMandatory().asLong().withMinLen(1)
				.pack()
				.pick(CodeRepositoryUpdateRequest.Fields.projectId, "Project").isMandatory().asLong().withMinLen(1)
				.pack()
				.pick(CodeRepositoryUpdateRequest.Fields.owner, "Owner").isMandatory().asString().withMinLen(1).pack()
				.pick(CodeRepositoryUpdateRequest.Fields.repoName, "Nama Repository").isMandatory().asString()
				.withMinLen(1).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		if (!projectIsActive(src.getProjectId())) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.project_not_found"));
			return wrap;
		}

		var qr = new QCodeRepository("r");
		var cu = configBuilder.create(em, CodeRepository.class).from(CodeRepository.class, qr.getMetadata().getName())
				.where(qr.project.id.toString()).eq(src.getProjectId())
				.where("LOWER(" + qr.owner.toString() + ")").eq(src.getOwner().toLowerCase())
				.where("LOWER(" + qr.repoName.toString() + ")").eq(src.getRepoName().toLowerCase())
				.where(qr.status.code.toString()).notEq("REM")
				.where(qr.id.toString()).notEq(src.getId())
				.getCountQuery().getSingleResult();
		if (cu > 0) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.duplicate_item"));
			return wrap;
		}

		var repository = this.em.find(CodeRepository.class, src.getId());
		if (repository == null || "REM".equals(repository.getStatus().getCode())) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.item_not_found"));
			return wrap;
		}

		repository.setProject(this.em.find(Project.class, src.getProjectId()));
		repository.setOwner(src.getOwner().trim());
		repository.setRepoName(src.getRepoName().trim());
		if (src.getEnabled() != null) {
			repository.setEnabled(src.getEnabled());
		}
		repository.setUpdatedAt(Instant.now());

		this.em.merge(repository);
		this.em.flush();

		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	@Transactional(readOnly = true)
	public ApiResponse<CodeRepositoryListView> listRepository(CodeRepositoryRequestParameter filter) {
		var wrap = new ApiResponse<CodeRepositoryListView>();
		wrap.setStatus(false);

		if (filter.getProjectId() == null) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.project_not_found"));
			return wrap;
		}
		if (!projectIsActive(filter.getProjectId())) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.project_not_found"));
			return wrap;
		}

		var qr = new QCodeRepository("r");
		var query = configBuilder.create(em, CodeRepository.class)
				.from(CodeRepository.class, qr.getMetadata().getName())
				.where(qr.project.id.toString()).eq(filter.getProjectId())
				.where(qr.status.code.toString()).notEq("REM");

		if (filter.getKeyword() != null && filter.getKeyword().length() > 2) {
			var kw = "%" + filter.getKeyword().toLowerCase() + "%";
			query = query.whereOr()
					.where("LOWER(" + qr.owner.toString() + ")").like().value(kw).noEscape()
					.where("LOWER(" + qr.repoName.toString() + ")").like().value(kw).noEscape()
					.endOr();
		}

		var num = Utility.getPerPage(filter.getPerPage());
		var of = Utility.getOffsetPage(filter.getPage(), num);
		query = query.orderByDesc(qr.id.toString());

		var res = viewManager.applySetting(EntityViewSetting.create(CodeRepositoryListView.class, of, num), query)
				.withKeysetExtraction(true)
				.getResultList();

		wrap.setMaxPage(res.getTotalPages());
		wrap.setData(res.stream().toList());
		wrap.setStatus(true);
		return wrap;
	}

	/**
	 * Soft-deletes a repository by setting the general status to {@code REM}. The row is kept so
	 * historical runs stay resolvable; every list query filters {@code REM} out.
	 */
	@Transactional
	public ApiResponse<CodeRepositoryIdRequest> deleteRepository(CodeRepositoryIdRequest src) {
		var wrap = new ApiResponse<CodeRepositoryIdRequest>();
		wrap.setStatus(false);

		if (src.getId() == null) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.item_not_found"));
			return wrap;
		}

		var repository = this.em.find(CodeRepository.class, src.getId());
		if (repository == null) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.item_not_found"));
			return wrap;
		}

		var removed = statusService.getStatusGeneralByCode("REM");
		if (removed == null) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.status_missing"));
			return wrap;
		}

		repository.setStatus(removed);
		repository.setEnabled(false);
		repository.setUpdatedAt(Instant.now());
		this.em.flush();

		src.setId(repository.getId());
		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	/** Loads a repository for other services, or {@code null} when it is absent or soft-deleted. */
	@Transactional(readOnly = true)
	public CodeRepository findActiveRepository(Long repositoryId) {
		if (repositoryId == null) {
			return null;
		}
		var repository = this.em.find(CodeRepository.class, repositoryId);
		if (repository == null || "REM".equals(repository.getStatus().getCode())) {
			return null;
		}
		return repository;
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
