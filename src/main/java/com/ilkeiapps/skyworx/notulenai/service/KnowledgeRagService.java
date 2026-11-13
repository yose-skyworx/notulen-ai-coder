package com.ilkeiapps.skyworx.notulenai.service;

import org.springframework.stereotype.Service;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.ilkeiapps.ilkeitool.validator.IlKeiValidator;
import com.ilkeiapps.ilkeitoolentityblazebit.validator.IlKeiEntityValidator;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.KnowledgeSearchResult;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.RagAnswer;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.RagQuery;
import com.ilkeiapps.skyworx.notulenai.bean.web.ApiResponse;
import com.ilkeiapps.skyworx.notulenai.bean.web.KnowledgeAskRequest;
import com.ilkeiapps.skyworx.notulenai.bean.web.KnowledgeSearchRequest;
import com.ilkeiapps.skyworx.notulenai.entity.Project;
import com.ilkeiapps.skyworx.notulenai.utility.Utility;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Project-scoped semantic search and RAG question answering. Both operations validate that the target
 * project exists and is active before any retrieval, so cross-project access is never possible.
 * <p>
 * Neither method is {@code @Transactional}: search embeds the query (remote) and ask additionally
 * invokes the Embabel RAG agent, which calls the LLM (remote). Per the project's transaction rules,
 * those network calls must not run inside a database transaction. The project-existence check and the
 * vector read each run as their own short unit of work.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRagService {

	private final EntityManager em;
	private final CriteriaBuilderFactory configBuilder;
	private final DocumentRetrievalService retrievalService;
	private final AgentPlatform agentPlatform;

	public ApiResponse<KnowledgeSearchResult> search(KnowledgeSearchRequest src) {
		var wrap = new ApiResponse<KnowledgeSearchResult>();
		wrap.setStatus(false);

		var pr = IlKeiValidator.builder().withPojo(src)
				.pick(KnowledgeSearchRequest.Fields.projectId, "Project").isMandatory().asLong().withMinLen(1).pack()
				.pick(KnowledgeSearchRequest.Fields.query, "Query").isMandatory().asString().withMinLen(2).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		if (!projectIsActive(src.getProjectId())) {
			wrap.setMessage(Utility.getErrorMessage("knowledge.document.project_not_found"));
			return wrap;
		}

		var results = retrievalService.retrieve(src.getProjectId(), src.getQuery(), src.getTopK());
		wrap.setMaxPage(1);
		wrap.setData(results);
		wrap.setStatus(true);
		return wrap;
	}

	public ApiResponse<RagAnswer> ask(KnowledgeAskRequest src) {
		var wrap = new ApiResponse<RagAnswer>();
		wrap.setStatus(false);

		var pr = IlKeiValidator.builder().withPojo(src)
				.pick(KnowledgeAskRequest.Fields.projectId, "Project").isMandatory().asLong().withMinLen(1).pack()
				.pick(KnowledgeAskRequest.Fields.question, "Question").isMandatory().asString().withMinLen(2).pack()
				.validate();
		if (!pr.getResult()) {
			wrap.setMessage(pr.getMessage());
			return wrap;
		}

		if (!projectIsActive(src.getProjectId())) {
			wrap.setMessage(Utility.getErrorMessage("knowledge.document.project_not_found"));
			return wrap;
		}

		var query = new RagQuery(src.getProjectId(), src.getQuestion(), src.getTopK());
		RagAnswer answer;
		try {
			answer = AgentInvocation.create(agentPlatform, RagAnswer.class).invoke(query);
		} catch (Exception e) {
			// Planning/LLM/retrieval failure: report a safe message, keep the real cause in the log only.
			log.error("ask: RAG invocation failed for project={}: {}", src.getProjectId(), e.getMessage(), e);
			wrap.setMessage(Utility.getErrorMessage("knowledge.rag.answer_failed"));
			return wrap;
		}

		if (answer == null) {
			wrap.setMessage(Utility.getErrorMessage("knowledge.rag.answer_failed"));
			return wrap;
		}

		wrap.setMaxPage(1);
		wrap.insertNewData(answer);
		wrap.setStatus(true);
		return wrap;
	}

	/** Project exists and is active ({@code AKT}). Runs as its own short read via Blaze criteria. */
	private boolean projectIsActive(Long projectId) {
		var vr = IlKeiEntityValidator.builder().withCriteriaBuilderFactory(em, configBuilder)
				.pick().withEntity(Project.class, "p").whereCriteriaEqual("p.id", projectId)
				.whereCriteriaEqual("p.status.code", "AKT")
				.withErrorMessage(Utility.getErrorMessage("knowledge.document.project_not_found")).pack()
				.validate();
		return vr.getResult();
	}
}
