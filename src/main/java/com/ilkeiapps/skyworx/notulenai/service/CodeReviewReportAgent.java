package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.common.ai.model.LlmOptions;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewBranchFilesResult.BranchFinding;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewReport;
import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewReportInput;
import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Embabel agent that turns one whole-branch review's PMD findings into a concise, formal Bahasa Indonesia
 * report, stored on {@code code_review_branch_results.report_text}. A single action: it receives the
 * findings, builds one plain-text prompt, and has the LLM narrate them.
 * <p>
 * The prompt is shaped around what the deployed chat model ({@code lfm-1.2b}) can actually do, the same
 * lesson {@link ProjectKnowledgeRagAgent} is built on: plain text, no system prompt, one instruction block,
 * and as little context as carries the point. Rather than list every finding, it <b>groups findings by rule
 * code</b> and gives each group its count, its (already Indonesian) message, and a few example locations —
 * so thirty near-identical findings become one line with a number instead of thirty lines, which a small
 * model summarises far more reliably. The findings themselves are already in Indonesian (the rule messages
 * were translated), so the model restates material it is given rather than inventing issues; the instruction
 * tells it not to add anything beyond the data, which keeps a small model from embellishing.
 * <p>
 * When there are no findings the report is a fixed sentence and no LLM call is made — there is nothing to
 * narrate, and spending a model call to say "nothing found" is waste.
 * <p>
 * {@code @Agent} is meta-annotated {@code @Component}, so Embabel registers this bean at startup. The action
 * is not {@code @Transactional}: it calls the LLM (remote), so no database transaction is held across the
 * network call — the caller persists the result in its own short transaction afterwards.
 */
@Slf4j
@Agent(
		name = "codeReviewReport",
		provider = "notulen-ai",
		description = "Summarise a whole-branch code review's findings as a concise formal Indonesian report")
@RequiredArgsConstructor
public class CodeReviewReportAgent {

	private final CodeReviewProperties properties;

	/** Low, for a factual and consistent narration rather than a creative one. */
	private static final double TEMPERATURE = 0.2;

	/** Example locations shown per rule group before the rest are collapsed to "dan N lainnya". */
	private static final int MAX_EXAMPLES_PER_GROUP = 3;

	private static final String NO_FINDINGS =
			"Tinjauan kode otomatis tidak menemukan temuan pada cabang ini. "
					+ "Tidak ada tindakan perbaikan yang diperlukan.";

	@AchievesGoal(description = "A concise, formal Bahasa Indonesia report of the whole-branch review")
	@Action
	public CodeReviewReport generate(CodeReviewReportInput input, OperationContext context) {
		var findings = input.getFindings();
		if (findings == null || findings.isEmpty()) {
			return new CodeReviewReport(NO_FINDINGS);
		}

		var prompt = buildPrompt(input);
		// Cap the output explicitly: without a max-tokens the provider's small default cuts the report off
		// mid-sentence, which is what produced a truncated report_text.
		var maxOutputTokens = properties.getBranchReview().getReport().getMaxOutputTokens();
		var generated = context.ai()
				.withLlm(LlmOptions.withDefaultLlm().withTemperature(TEMPERATURE).withMaxTokens(maxOutputTokens))
				.generateText(prompt);

		var body = generated != null ? generated.strip() : "";
		if (body.isEmpty()) {
			log.warn("code review report: model returned empty text for {} on {}", input.getRepositorySlug(),
					input.getBranchName());
			return new CodeReviewReport("");
		}
		return new CodeReviewReport(body);
	}

	/**
	 * Context first, then one instruction — the shape this model reads most reliably. The context is the
	 * findings grouped by rule code: one line per group with its count and message, then a few example
	 * locations, so the model sees a short digest rather than every finding. The closing instruction asks for
	 * a concise, formal, efficient Indonesian report and forbids going beyond the listed data.
	 */
	private String buildPrompt(CodeReviewReportInput input) {
		var findings = input.getFindings();

		// Group by rule code, preserving first-seen order so the digest reads in the order findings arrived.
		var groups = new LinkedHashMap<String, List<BranchFinding>>();
		for (BranchFinding finding : findings) {
			groups.computeIfAbsent(finding.code(), key -> new ArrayList<>()).add(finding);
		}

		var prompt = new StringBuilder();
		prompt.append("Ringkasan hasil tinjauan kode otomatis.\n");
		prompt.append("Repository: ").append(input.getRepositorySlug()).append('\n');
		prompt.append("Cabang: ").append(input.getBranchName()).append('\n');
		prompt.append("Total temuan: ").append(findings.size())
				.append(" pada ").append(input.getTotalFiles()).append(" berkas.\n");
		prompt.append("Jumlah kelompok masalah: ").append(groups.size()).append(".\n\n");
		prompt.append("Temuan dikelompokkan menurut jenis masalah:\n");

		for (var group : groups.entrySet()) {
			var list = group.getValue();
			var first = list.getFirst();
			prompt.append("- [").append(group.getKey()).append("] ").append(first.ruleName())
					.append(" — ").append(list.size()).append(" temuan. ").append(first.message()).append('\n');

			var examples = Math.min(list.size(), MAX_EXAMPLES_PER_GROUP);
			prompt.append("  Contoh: ");
			for (var i = 0; i < examples; i++) {
				if (i > 0) {
					prompt.append("; ");
				}
				var example = list.get(i);
				prompt.append(example.path()).append(" baris ").append(example.beginLine());
			}
			if (list.size() > examples) {
				prompt.append("; dan ").append(list.size() - examples).append(" lainnya");
			}
			prompt.append('\n');
		}

		prompt.append("\nTulislah laporan ringkas dalam Bahasa Indonesia yang formal dan efisien. "
				+ "Bahas setiap kelompok masalah di atas beserta jumlahnya, jelaskan dampaknya secara singkat, "
				+ "dan sampaikan rekomendasi prioritas perbaikan. Jangan menambahkan informasi di luar data di atas.");
		return prompt.toString();
	}
}
