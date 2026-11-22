package com.ilkeiapps.skyworx.notulenai.bean.web;

import java.util.ArrayList;
import java.util.List;

import com.ilkeiapps.skyworx.notulenai.bean.web.CodeReviewBranchFilesResult.BranchFinding;

import lombok.Getter;
import lombok.Setter;

/**
 * Input to the code-review report agent: everything the model needs to narrate one whole-branch review,
 * and nothing it does not. The service builds this from a completed {@link CodeReviewBranchFilesResult}
 * and hands it to {@code AgentInvocation.invoke(...)}; the agent does no database or GitHub work of its own.
 * <p>
 * The findings are the deterministic PMD findings exactly as produced — the model only restates and
 * prioritizes them, it is never the source of a finding, so the report cannot invent an issue that was not
 * found.
 */
@Setter
@Getter
public class CodeReviewReportInput {

	private String repositorySlug;
	private String branchName;
	private int totalFiles;
	private List<BranchFinding> findings = new ArrayList<>();

	public CodeReviewReportInput() {
	}

	public CodeReviewReportInput(String repositorySlug, String branchName, int totalFiles,
			List<BranchFinding> findings) {
		this.repositorySlug = repositorySlug;
		this.branchName = branchName;
		this.totalFiles = totalFiles;
		this.findings = findings != null ? findings : new ArrayList<>();
	}
}
