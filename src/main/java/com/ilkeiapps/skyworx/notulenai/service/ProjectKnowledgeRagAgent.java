package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.common.ai.model.LlmOptions;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.Citation;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.KnowledgeSearchResult;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.RagAnswer;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.RagQuery;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.RetrievedChunks;
import com.ilkeiapps.skyworx.notulenai.config.KnowledgeProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Embabel agent that answers a question against a single project's knowledge base. The GOAP plan is
 * {@code retrieve} → {@code answer}: the first action runs project-scoped semantic retrieval, the
 * second grounds an LLM answer on the retrieved chunks and attaches real citations.
 * <p>
 * The answer is generated as <em>plain text</em> (not a structured object): the configured model
 * ({@code lfm-1.2b}) is small and cannot reliably produce the JSON that structured output binding
 * requires, so demanding it caused repeated parse-retry failures. Instead the LLM is told to answer
 * only from the numbered context and to cite sources as {@code [n]}; citations are then rebuilt from the
 * actually-retrieved chunks by parsing those markers (falling back to all retrieved chunks), so a
 * document or page reference can never be fabricated. The agent performs <em>no</em> authorization —
 * {@code KnowledgeRagService} validates project access before invoking it.
 * <p>
 * {@code @Agent} is meta-annotated {@code @Component}, so this is a Spring bean; Embabel's annotation
 * scanning registers it with the platform at startup. Neither action is {@code @Transactional}: the
 * retrieval embeds the query (remote) and the answer action calls the LLM (remote), so no database
 * transaction is held across either network call.
 */
@Slf4j
@Agent(
		name = "projectKnowledgeRag",
		provider = "notulen-ai",
		description = "Answer a question grounded in a project's knowledge-base documents, with citations")
@RequiredArgsConstructor
public class ProjectKnowledgeRagAgent {

	private static final String SYSTEM_PROMPT = """
			You are a retrieval-augmented assistant for a project knowledge base.
			Answer ONLY using the numbered context sources provided. Do not use outside knowledge.
			Cite the sources you use by their number in square brackets, e.g. [1] or [2].
			If the context does not contain enough information to answer, reply exactly:
			"The documents do not contain the information to answer this question."
			Do not guess or invent facts. Answer in the same language as the question.
			""";

	/** Matches source markers like {@code [1]} that the model is asked to cite. */
	private static final Pattern SOURCE_MARKER = Pattern.compile("\\[(\\d{1,3})\\]");

	private static final List<String> INSUFFICIENT_MARKERS = List.of(
			"do not contain", "does not contain", "not contain the information", "no relevant information",
			"cannot find", "could not find", "tidak terdapat", "tidak ada informasi", "tidak mengandung");

	private final DocumentRetrievalService retrievalService;
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
		var results = chunks.getResults();
		if (results.isEmpty()) {
			log.info("rag.answer: no context for project={}; returning insufficient", query.getProjectId());
			return RagAnswer.insufficient(query.getQuestion(),
					"The project knowledge base does not contain information to answer this question.");
		}

		var prompt = buildPrompt(query.getQuestion(), results);
		var llmOptions = LlmOptions.withDefaultLlm().withTemperature(properties.getRag().getTemperature());

		var text = context.ai()
				.withLlm(llmOptions)
				.withSystemPrompt(SYSTEM_PROMPT)
				.generateText(prompt);

		return assemble(query.getQuestion(), results, text);
	}

	/** Builds the user prompt: numbered, provenance-tagged context passages followed by the question. */
	private String buildPrompt(String question, List<KnowledgeSearchResult> results) {
		var maxChars = properties.getRag().getMaxContextChars();
		var sb = new StringBuilder();
		sb.append("Context sources:\n");
		var used = 0;
		for (var i = 0; i < results.size(); i++) {
			var r = results.get(i);
			var content = r.getContent() != null ? r.getContent() : "";
			var header = "[" + (i + 1) + "] " + provenance(r) + "\n";
			if (used + header.length() + content.length() > maxChars && used > 0) {
				break;
			}
			sb.append(header).append(content).append("\n\n");
			used += header.length() + content.length() + 2;
		}
		sb.append("Question: ").append(question).append('\n');
		return sb.toString();
	}

	/** Human-readable provenance for a source line, e.g. {@code Spec (v2), pages 3-4}. */
	private String provenance(KnowledgeSearchResult r) {
		var sb = new StringBuilder();
		sb.append(r.getDocumentTitle() != null ? r.getDocumentTitle() : "Untitled document");
		if (r.getVersionNumber() != null) {
			sb.append(" (v").append(r.getVersionNumber()).append(')');
		}
		var pages = range("page", r.getPageStart(), r.getPageEnd());
		if (pages != null) {
			sb.append(", ").append(pages);
		}
		var slides = range("slide", r.getSlideStart(), r.getSlideEnd());
		if (slides != null) {
			sb.append(", ").append(slides);
		}
		return sb.toString();
	}

	private String range(String label, Integer start, Integer end) {
		if (start == null && end == null) {
			return null;
		}
		if (start != null && end != null && !start.equals(end)) {
			return label + "s " + start + "-" + end;
		}
		var value = start != null ? start : end;
		return label + " " + value;
	}

	/**
	 * Builds the final answer from the model's plain-text output. An explicit "no information" reply is
	 * reported as not grounded with no citations. Otherwise citations are rebuilt from the real retrieved
	 * chunks using the {@code [n]} markers the model cited (or all retrieved chunks when it cited none),
	 * so references always point at actually-retrieved content.
	 */
	private RagAnswer assemble(String question, List<KnowledgeSearchResult> results, String text) {
		var body = text != null ? text.strip() : "";
		if (body.isEmpty()) {
			return RagAnswer.insufficient(question,
					"The answer could not be generated from the project knowledge base.");
		}

		var answer = new RagAnswer();
		answer.setQuestion(question);
		answer.setAnswer(body);

		if (looksInsufficient(body)) {
			answer.setGrounded(false);
			answer.setCitations(new ArrayList<>());
			return answer;
		}

		answer.setGrounded(true);
		answer.setCitations(buildCitations(results, body));
		return answer;
	}

	private List<Citation> buildCitations(List<KnowledgeSearchResult> results, String body) {
		var citations = new ArrayList<Citation>();
		var cited = citedSourceNumbers(body, results.size());
		if (cited.isEmpty()) {
			for (var r : results) {
				citations.add(Citation.from(r));
			}
		} else {
			for (var number : cited) {
				citations.add(Citation.from(results.get(number - 1)));
			}
		}
		return citations;
	}

	/** Distinct, in-range 1-based source numbers referenced as {@code [n]} in the answer, in first-seen order. */
	private LinkedHashSet<Integer> citedSourceNumbers(String body, int sourceCount) {
		var numbers = new LinkedHashSet<Integer>();
		var matcher = SOURCE_MARKER.matcher(body);
		while (matcher.find()) {
			try {
				var number = Integer.parseInt(matcher.group(1));
				if (number >= 1 && number <= sourceCount) {
					numbers.add(number);
				}
			} catch (NumberFormatException ignored) {
				// Not a usable source marker; skip it.
			}
		}
		return numbers;
	}

	private boolean looksInsufficient(String body) {
		var lower = body.toLowerCase();
		for (var marker : INSUFFICIENT_MARKERS) {
			if (lower.contains(marker)) {
				return true;
			}
		}
		return false;
	}

	private String preview(String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= 80 ? text : text.substring(0, 80) + "…";
	}
}
