package com.ilkeiapps.skyworx.notulenai.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.DocumentUploadedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bridges upload to asynchronous processing. Listens for {@link DocumentUploadedEvent} after the
 * upload transaction commits (so the document row is visible), then runs the full pipeline on a
 * separate (virtual) thread. Kept in its own bean so the {@code @Async} / {@code @Transactional}
 * boundaries apply via the Spring proxy (no self-invocation).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentProcessingTrigger {

	private final KnowledgeProcessingService processingService;

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onDocumentUploaded(DocumentUploadedEvent event) {
		log.info("async trigger: processing document {} after upload commit", event.documentId());
		try {
			processingService.process(event.documentId());
		} catch (Exception e) {
			// process() already records FAILED on the job/document; this guards the async boundary.
			log.error("async processing failed for document {}", event.documentId(), e);
		}
	}
}
