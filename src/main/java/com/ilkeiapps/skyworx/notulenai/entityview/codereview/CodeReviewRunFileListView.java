package com.ilkeiapps.skyworx.notulenai.entityview.codereview;

import java.time.Instant;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.bean.enums.FileChangeStatus;
import com.ilkeiapps.skyworx.notulenai.bean.enums.ReviewRoleType;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewGuideline;
import com.ilkeiapps.skyworx.notulenai.entity.CodeReviewRunFile;
import com.ilkeiapps.skyworx.notulenai.entity.StatusProcess;

/**
 * Read model for the per-file breakdown of a run. Exposes why a file was skipped, what standard it was
 * measured against, and how far its segmented review got, which is what an operator needs to tell a
 * genuinely clean file from one the pipeline never reached. Omits {@code diffHash}: it is an internal
 * idempotency key with no meaning outside the service.
 */
@EntityView(CodeReviewRunFile.class)
public interface CodeReviewRunFileListView {

	@IdMapping
	Long getId();

	String getFilePath();

	String getOldFilePath();

	FileChangeStatus getChangeStatus();

	String getLanguage();

	/** Null when the file plays none of the reviewed roles, which is itself the reason it was skipped. */
	ReviewRoleType getRoleType();

	GuidelineView getGuideline();

	Integer getAdditions();

	Integer getDeletions();

	String getSkipReason();

	Integer getSegmentCount();

	Integer getSegmentsCompleted();

	Instant getStartedAt();

	Instant getCompletedAt();

	String getErrorMessage();

	StatusView getStatus();

	/**
	 * The guideline rule this file was reviewed against. Identity and title only — {@code content} is
	 * deliberately absent, because it runs to thousands of characters and this view is returned once per
	 * file in a run. A caller that wants the wording asks for the guideline by id.
	 */
	@EntityView(CodeReviewGuideline.class)
	interface GuidelineView {
		@IdMapping
		Long getId();

		ReviewRoleType getRoleType();

		String getCode();

		String getTitle();
	}

	@EntityView(StatusProcess.class)
	interface StatusView {
		@IdMapping
		Integer getId();

		String getCode();

		String getName();
	}
}
