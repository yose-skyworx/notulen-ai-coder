package com.ilkeiapps.skyworx.notulenai.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViewSetting;
import com.ilkeiapps.ilkeitool.validator.IlKeiValidator;
import com.ilkeiapps.ilkeitool.validator.ProcessResult;
import com.ilkeiapps.ilkeitoolentityblazebit.validator.IlKeiEntityValidator;
import com.ilkeiapps.skyworx.notulenai.bean.CodeReviewBranchRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewBranchRequestParameter;
import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewBranch;
import com.ilkeiapps.skyworx.notulenai.entity.Project;
import com.ilkeiapps.skyworx.notulenai.entity.QCodeReviewBranch;
import com.ilkeiapps.skyworx.notulenai.entityview.codereview.CodeReviewBranchListView;
import com.ilkeiapps.skyworx.notulenai.utility.Utility;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Maintains the set of branches selected for review on a repository.
 * <p>
 * Saving replaces the whole selection by flag rather than by deletion. {@code code_review_runs.branch_id}
 * references these rows, so removing a deselected branch would orphan the history of every run that
 * reviewed it; instead it is left in place and disabled. Re-selecting a previously disabled branch
 * re-enables the existing row rather than inserting a second one, which is what the
 * {@code uq_crb_repo_branch} unique constraint requires.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeReviewBranchService {

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;
	private final EntityViewManager viewManager;
	private final CodeRepositoryService codeRepositoryService;
	private final CodeReviewProperties properties;

	@Transactional
	public ApiResponse<CodeReviewBranchRequest> saveBranches(CodeReviewBranchRequest src) {
		var wrap = new ApiResponse<CodeReviewBranchRequest>();
		wrap.setStatus(false);

		if (!properties.isEnabled()) {
			wrap.setMessage(Utility.getErrorMessage("codereview.module_disabled"));
			return wrap;
		}

		ProcessResult pr = IlKeiValidator.builder().withPojo(src)
				.pick(CodeReviewBranchRequest.Fields.repositoryId, "Repository").isMandatory().asLong().withMinLen(1)
				.pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
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

		var selected = normalize(src.getBranchNames());
		var enabled = src.getEnabled() == null || src.getEnabled();
		var now = Instant.now();

		var qb = new QCodeReviewBranch("b");
		var existing = configBuilder.create(em, CodeReviewBranch.class)
				.from(CodeReviewBranch.class, qb.getMetadata().getName())
				.where(qb.repository.id.toString()).eq(repository.getId())
				.getResultList();

		var matched = new LinkedHashSet<String>();
		for (var branch : existing) {
			if (selected.contains(branch.getBranchName())) {
				branch.setEnabled(enabled);
				matched.add(branch.getBranchName());
			} else {
				branch.setEnabled(false);
			}
			branch.setUpdatedAt(now);
		}

		for (var branchName : selected) {
			if (matched.contains(branchName)) {
				continue;
			}
			var branch = new CodeReviewBranch();
			branch.setRepository(repository);
			branch.setBranchName(branchName);
			branch.setEnabled(enabled);
			branch.setCreatedAt(now);
			branch.setUpdatedAt(now);
			this.em.persist(branch);
		}

		this.em.flush();

		src.setBranchNames(new ArrayList<>(selected));
		wrap.insertNewData(src);
		wrap.setStatus(true);
		return wrap;
	}

	@Transactional(readOnly = true)
	public ApiResponse<CodeReviewBranchListView> listBranch(CodeReviewBranchRequestParameter filter) {
		var wrap = new ApiResponse<CodeReviewBranchListView>();
		wrap.setStatus(false);

		if (filter.getRepositoryId() == null) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.item_not_found"));
			return wrap;
		}
		if (codeRepositoryService.findActiveRepository(filter.getRepositoryId()) == null) {
			wrap.setMessage(Utility.getErrorMessage("codereview.repository.item_not_found"));
			return wrap;
		}

		var qb = new QCodeReviewBranch("b");
		var query = configBuilder.create(em, CodeReviewBranch.class)
				.from(CodeReviewBranch.class, qb.getMetadata().getName())
				.where(qb.repository.id.toString()).eq(filter.getRepositoryId());

		var num = Utility.getPerPage(filter.getPerPage());
		var of = Utility.getOffsetPage(filter.getPage(), num);
		query = query.orderByAsc(qb.branchName.toString());

		var res = viewManager.applySetting(EntityViewSetting.create(CodeReviewBranchListView.class, of, num), query)
				.withKeysetExtraction(true)
				.getResultList();

		wrap.setMaxPage(res.getTotalPages());
		wrap.setData(res.stream().toList());
		wrap.setStatus(true);
		return wrap;
	}

	/**
	 * Trims, drops blanks, and de-duplicates while preserving the client's order. Git branch names are
	 * case-sensitive, so comparison is exact rather than folded.
	 */
	private LinkedHashSet<String> normalize(List<String> branchNames) {
		var out = new LinkedHashSet<String>();
		if (branchNames == null) {
			return out;
		}
		for (var name : branchNames) {
			if (name == null) {
				continue;
			}
			var trimmed = name.trim();
			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}
		return out;
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
