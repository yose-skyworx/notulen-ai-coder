package com.ilkeiapps.skyworx.notulenai.entityview.document;

import java.time.Instant;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.ilkeiapps.skyworx.notulenai.entity.KnowledgeDocument;
import com.ilkeiapps.skyworx.notulenai.entity.Project;
import com.ilkeiapps.skyworx.notulenai.entity.StatusProcess;

/**
 * Read model for the document list/detail endpoints. Exposes document metadata, version/processing
 * state, and project + processing-status summaries — never storage keys or managed entities.
 */
@EntityView(KnowledgeDocument.class)
public interface KnowledgeDocumentListView {

	@IdMapping
	Long getId();

	Long getLogicalDocumentId();

	Integer getVersionNumber();

	Boolean getIsCurrent();

	String getOriginalFilename();

	String getDocumentTitle();

	String getDescription();

	String getContentType();

	String getFileExtension();

	Long getFileSize();

	String getSourceType();

	String getProcessingError();

	Instant getCreatedAt();

	Instant getUpdatedAt();

	Instant getProcessedAt();

	ProjectView getProject();

	StatusView getProcessingStatus();

	@EntityView(Project.class)
	interface ProjectView {
		@IdMapping
		Long getId();

		String getCode();

		String getName();
	}

	@EntityView(StatusProcess.class)
	interface StatusView {
		@IdMapping
		Integer getId();

		String getCode();

		String getName();
	}
}
