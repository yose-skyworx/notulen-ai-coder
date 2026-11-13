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
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.LlmAnswer;
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
 * The answer is produced as a structured {@link LlmAnswer} (answer text, a grounded flag, and the
 * 1-based numbers of the context sources the model used); this requires a model capable of reliable
 * structured output. Only those source numbers are trusted from the model: the agent rebuilds the real
 * {@link Citation} metadata from the actually-retrieved chunks (falling back to the {@code [n]} markers
 * in the text, then to all retrieved chunks), so a document or page reference can never be fabricated.
 * The agent performs <em>no</em> authorization — {@code KnowledgeRagService} validates project access
 * before invoking it.
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
			Anda adalah asisten yang menjawab berdasarkan basis pengetahuan sebuah proyek.
			Jawab HANYA menggunakan sumber konteks bernomor yang diberikan. Jangan gunakan pengetahuan di luar konteks.
			Selalu jawab dalam Bahasa Indonesia, apa pun bahasa pertanyaannya.
			Pada kolom "answer", kutip sumber yang Anda gunakan dengan nomornya dalam tanda kurung siku, misalnya [1] atau [2].
			Cantumkan nomor sumber yang sama pada kolom "citedSources".
			Isi "grounded" dengan true hanya jika konteks memuat cukup informasi untuk menjawab; jika tidak, isi
			dengan false dan nyatakan pada kolom "answer" bahwa dokumen tidak memuat informasi tersebut.
			Jangan menebak atau mengarang fakta.
			""";

	/** Matches source markers like {@code [1]} in the answer text; a fallback when the model omits citedSources. */
	private static final Pattern SOURCE_MARKER = Pattern.compile("\\[(\\d{1,3})\\]");

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
					"Basis pengetahuan proyek tidak memuat informasi untuk menjawab pertanyaan ini.");
		}

		var prompt = buildPrompt(query.getQuestion(), results);
		var llmOptions = LlmOptions.withDefaultLlm().withTemperature(properties.getRag().getTemperature());

		var llmAnswer = context.ai()
				.withLlm(llmOptions)
				.withSystemPrompt(SYSTEM_PROMPT)
				.createObject(prompt, LlmAnswer.class);

		return assemble(query.getQuestion(), results, llmAnswer);
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
	 * Builds the final answer from the model's structured output. When the model reports the context was
	 * insufficient (or produced no answer), it is returned as not grounded with no citations. Otherwise
	 * citations are rebuilt from the real retrieved chunks using the source numbers the model reported
	 * (falling back to the {@code [n]} markers in the text, then to all retrieved chunks), so references
	 * always point at actually-retrieved content.
	 */
	private RagAnswer assemble(String question, List<KnowledgeSearchResult> results, LlmAnswer llmAnswer) {
		var body = llmAnswer != null && llmAnswer.getAnswer() != null ? llmAnswer.getAnswer().strip() : "";
		if (body.isEmpty()) {
			return RagAnswer.insufficient(question,
					"Jawaban tidak dapat dihasilkan dari basis pengetahuan proyek.");
		}

		var answer = new RagAnswer();
		answer.setQuestion(question);
		answer.setAnswer(body);

		if (!llmAnswer.isGrounded()) {
			answer.setGrounded(false);
			answer.setCitations(new ArrayList<>());
			return answer;
		}

		answer.setGrounded(true);
		answer.setCitations(buildCitations(results, llmAnswer, body));
		return answer;
	}

	private List<Citation> buildCitations(List<KnowledgeSearchResult> results, LlmAnswer llmAnswer, String body) {
		var cited = reportedSourceNumbers(llmAnswer.getCitedSources(), results.size());
		if (cited.isEmpty()) {
			cited = markerSourceNumbers(body, results.size());
		}

		var citations = new ArrayList<Citation>();
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

	/** Distinct, in-range 1-based source numbers the model reported in {@code citedSources}, in order. */
	private LinkedHashSet<Integer> reportedSourceNumbers(List<Integer> citedSources, int sourceCount) {
		var numbers = new LinkedHashSet<Integer>();
		if (citedSources == null) {
			return numbers;
		}
		for (var number : citedSources) {
			if (number != null && number >= 1 && number <= sourceCount) {
				numbers.add(number);
			}
		}
		return numbers;
	}

	/** Distinct, in-range 1-based source numbers referenced as {@code [n]} in the answer, in first-seen order. */
	private LinkedHashSet<Integer> markerSourceNumbers(String body, int sourceCount) {
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

	private String preview(String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= 80 ? text : text.substring(0, 80) + "…";
	}
}
