package com.ilkeiapps.skyworx.notulenai.controller;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
import com.ilkeiapps.skyworx.notulenai.service.DocumentStorageService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Knowledge-base document endpoints under {@code /api/knowledge/document} (JWT-guarded via
 * {@code SecurityConfiguration}). Upload returns a document id + processing status immediately; the
 * extraction pipeline runs separately. List/detail return Entity Views; nothing returns a managed
 * entity. Download streams the original from MinIO.
 */
@Slf4j
@RestController
@RequestMapping(path = "/api/knowledge/document")
@RequiredArgsConstructor
public class DocumentController {

	private final KnowledgeDocumentService documentService;
	private final KnowledgeProcessingService processingService;
	private final DocumentStorageService storageService;

	@PostMapping(path = "/upload",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ApiResponse<DocumentUploadResponse> upload(
			@RequestParam(value = "file", required = false) MultipartFile file,
			@RequestParam(value = "projectId", required = false) Long projectId,
			@RequestParam(value = "documentTitle", required = false) String documentTitle,
			@RequestParam(value = "description", required = false) String description,
			@RequestParam(value = "logicalDocumentId", required = false) Long logicalDocumentId,
			HttpServletRequest request) {
		if (file == null || file.isEmpty()) {
			// Diagnostic: shows whether the request actually arrived as a parsed multipart with a 'file'
			// part. A content-type without "boundary=" means the client overrode the Content-Type header.
			log.warn("upload: 'file' part missing/empty. contentType='{}', multipart={}, partNames={}",
					request.getContentType(), request instanceof org.springframework.web.multipart.MultipartHttpServletRequest,
					safePartNames(request));
		}
		var meta = new DocumentUploadRequest();
		meta.setProjectId(projectId);
		meta.setDocumentTitle(documentTitle);
		meta.setDescription(description);
		meta.setLogicalDocumentId(logicalDocumentId);
		return documentService.upload(meta, file);
	}

	/** Best-effort list of multipart part names, for diagnosing failed uploads. */
	private String safePartNames(HttpServletRequest request) {
		try {
			var names = new java.util.ArrayList<String>();
			for (var part : request.getParts()) {
				names.add(part.getName() + "(" + part.getSize() + "B)");
			}
			return names.toString();
		} catch (Exception e) {
			return "<unavailable: " + e.getClass().getSimpleName() + ">";
		}
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
	 * Runs the full processing pipeline (extract → normalize → chunk → embed → persist) synchronously
	 * and returns element/chunk counts. Upload also triggers this automatically (asynchronously), so
	 * this endpoint is mainly for on-demand (re)processing and verification.
	 */
	@PostMapping(path = "/process",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<String> process(@RequestBody DocumentIdRequest req) {
		return processingService.process(req.getId());
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
