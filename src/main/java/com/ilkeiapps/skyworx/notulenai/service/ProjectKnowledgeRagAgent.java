package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayList;
import java.util.List;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.common.ai.model.LlmOptions;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.Citation;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.RagAnswer;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.RagQuery;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.RerankedContext;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.RetrievedChunks;
import com.ilkeiapps.skyworx.notulenai.config.KnowledgeProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Embabel agent that answers a question against a single project's knowledge base. The GOAP plan is
 * {@code retrieve} → {@code answer}: the first action runs project-scoped semantic retrieval, the second
 * narrows those chunks to the passage that answers the question and has the LLM restate it.
 * <p>
 * The answer action is built around what the deployed chat model can actually do. {@code lfm-1.2b} is the
 * only working chat model on the inference server, and measured against this corpus it answers correctly
 * from one short passage with one instruction, but degrades sharply with more: a few thousand tokens of
 * context, a second passage, a system prompt, or a structured-output schema each turn a correct answer
 * into a wrong or evasive one. So {@link PassageRerankerService} selects the passage, the prompt is plain
 * text with a single instruction and no system prompt, and {@code grounded} plus {@code citations} are
 * derived here rather than asked of the model — the model is never trusted with anything but the wording
 * of the answer, and a citation still always points at a chunk that was really retrieved.
 * <p>
 * When nothing in the retrieved chunks matches the question's terms the agent reports insufficient
 * <em>without</em> calling the LLM: given a context that does not contain the answer, the model invents one
 * instead of saying so.
 * <p>
 * The agent performs <em>no</em> authorization — {@code KnowledgeRagService} validates project access
 * before invoking it. {@code @Agent} is meta-annotated {@code @Component}, so this is a Spring bean;
 * Embabel's annotation scanning registers it with the platform at startup. Neither action is
 * {@code @Transactional}: retrieval embeds the query (remote) and the answer action calls the LLM
 * (remote), so no database transaction is held across either network call.
 */
@Slf4j
@Agent(
		name = "projectKnowledgeRag",
		provider = "notulen-ai",
		description = "Answer a question grounded in a project's knowledge-base documents, with citations")
@RequiredArgsConstructor
public class ProjectKnowledgeRagAgent {

	private static final String NOT_IN_DOCUMENTS =
			"Basis pengetahuan proyek tidak memuat informasi untuk menjawab pertanyaan ini.";
	private static final String NOT_GENERATED = "Jawaban tidak dapat dihasilkan dari basis pengetahuan proyek.";

	private final DocumentRetrievalService retrievalService;
	private final PassageRerankerService reranker;
	private final KnowledgeProperties properties;

	@Action
	public RetrievedChunks retrieve(RagQuery query) {
		var results = retrievalService.retrieve(query.getProjectId(), query.getQuestion(), query.getTopK());
		log.info("rag.retrieve: project={} question='{}' -> {} chunk(s)", query.getProjectId(),
				preview(query.getQuestion()), results.size());
		return new RetrievedChunks(results);
	}

	@AchievesGoal(description = "A grounded answer to the project question, with source citations")
	@Action
	public RagAnswer answer(RagQuery query, RetrievedChunks chunks, OperationContext context) {
		var question = query.getQuestion();
		var results = chunks.getResults();
		if (results.isEmpty()) {
			log.info("rag.answer: no context for project={}; returning insufficient", query.getProjectId());
			return RagAnswer.insufficient(question, NOT_IN_DOCUMENTS);
		}

		var grounding = reranker.select(question, results);
		if (grounding.isEmpty()) {
			log.info("rag.answer: project={} question='{}' matched no passage in {} chunk(s); "
					+ "returning insufficient without an LLM call", query.getProjectId(), preview(question),
					results.size());
			return RagAnswer.insufficient(question, NOT_IN_DOCUMENTS);
		}

		var prompt = buildPrompt(question, grounding);
		var debug = properties.getRag().isDebugLog();
		if (debug) {
			log.info("rag.answer prompt ({} chars):\n{}", prompt.length(), prompt);
		}

		var generated = context.ai()
				.withLlm(LlmOptions.withDefaultLlm().withTemperature(properties.getRag().getTemperature()))
				.generateText(prompt);

		var body = generated != null ? generated.strip() : "";
		if (debug) {
			log.info("rag.answer generated: {}", body);
		}
		if (body.isEmpty()) {
			log.warn("rag.answer: model returned an empty answer for project={}", query.getProjectId());
			return RagAnswer.insufficient(question, NOT_GENERATED);
		}

		var answer = new RagAnswer();
		answer.setQuestion(question);
		answer.setAnswer(body);
		// The passages were matched from retrieved chunks by this service, not asserted by the model, so the
		// answer is grounded by construction and its citations are exactly the chunks that were used.
		answer.setGrounded(true);
		answer.setCitations(citations(grounding));
		return answer;
	}

	/**
	 * Context first, then the question, then one instruction. Every additional instruction was measured to
	 * cost accuracy on this model: asking it to cite sources made it name the document title as a parameter
	 * value, and offering a "not in the documents" escape made it take that escape on a context that did
	 * contain the answer. The one instruction deliberately does not constrain the length — asked for a single
	 * sentence, the model answered a list-shaped question with the section intro that headed the list and
	 * dropped every item in it, while the same passages answer correctly with the constraint removed.
	 */
	private String buildPrompt(String question, RerankedContext grounding) {
		var asked = question.strip();
		if (!asked.endsWith("?") && !asked.endsWith(".")) {
			asked = asked + "?";
		}
		return grounding.toPromptContext()
				+ "\n\nBerdasarkan teks di atas, " + asked + " Jawab dalam Bahasa Indonesia.";
	}

	private List<Citation> citations(RerankedContext grounding) {
		var out = new ArrayList<Citation>(grounding.getPassages().size());
		for (var passage : grounding.getPassages()) {
			out.add(Citation.from(passage.source()));
		}
		return out;
	}

	private String preview(String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= 80 ? text : text.substring(0, 80) + "…";
	}
}
