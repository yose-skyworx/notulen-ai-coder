package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

/**
 * Published after a document upload commits, to trigger asynchronous processing (extraction →
 * chunking → embedding) without blocking the upload request. Carries only the document id.
 */
public record DocumentUploadedEvent(Long documentId) {
}
