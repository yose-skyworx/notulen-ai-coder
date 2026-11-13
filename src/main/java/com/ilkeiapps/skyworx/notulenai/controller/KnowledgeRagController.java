package com.ilkeiapps.skyworx.notulenai.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.KnowledgeSearchResult;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.RagAnswer;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.KnowledgeAskRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.KnowledgeSearchRequest;
import com.ilkeiapps.skyworx.notulenai.service.KnowledgeRagService;

import lombok.RequiredArgsConstructor;

/**
 * Project knowledge-base retrieval endpoints under {@code /api/knowledge} (JWT-guarded via
 * {@code SecurityConfiguration}). {@code /search} returns project-scoped semantic-search hits;
 * {@code /ask} returns a RAG answer grounded in the project's documents, with citations. Both validate
 * project access in the service; neither returns a managed entity.
 */
@RestController
@RequestMapping(path = "/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeRagController {

	private final KnowledgeRagService ragService;

	@PostMapping(path = "/search",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<KnowledgeSearchResult> search(@RequestBody KnowledgeSearchRequest req) {
		return ragService.search(req);
	}

	@PostMapping(path = "/ask",
			produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<RagAnswer> ask(@RequestBody KnowledgeAskRequest req) {
		return ragService.ask(req);
	}
}
