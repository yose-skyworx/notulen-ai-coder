package com.ilkeiapps.skyworx.notulenai.bean.web;

import lombok.Getter;
import lombok.Setter;

/**
 * The goal of the code-review report agent: a concise, formal Bahasa Indonesia narrative of a whole-branch
 * review, destined for {@code code_review_branch_results.report_text}. {@code text} is the model's output,
 * trimmed; it is derived purely from the findings handed to the agent, never a source of new findings.
 */
@Setter
@Getter
public class CodeReviewReport {

	private String text;

	public CodeReviewReport() {
	}

	public CodeReviewReport(String text) {
		this.text = text;
	}
}
