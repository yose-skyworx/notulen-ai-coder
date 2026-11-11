package com.ilkeiapps.skyworx.notulenai.controller;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ilkeiapps.skyworx.notulenai.bean.DocumentUploadRequest;
import com.ilkeiapps.skyworx.notulenai.bean.DocumentUploadResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.DocumentIdRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.DocumentRequestParameter;
import com.ilkeiapps.skyworx.notulenai.entityview.document.KnowledgeDocumentListView;
import com.ilkeiapps.skyworx.notulenai.service.KnowledgeDocumentService;
import com.ilkeiapps.skyworx.notulenai.service.KnowledgeProcessingService;
import com.ilkeiapps.skyworx.notulenai.service.MinioStorageService;

import lombok.RequiredArgsConstructor;

/**
 * Knowledge-base document endpoints under {@code /api/knowledge/document} (JWT-guarded via
 * {@code SecurityConfiguration}). Upload returns a document id + processing status immediately; the
 * extraction pipeline runs separately. List/detail return Entity Views; nothing returns a managed
 * entity. Download streams the original from MinIO.
 */
@RestController
@RequestMapping(path = "/api/knowledge/document")
@RequiredArgsConstructor
public class DocumentController {

	private final KnowledgeDocumentService documentService;
	private final KnowledgeProcessingService processingService;
	private final MinioStorageService storageService;

	@PostMapping(path = "/upload",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ApiResponse<DocumentUploadResponse> upload(@ModelAttribute DocumentUploadRequest meta,
			@RequestParam("file") MultipartFile file) {
		return documentService.upload(meta, file);
	}

	@GetMapping(path = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<KnowledgeDocumentListView> list(DocumentRequestParameter filter) {
		return documentService.list(filter);
	}

	@PostMapping(path = "/get",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<KnowledgeDocumentListView> get(@RequestBody DocumentIdRequest req) {
		return documentService.get(req);
	}

	/**
	 * Runs extraction + normalization for a document (Phase 1: synchronous trigger). Phase 2 replaces
	 * this with asynchronous, automatically-queued processing.
	 */
	@PostMapping(path = "/process",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<String> process(@RequestBody DocumentIdRequest req) {
		return processingService.extractAndNormalize(req.getId());
	}

	@GetMapping(path = "/download")
	public ResponseEntity<InputStreamResource> download(@RequestParam("id") Long id) {
		var resource = documentService.resolveDownload(id);
		if (resource == null) {
			return ResponseEntity.notFound().build();
		}
		var stream = storageService.openStream(resource.objectKey());
		var contentType = resource.contentType() != null
				? MediaType.parseMediaType(resource.contentType()) : MediaType.APPLICATION_OCTET_STREAM;
		var disposition = ContentDisposition.attachment().filename(resource.filename()).build();
		return ResponseEntity.ok()
				.contentType(contentType)
				.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
				.body(new InputStreamResource(stream));
	}
}
