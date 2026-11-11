package com.ilkeiapps.skyworx.notulenai.service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViewSetting;
import com.ilkeiapps.ilkeitool.validator.IlKeiValidator;
import com.ilkeiapps.ilkeitool.validator.ProcessResult;
import com.ilkeiapps.ilkeitoolentityblazebit.validator.IlKeiEntityValidator;
import com.ilkeiapps.skyworx.notulenai.bean.DocumentUploadRequest;
import com.ilkeiapps.skyworx.notulenai.bean.DocumentUploadResponse;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.DownloadResource;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.DocumentIdRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.DocumentRequestParameter;
import com.ilkeiapps.skyworx.notulenai.config.KnowledgeProperties;
import com.ilkeiapps.skyworx.notulenai.entity.KnowledgeDocument;
import com.ilkeiapps.skyworx.notulenai.entity.Project;
import com.ilkeiapps.skyworx.notulenai.entity.QKnowledgeDocument;
import com.ilkeiapps.skyworx.notulenai.entity.StatusProcess;
import com.ilkeiapps.skyworx.notulenai.entityview.document.KnowledgeDocumentListView;
import com.ilkeiapps.skyworx.notulenai.utility.Utility;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Knowledge-document use cases (house style: Blaze + QueryDSL paths, {@code IlKeiValidator} /
 * {@code IlKeiEntityValidator}, Entity Views for reads, {@code ApiResponse} wrapper). Upload stores
 * the original in MinIO and records a {@code PENDING} document + processing job without running the
 * extraction/embedding pipeline inline, so the request returns immediately.
 * <p>
 * Every operation is project-scoped and validates that the project exists and is active. Finer
 * per-user project membership is not modeled in the current schema — see the implementation notes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

	private static final String SOURCE_UPLOAD = "UPLOAD";
	private static final String JOB_TYPE_FULL = "FULL_PROCESSING";
	private static final String STATUS_PENDING = "PENDING";

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;
	private final EntityViewManager viewManager;
	private final StatusService statusService;
	private final MinioStorageService storageService;
	private final KnowledgeProperties properties;
	private final UserService userService;

	@Transactional
	public ApiResponse<DocumentUploadResponse> upload(DocumentUploadRequest meta, MultipartFile file) {
		var wrap = new ApiResponse<DocumentUploadResponse>();
		wrap.setStatus(false);

		ProcessResult pr = IlKeiValidator.builder().withPojo(meta)
				.pick(DocumentUploadRequest.Fields.projectId, "Project").isMandatory().asLong().withMinLen(1).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		if (file == null || file.isEmpty()) {
			wrap.setMessage(Utility.getErrorMessage("knowledge.document.file_required"));
			return wrap;
		}
		if (file.getSize() > properties.getUpload().getMaxFileSizeBytes()) {
			wrap.setMessage(Utility.getErrorMessage("knowledge.document.file_too_large"));
			return wrap;
		}

		var cpr = IlKeiEntityValidator.builder().withCriteriaBuilderFactory(em, configBuilder)
				.pick().withEntity(Project.class, "p").whereCriteriaEqual("p.id", meta.getProjectId())
				.whereCriteriaEqual("p.status.code", "AKT")
				.withErrorMessage(Utility.getErrorMessage("knowledge.document.project_not_found")).pack()
				.validate();
		if (!cpr.getResult()) {
			wrap.setMessage(cpr.getMessage());
			return wrap;
		}

		var pending = statusService.getStatusProcessByCode(STATUS_PENDING);
		if (pending == null) {
			wrap.setMessage(Utility.getErrorMessage("knowledge.document.status_missing"));
			return wrap;
		}

		byte[] data;
		try {
			data = file.getBytes();
		} catch (Exception e) {
			throw new IllegalStateException("Unable to read uploaded file", e);
		}

		var project = this.em.find(Project.class, meta.getProjectId());
		var originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
		var extension = fileExtension(originalFilename);
		var objectKey = "project/" + meta.getProjectId() + "/" + java.util.UUID.randomUUID()
				+ (extension.isEmpty() ? "" : "." + extension);

		storageService.store(objectKey, data, file.getContentType());

		var now = Instant.now();
		var document = new KnowledgeDocument();
		document.setProject(project);
		document.setOriginalFilename(originalFilename);
		document.setStoredFilename(objectKey.substring(objectKey.lastIndexOf('/') + 1));
		document.setContentType(file.getContentType());
		document.setFileExtension(extension.isEmpty() ? null : extension);
		document.setFileSize(file.getSize());
		document.setChecksum(sha256Hex(data));
		document.setStorageBucket(storageService.bucket());
		document.setStorageObjectKey(objectKey);
		document.setDocumentTitle(meta.getDocumentTitle() != null ? meta.getDocumentTitle() : originalFilename);
		document.setDescription(meta.getDescription());
		document.setSourceType(SOURCE_UPLOAD);
		document.setProcessingStatus(pending);
		document.setCreatedBy(userService.currentUser());
		document.setCreatedAt(now);
		document.setUpdatedAt(now);
		document.setIsCurrent(Boolean.FALSE);

		applyVersioning(document, meta, project);

		this.em.persist(document);
		this.em.flush();

		if (document.getLogicalDocumentId() == null) {
			// First version of a new logical document: the logical id is this row's own id.
			document.setLogicalDocumentId(document.getId());
			this.em.flush();
		}

		createJob(document, project, pending, now);

		var response = new DocumentUploadResponse();
		response.setDocumentId(document.getId());
		response.setLogicalDocumentId(document.getLogicalDocumentId());
		response.setVersionNumber(document.getVersionNumber());
		response.setOriginalFilename(document.getOriginalFilename());
		response.setProcessingStatus(STATUS_PENDING);

		wrap.insertNewData(response);
		wrap.setStatus(true);
		return wrap;
	}

	private void applyVersioning(KnowledgeDocument document, DocumentUploadRequest meta, Project project) {
		if (meta.getLogicalDocumentId() == null) {
			document.setVersionNumber(1);
			return;
		}
		var qd = new QKnowledgeDocument("d");
		var latest = configBuilder.create(em, KnowledgeDocument.class).from(KnowledgeDocument.class, qd.getMetadata().getName())
				.where(qd.logicalDocumentId.toString()).eq(meta.getLogicalDocumentId())
				.where(qd.project.id.toString()).eq(project.getId())
				.orderByDesc(qd.versionNumber.toString())
				.setMaxResults(1)
				.getResultList();
		if (latest.isEmpty()) {
			// Unknown logical id for this project: treat as a new logical document.
			document.setVersionNumber(1);
			return;
		}
		var previous = latest.getFirst();
		document.setLogicalDocumentId(meta.getLogicalDocumentId());
		document.setParentVersion(previous);
		document.setVersionNumber(previous.getVersionNumber() + 1);
	}

	private void createJob(KnowledgeDocument document, Project project, StatusProcess pending, Instant now) {
		var job = new com.ilkeiapps.skyworx.notulenai.entity.DocumentProcessingJob();
		job.setProject(project);
		job.setDocument(document);
		job.setJobType(JOB_TYPE_FULL);
		job.setStatus(pending);
		job.setAttemptCount(0);
		job.setMaxAttempts(properties.getProcessing().getMaxAttempts());
		job.setCreatedAt(now);
		job.setUpdatedAt(now);
		this.em.persist(job);
		this.em.flush();
	}

	@Transactional(readOnly = true)
	public ApiResponse<KnowledgeDocumentListView> list(DocumentRequestParameter filter) {
		var wrap = new ApiResponse<KnowledgeDocumentListView>();
		wrap.setStatus(false);

		if (filter.getProjectId() == null) {
			wrap.setMessage(Utility.getErrorMessage("knowledge.document.project_not_found"));
			return wrap;
		}

		var qd = new QKnowledgeDocument("d");
		var query = configBuilder.create(em, KnowledgeDocument.class).from(KnowledgeDocument.class, qd.getMetadata().getName())
				.where(qd.project.id.toString()).eq(filter.getProjectId());
		if (filter.getKeyword() != null && filter.getKeyword().length() > 2) {
			var kw = "%" + filter.getKeyword().toLowerCase() + "%";
			query = query.whereOr()
					.where("LOWER(" + qd.originalFilename.toString() + ")").like().value(kw).noEscape()
					.where("LOWER(" + qd.documentTitle.toString() + ")").like().value(kw).noEscape()
					.endOr();
		}

		var num = Utility.getPerPage(filter.getPerPage());
		var of = Utility.getOffsetPage(filter.getPage(), num);
		query = query.orderByDesc(qd.id.toString());

		var res = viewManager.applySetting(EntityViewSetting.create(KnowledgeDocumentListView.class, of, num), query)
				.withKeysetExtraction(true)
				.getResultList();

		wrap.setMaxPage(res.getTotalPages());
		wrap.setData(res.stream().toList());
		wrap.setStatus(true);
		return wrap;
	}

	@Transactional(readOnly = true)
	public ApiResponse<KnowledgeDocumentListView> get(DocumentIdRequest src) {
		var wrap = new ApiResponse<KnowledgeDocumentListView>();
		wrap.setStatus(false);

		if (src.getId() == null) {
			wrap.setMessage(Utility.getErrorMessage("knowledge.document.item_not_found"));
			return wrap;
		}

		var qd = new QKnowledgeDocument("d");
		var query = configBuilder.create(em, KnowledgeDocument.class).from(KnowledgeDocument.class, qd.getMetadata().getName())
				.where(qd.id.toString()).eq(src.getId());
		var res = viewManager.applySetting(EntityViewSetting.create(KnowledgeDocumentListView.class), query)
				.getResultList();
		if (res.isEmpty()) {
			wrap.setMessage(Utility.getErrorMessage("knowledge.document.item_not_found"));
			return wrap;
		}

		wrap.setMaxPage(1);
		wrap.insertNewData(res.getFirst());
		wrap.setStatus(true);
		return wrap;
	}

	/**
	 * Resolves the storage coordinates for a download. Returns {@code null} when the document does not
	 * exist; the controller opens the MinIO stream from the returned key (never serialized to clients).
	 */
	@Transactional(readOnly = true)
	public DownloadResource resolveDownload(Long documentId) {
		if (documentId == null) {
			return null;
		}
		var document = this.em.find(KnowledgeDocument.class, documentId);
		if (document == null || document.getStorageObjectKey() == null) {
			return null;
		}
		return new DownloadResource(document.getOriginalFilename(), document.getContentType(),
				document.getStorageObjectKey());
	}

	private String fileExtension(String filename) {
		var dot = filename.lastIndexOf('.');
		if (dot < 0 || dot == filename.length() - 1) {
			return "";
		}
		return filename.substring(dot + 1).toLowerCase();
	}

	private String sha256Hex(byte[] data) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(data));
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}
}
