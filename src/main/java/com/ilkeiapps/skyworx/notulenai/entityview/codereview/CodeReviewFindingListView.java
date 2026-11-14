package com.ilkeiapps.skyworx.notulenai.entityview.codereview;

import java.math.BigDecimal;
import java.time.Instant;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.bean.enums.DiffSide;
import com.ilkeiapps.skyworx.notulenai.bean.enums.FindingValidationStatus;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewSeverity;
import com.ilkeiapps.skyworx.notulenai.entity.CodeRepository;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewFinding;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewRun;

/**
 * Read model for a reported finding: where it is, how bad it is, what the evidence was, and whether
 * the deterministic validator accepted the model's claim.
 * <p>
 * The nested run exposes {@code selectedCommitSha} alongside the repository owner and name so a caller
 * can build a permalink that resolves to the exact reviewed revision. Pointing at branch HEAD instead
 * would drift as soon as the branch moved and silently misattribute the finding.
 * <p>
 * {@code validationStatus} is exposed rather than filtered out: a finding the validator rejected
 * ({@code INVALID_LINE}, {@code MISSING_EVIDENCE}, …) is still worth showing as rejected, because
 * hiding it would make a broken review look like a clean one.
 */
@EntityView(CodeReviewFinding.class)
public interface CodeReviewFindingListView {

	@IdMapping
	Long getId();

	String getFilePath();

	Integer getLineNumber();

	Integer getEndLineNumber();

	DiffSide getSide();

	String getRuleId();

	ReviewSeverity getSeverity();

	String getCategory();

	String getTitle();

	String getDescription();

	String getEvidence();

	String getRecommendation();

	String getGuidelineReference();

	BigDecimal getConfidence();

	FindingValidationStatus getValidationStatus();

	String getValidationDetail();

	Instant getCreatedAt();

	RunView getRun();

	@EntityView(CodeReviewRun.class)
	interface RunView {
		@IdMapping
		Long getId();

		String getSelectedCommitSha();

		RepositoryView getRepository();

		@EntityView(CodeRepository.class)
		interface RepositoryView {
			@IdMapping
			Long getId();

			String getOwner();

			String getRepoName();
		}
	}
}
