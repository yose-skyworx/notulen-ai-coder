package com.ilkeiapps.skyworx.notulenai.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds and snapshots the {@code AGENTS.md} guidelines that govern a commit's changed files.
 * <p>
 * A guideline is discovered from the changed paths, not from a configured list: {@code AGENTS.md}
 * applies to the directory it sits in and everything below it, so the guidelines relevant to a commit
 * are the root file plus one per ancestor directory of each changed file. Fetching them at the
 * reviewed commit's SHA is what makes the snapshot faithful — reading the branch head would attribute
 * a rule to a review that ran before the rule existed.
 * <p>
 * Each resolved guideline is persisted with its content and a SHA-256 of that content, so a finding
 * that cites a rule can be traced to the exact wording in force at the time. That is also what makes
 * re-runs idempotent: an unchanged hash means an unchanged rule.
 * <p>
 * Guideline text is untrusted input. It is stored and passed to the model as <em>content</em>, never as
 * instructions to this service — nothing here interprets what a guideline says.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentsGuidelineResolver {

	private static final String FILENAME = "AGENTS.md";

	/**
	 * Ceiling on guidelines fetched for one run. Each candidate costs a GitHub request, and the vast
	 * majority answer 404, so the bound is about rate limit rather than memory. Ordered root-first, a
	 * truncation drops the deepest — and least commonly present — files.
	 */
	private static final int MAX_CANDIDATES = 32;

	private final GithubApiClient githubApiClient;

	/**
	 * One guideline as it existed at the reviewed commit.
	 *
	 * @param path        repository-relative, always ending in {@code AGENTS.md}
	 * @param contentHash SHA-256 hex of {@code content}; 64 chars, matching the column
	 */
	public record Guideline(String path, String commitSha, String content, String contentHash) {
	}

	/**
	 * @param changedPaths repository-relative paths of the commit's files; used only to derive the
	 *                     directories to look in
	 * @return resolved guidelines ordered root-first, then by directory depth, then alphabetically
	 */
	public List<Guideline> resolve(String owner, String repo, String commitSha, List<String> changedPaths) {
		var candidates = candidatePaths(changedPaths);
		var resolved = new ArrayList<Guideline>();

		for (String path : candidates) {
			try {
				var content = githubApiClient.findFileContent(owner, repo, commitSha, path);
				if (content.isEmpty() || content.get().oversized() || content.get().text() == null) {
					continue;
				}
				var text = content.get().text();
				if (text.isBlank()) {
					continue;
				}
				resolved.add(new Guideline(path, commitSha, text, sha256Hex(text)));
			} catch (IllegalArgumentException e) {
				// The directory name came from a file path in the repository, and git permits characters
				// (braces, percent) that cannot be placed in a request URL. One unusable name must not
				// abort the run, so it is skipped and the rest are still resolved.
				log.debug("guidelines: skipped '{}', not expressible as a request path", path);
			}
		}

		log.info("guidelines: probed {} candidate path(s), resolved {}", candidates.size(), resolved.size());
		return List.copyOf(resolved);
	}

	/**
	 * Builds the probe list: the root {@code AGENTS.md} plus one per ancestor directory of each changed
	 * file, deduplicated and ordered shallowest-first so the general rules precede the specific ones.
	 * Sorting the input makes the order independent of how GitHub happened to list the files.
	 */
	private List<String> candidatePaths(List<String> changedPaths) {
		var sorted = new TreeSet<String>();
		if (changedPaths != null) {
			sorted.addAll(changedPaths);
		}

		// depth -> directory guideline paths at that depth. Depth 0 is the repository root.
		var byDepth = new TreeMap<Integer, TreeSet<String>>();
		byDepth.computeIfAbsent(0, depth -> new TreeSet<>()).add(FILENAME);

		for (String path : sorted) {
			var depth = 1;
			var slash = path.indexOf('/');
			while (slash > 0) {
				byDepth.computeIfAbsent(depth, d -> new TreeSet<>())
						.add(path.substring(0, slash) + "/" + FILENAME);
				depth++;
				slash = path.indexOf('/', slash + 1);
			}
		}

		var candidates = new ArrayList<String>();
		for (TreeSet<String> level : byDepth.values()) {
			for (String candidate : level) {
				if (candidates.size() == MAX_CANDIDATES) {
					log.warn("guidelines: {} candidate paths exceed the cap of {}, deepest are not probed",
							countAll(byDepth), MAX_CANDIDATES);
					return List.copyOf(candidates);
				}
				candidates.add(candidate);
			}
		}
		return List.copyOf(candidates);
	}

	private static int countAll(TreeMap<Integer, TreeSet<String>> byDepth) {
		var total = 0;
		for (TreeSet<String> level : byDepth.values()) {
			total += level.size();
		}
		return total;
	}

	private static String sha256Hex(String text) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}
}
