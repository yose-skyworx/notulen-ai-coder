package com.ilkeiapps.skyworx.notulenai.service;

import java.io.Serial;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ilkeiapps.skyworx.notulenai.config.CodeReviewProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only GitHub REST access for the code-review module. Four endpoints are used: the commit list for
 * a branch and window, a single commit with its changed files, a file's contents at an exact SHA, and a
 * branch's whole file tree. Nothing here writes to GitHub — no comments, no pull requests, no pushes.
 * <p>
 * The module is read-only against a single trusted host, so every defence is about not letting a
 * response or a stored repository value steer a request somewhere it should not go:
 * <ul>
 * <li>Owner, repository, ref, SHA, and file path are validated against strict shapes before use,
 * because they arrive from the database rather than from a literal in this class. A repository row
 * containing {@code ../..} or a URL fragment must not be able to build one.</li>
 * <li>Pagination re-issues the same request against the configured base URL with {@code page=N+1}
 * instead of following the {@code Link} header. Following it would let a response redirect the
 * request — and the bearer token it carries — to another host.</li>
 * <li>{@code githubRestClient} sets {@code Redirect.NEVER} and uses the JVM trust store, so neither a
 * 3xx nor a spoofed certificate can move the token.</li>
 * </ul>
 * <p>
 * Status handling uses {@link RestClient.RequestHeadersSpec#exchange} rather than {@code retrieve()}
 * so that 401, 403, 404, 429, and 5xx each map to a distinct exception instead of one opaque
 * {@code HttpClientErrorException}. Callers turn those into different outcomes: an auth failure fails
 * the whole run, a rate limit fails it with a different message, and a missing file skips one file.
 * <p>
 * Transient outcomes are retried with bounded exponential backoff plus jitter, honouring
 * {@code Retry-After}. Permanent outcomes are not retried — repeating a rejected credential only
 * burns rate limit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubApiClient {

	/**
	 * Local Jackson (2.x) mapper. Spring Boot 4 auto-configures a Jackson 3 mapper, so no
	 * {@code com.fasterxml.jackson.databind.ObjectMapper} bean exists to inject.
	 */
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	/**
	 * GitHub caps {@code files} on the single-commit endpoint and silently drops the rest, so a commit
	 * that reports exactly this many may be truncated. Logged rather than silently accepted.
	 */
	private static final int GITHUB_COMMIT_FILE_CAP = 300;

	/** Upper bound on a single backoff wait, so a large configured delay cannot stall a run for long. */
	private static final long MAX_BACKOFF_MS = 60_000L;

	private static final Pattern OWNER_OR_REPO = Pattern.compile("[A-Za-z0-9._-]{1,100}");
	private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{7,64}");
	private static final Pattern BRANCH_REF = Pattern.compile("[A-Za-z0-9._/-]{1,255}");

	private static final SecureRandom JITTER = new SecureRandom();

	/**
	 * Injected by name: there are two {@code RestClient} beans in this application ({@code
	 * githubRestClient} and {@code tikaRestClient}) and no {@code lombok.config} copying
	 * {@code @Qualifier} onto constructor parameters, so resolution depends on this field name
	 * matching the bean name. Renaming the field breaks startup.
	 */
	private final @Qualifier("githubRestClient") RestClient githubRestClient;
	private final CodeReviewProperties properties;

	// ------------------------------------------------------------------ public API

	/**
	 * Lists commits on a branch within {@code [since, until]}, oldest page first. GitHub filters on
	 * committer date, but callers re-check the window themselves: the server-side filter is documented
	 * as approximate and silently returns nothing useful when it disagrees.
	 */
	public List<CommitInfo> listCommits(String owner, String repo, String branchRef, Instant since, Instant until) {
		var ownerPart = requireSlug(owner, "owner");
		var repoPart = requireSlug(repo, "repo");
		var ref = requireRef(branchRef);
		var label = "list commits " + ownerPart + "/" + repoPart + "@" + ref;

		var query = new LinkedHashMap<String, String>();
		query.put("sha", ref);
		if (since != null) {
			query.put("since", since.toString());
		}
		if (until != null) {
			query.put("until", until.toString());
		}

		var commits = new ArrayList<CommitInfo>();
		try {
			for (JsonNode node : getPaged(label, "/repos/" + ownerPart + "/" + repoPart + "/commits", query)) {
				var commit = parseCommit(node);
				if (commit != null) {
					commits.add(commit);
				}
			}
		} catch (GithubNotFoundException e) {
			throw explainCommitNotFound(ownerPart, repoPart, ref, e);
		}
		log.debug("github: {} returned {} commit(s)", label, commits.size());
		return commits;
	}

	/**
	 * Separates the three problems a 404 on the commit listing can actually mean, because GitHub reports
	 * all of them identically. It answers <b>404 rather than 403</b> for a private repository the token
	 * cannot see, so "Not Found" is equally consistent with a misspelled slug, a branch that does not
	 * exist, and a valid token that simply lacks access to a repository that is there. The first two are
	 * configuration typos; the third needs a different token, and telling the user to check their branch
	 * name when the real problem is token scope sends them down a dead end.
	 * <p>
	 * Costs one extra request, on a path that has already terminally failed this run.
	 */
	private RuntimeException explainCommitNotFound(String owner, String repo, String branchRef,
			GithubNotFoundException original) {
		var slug = owner + "/" + repo;
		var label = "resolve repository " + slug;

		Response probe;
		try {
			probe = getWithRetry(label, builder -> buildUri(builder, "/repos/" + owner + "/" + repo, Map.of()));
		} catch (RuntimeException e) {
			// The probe is a courtesy. If it cannot complete, the original error is still the accurate one.
			log.debug("github: {} probe failed, keeping the original error", label, e);
			return original;
		}
		if (probe.status() == 404) {
			return new GithubAuthException("repository " + slug + " is not visible to the configured GITHUB_TOKEN. "
					+ "GitHub answers 404 rather than 403 for a private repository a token cannot see, so this is "
					+ "usually a token problem rather than a missing repository: confirm the token's repository "
					+ "selection includes it, that it carries the 'repo' scope (classic token) or Contents read "
					+ "permission (fine-grained token), and — when " + owner + " is an organisation enforcing SAML "
					+ "SSO — that the token has been authorised for SSO with that organisation.");
		}
		if (probe.status() < 200 || probe.status() >= 300) {
			// The probe settled nothing, so the original error is still the accurate one.
			return original;
		}

		// The repository is reachable, so the ref is what GitHub could not resolve.
		String defaultBranch = null;
		try {
			defaultBranch = textOrNull(OBJECT_MAPPER.readTree(probe.body()), "default_branch");
		} catch (Exception ignored) {
			// The default branch is a hint in the message, not something the diagnosis depends on.
		}
		return new GithubNotFoundException("branch or ref '" + branchRef + "' was not found on " + slug
				+ (defaultBranch == null ? "" : ", whose default branch is '" + defaultBranch + "'")
				+ ". The repository itself is reachable with the configured GITHUB_TOKEN, so this is a branch name "
				+ "problem — check code_review_branches.branch_name against the repository's actual branches.");
	}

	/** Fetches one commit together with its changed files. */
	public CommitDetail getCommit(String owner, String repo, String commitSha) {
		var ownerPart = requireSlug(owner, "owner");
		var repoPart = requireSlug(repo, "repo");
		var sha = requireSha(commitSha);
		var label = "get commit " + ownerPart + "/" + repoPart + "@" + sha;

		var node = requireOk(label, getWithRetry(label,
				builder -> buildUri(builder, "/repos/" + ownerPart + "/" + repoPart + "/commits/" + sha, Map.of())));

		var commit = parseCommit(node);
		if (commit == null) {
			throw new GithubApiException(label + ": response carried no commit metadata");
		}

		var files = new ArrayList<CommitFile>();
		JsonNode filesNode = node.get("files");
		if (filesNode != null && filesNode.isArray()) {
			if (filesNode.size() >= GITHUB_COMMIT_FILE_CAP) {
				log.warn("github: {} reported {} files, which is GitHub's cap — the list may be truncated",
						label, filesNode.size());
			}
			for (JsonNode fileNode : filesNode) {
				var path = textOrNull(fileNode, "filename");
				if (path == null || path.isBlank()) {
					continue;
				}
				files.add(new CommitFile(path,
						textOrNull(fileNode, "previous_filename"),
						textOrNull(fileNode, "status"),
						intOrNull(fileNode, "additions"),
						intOrNull(fileNode, "deletions"),
						textOrNull(fileNode, "patch")));
			}
		}
		log.debug("github: {} returned {} file(s)", label, files.size());
		return new CommitDetail(commit, files);
	}

	/**
	 * Reads one file at an exact commit. The SHA is mandatory: reading from the branch head instead
	 * would review context that did not exist in the reviewed commit, which makes every line number in
	 * the findings unverifiable.
	 *
	 * @return empty when the path does not exist at that commit, or is a directory rather than a file
	 */
	public Optional<FileContent> findFileContent(String owner, String repo, String commitSha, String path) {
		var ownerPart = requireSlug(owner, "owner");
		var repoPart = requireSlug(repo, "repo");
		var sha = requireSha(commitSha);
		var filePath = requirePath(path);
		var label = "read " + ownerPart + "/" + repoPart + "@" + sha + ":" + filePath;

		var query = new LinkedHashMap<String, String>();
		query.put("ref", sha);

		var response = getWithRetry(label,
				builder -> buildUri(builder,
						"/repos/" + ownerPart + "/" + repoPart + "/contents/" + filePath, query));
		if (response.status() == 404) {
			log.debug("github: {} not found at that commit", label);
			return Optional.empty();
		}

		var node = requireOk(label, response);
		if (!"file".equals(textOrNull(node, "type"))) {
			log.debug("github: {} is not a regular file", label);
			return Optional.empty();
		}

		long size = node.path("size").asLong(0L);
		var maxBytes = properties.getGithub().getMaxFileBytes();
		if (size > maxBytes) {
			log.info("github: {} is {} bytes, over the {} byte limit", label, size, maxBytes);
			return Optional.of(new FileContent(filePath, sha, size, true, null));
		}

		var encoded = textOrNull(node, "content");
		if (encoded == null) {
			return Optional.of(new FileContent(filePath, sha, size, true, null));
		}
		// The MIME decoder is required: GitHub wraps the base64 body at 60 columns.
		var decoded = new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
		return Optional.of(new FileContent(filePath, sha, decoded.getBytes(StandardCharsets.UTF_8).length,
				false, decoded));
	}

	/**
	 * Lists every file a branch holds at its current head, for a whole-branch review that has no commit
	 * and therefore no diff to work from. Two calls: the branch head, to pin a commit SHA and read its
	 * tree SHA, then that tree recursively.
	 * <p>
	 * Pinning the head first is what keeps the result self-consistent. Reading the tree at
	 * {@code refs/heads/x} and then fetching each file at {@code refs/heads/x} would span a push that
	 * landed in between, so a file could be listed and then not found, or found with content the listing
	 * never described. Everything after the first call uses the SHA, so one run reviews one immutable
	 * snapshot — the same rule {@link #findFileContent} enforces for the diff flow.
	 * <p>
	 * The branch name travels as a <b>query parameter</b>, never as a path segment. {@code UriBuilder}
	 * percent-encodes query values, so a branch named {@code feature/a b} cannot change the shape of the
	 * request; in a path segment the {@code /} would. The tree SHA that goes into the second path is a
	 * 40-hex value taken from GitHub's own response and re-validated, not from a stored column.
	 * <p>
	 * {@code truncated} reports GitHub's own cap on a recursive tree — 100,000 entries or 7 MB. It is
	 * surfaced rather than swallowed: a truncated listing means the branch holds files this review never
	 * saw, and a run that silently covered half a repository would look exactly like one that covered all
	 * of it.
	 */
	public BranchTree listBranchTree(String owner, String repo, String branchRef) {
		var ownerPart = requireSlug(owner, "owner");
		var repoPart = requireSlug(repo, "repo");
		var ref = requireRef(branchRef);
		var label = "list branch tree " + ownerPart + "/" + repoPart + "@" + ref;

		var headQuery = new LinkedHashMap<String, String>();
		headQuery.put("sha", ref);
		headQuery.put("per_page", "1");
		var headLabel = label + ": resolve head";

		JsonNode headPage;
		try {
			headPage = requireOk(headLabel, getWithRetry(headLabel, builder -> buildUri(builder,
					"/repos/" + ownerPart + "/" + repoPart + "/commits", headQuery)));
		} catch (GithubNotFoundException e) {
			throw explainCommitNotFound(ownerPart, repoPart, ref, e);
		}
		if (!headPage.isArray() || headPage.isEmpty()) {
			throw new GithubNotFoundException(headLabel + ": the branch resolved to no commit");
		}

		JsonNode headNode = headPage.get(0);
		var head = parseCommit(headNode);
		if (head == null) {
			throw new GithubApiException(headLabel + ": response carried no commit metadata");
		}
		// Checked directly rather than through requireSha, whose message names a commit SHA and would send
		// whoever reads it looking at the wrong field.
		var treeSha = textOrNull(headNode.path("commit").path("tree"), "sha");
		if (treeSha == null || !COMMIT_SHA.matcher(treeSha).matches()) {
			throw new GithubApiException(headLabel + ": response carried no tree SHA to list");
		}

		var treeQuery = new LinkedHashMap<String, String>();
		treeQuery.put("recursive", "1");
		var treeNode = requireOk(label, getWithRetry(label, builder -> buildUri(builder,
				"/repos/" + ownerPart + "/" + repoPart + "/git/trees/" + treeSha, treeQuery)));

		var truncated = treeNode.path("truncated").asBoolean(false);
		var files = new ArrayList<BranchFile>();
		JsonNode entries = treeNode.get("tree");
		if (entries != null && entries.isArray()) {
			for (JsonNode entry : entries) {
				// Only blobs are files. Trees are directories and commits are submodule pointers, neither
				// of which has content to read.
				if (!"blob".equals(textOrNull(entry, "type"))) {
					continue;
				}
				var path = textOrNull(entry, "path");
				if (path == null || path.isBlank()) {
					continue;
				}
				files.add(new BranchFile(path, textOrNull(entry, "sha"), entry.path("size").asLong(0L)));
			}
		}

		if (truncated) {
			log.warn("github: {} was truncated by GitHub after {} entry(ies) — the branch holds more files "
					+ "than this listing shows", label, files.size());
		}
		log.debug("github: {} at {} returned {} file(s)", label, head.sha(), files.size());
		return new BranchTree(head, treeSha, List.copyOf(files), truncated);
	}

	// ------------------------------------------------------------------ transport

	/** Raw HTTP outcome, kept uninterpreted so each caller decides what a status means. */
	private record Response(int status, String body, Long retryAfterMillis, boolean rateLimitExhausted) {
	}

	private Response getWithRetry(String label, Function<UriBuilder, URI> uriFn) {
		var execution = properties.getExecution();
		var attempts = Math.max(1, execution.getRetryAttempts());
		var initialDelay = Math.max(0L, execution.getRetryInitialDelayMs());

		Response last = null;
		for (var attempt = 1; attempt <= attempts; attempt++) {
			try {
				last = getOnce(uriFn);
			} catch (ResourceAccessException e) {
				// Timeout or connection failure. The message never carries the Authorization header.
				if (attempt == attempts) {
					throw new GithubApiException(label + ": " + e.getMessage(), e);
				}
				log.warn("github: {} attempt {}/{} failed to reach the API: {}", label, attempt, attempts,
						e.getMessage());
				sleep(backoff(initialDelay, attempt, null), label);
				continue;
			}

			if (!isTransient(last) || attempt == attempts) {
				return last;
			}
			log.warn("github: {} attempt {}/{} got HTTP {}, retrying", label, attempt, attempts, last.status());
			sleep(backoff(initialDelay, attempt, last.retryAfterMillis()), label);
		}
		return last;
	}

	private Response getOnce(Function<UriBuilder, URI> uriFn) {
		return githubRestClient.get()
				.uri(uriFn)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + requireToken())
				.exchange((request, response) -> {
					var bytes = response.getBody().readAllBytes();
					var headers = response.getHeaders();
					return new Response(response.getStatusCode().value(),
							new String(bytes, StandardCharsets.UTF_8),
							parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)),
							"0".equals(headers.getFirst("X-RateLimit-Remaining")));
				});
	}

	private boolean isTransient(Response response) {
		var status = response.status();
		if (status == 429 || status >= 500) {
			return true;
		}
		// GitHub reports both primary and secondary rate limits as 403; the headers tell them apart
		// from a plain permission denial.
		return status == 403 && (response.rateLimitExhausted() || response.retryAfterMillis() != null);
	}

	private long backoff(long initialDelay, int attempt, Long retryAfterMillis) {
		// Capped shift: a large retryAttempts must not overflow into a negative or absurd delay.
		var base = initialDelay * (1L << Math.min(attempt - 1, 5));
		var jitter = JITTER.nextLong(Math.max(1L, base / 2));
		var wait = Math.min(base + jitter, MAX_BACKOFF_MS);
		if (retryAfterMillis != null) {
			wait = Math.min(Math.max(wait, retryAfterMillis), MAX_BACKOFF_MS);
		}
		return wait;
	}

	private void sleep(long millis, String label) {
		if (millis <= 0) {
			return;
		}
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GithubApiException(label + ": interrupted while waiting to retry", e);
		}
	}

	/**
	 * Builds the request URI from a pre-validated literal path. The path is concatenated rather than
	 * passed as a {@code {variable}} because Spring encodes URI variables strictly — a file path's
	 * {@code /} separators would become {@code %2F} and GitHub would answer 404. Template encoding,
	 * which is what a literal path gets, preserves {@code /} and percent-encodes everything illegal
	 * (spaces, non-ASCII) on its own.
	 */
	private URI buildUri(UriBuilder builder, String validatedPath, Map<String, String> query) {
		builder.path(validatedPath);
		for (var entry : query.entrySet()) {
			builder.queryParam(entry.getKey(), entry.getValue());
		}
		return builder.build();
	}

	private JsonNode requireOk(String label, Response response) {
		if (response.status() >= 200 && response.status() < 300) {
			try {
				return OBJECT_MAPPER.readTree(response.body());
			} catch (Exception e) {
				throw new GithubApiException(label + ": response was not valid JSON", e);
			}
		}
		throw mapError(label, response);
	}

	private RuntimeException mapError(String label, Response response) {
		var status = response.status();
		var detail = githubMessage(response.body());
		var described = label + " (HTTP " + status + "): " + detail;

		if (status == 404) {
			return new GithubNotFoundException(described);
		}
		if (status == 429 || (status == 403 && (response.rateLimitExhausted() || response.retryAfterMillis() != null))) {
			return new GithubRateLimitException(described);
		}
		if (status == 401 || status == 403) {
			return new GithubAuthException(described);
		}
		return new GithubApiException(described);
	}

	/**
	 * Extracts GitHub's own {@code message} field for an error response. Bounded, because a body can be
	 * arbitrarily large and it is persisted into {@code error_message} columns.
	 */
	private String githubMessage(String body) {
		if (body == null || body.isBlank()) {
			return "no response body";
		}
		try {
			var message = textOrNull(OBJECT_MAPPER.readTree(body), "message");
			if (message != null && !message.isBlank()) {
				return message.length() > 200 ? message.substring(0, 200) : message;
			}
		} catch (Exception ignored) {
			// A non-JSON error body is not worth failing over; fall through to the length note.
		}
		return "unrecognised response body (" + body.length() + " chars)";
	}

	private List<JsonNode> getPaged(String label, String validatedPath, Map<String, String> query) {
		var github = properties.getGithub();
		var pageSize = Math.max(1, github.getPageSize());
		var maxPages = Math.max(1, github.getMaxPages());
		var items = new ArrayList<JsonNode>();

		for (var page = 1; page <= maxPages; page++) {
			var pagedQuery = new LinkedHashMap<>(query);
			pagedQuery.put("page", String.valueOf(page));
			pagedQuery.put("per_page", String.valueOf(pageSize));

			var response = getWithRetry(label, builder -> buildUri(builder, validatedPath, pagedQuery));
			var node = requireOk(label, response);
			if (!node.isArray()) {
				throw new GithubApiException(label + ": expected a JSON array");
			}
			for (JsonNode element : node) {
				items.add(element);
			}
			// A short page means the end of the list. Stopping here is what keeps maxPages a ceiling
			// rather than a fixed number of round trips.
			if (node.size() < pageSize) {
				break;
			}
			if (page == maxPages) {
				log.warn("github: {} stopped at the configured maxPages={}, results may be incomplete", label, maxPages);
			}
		}
		return items;
	}

	private static Long parseRetryAfter(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		try {
			var seconds = Long.parseLong(header.trim());
			return seconds > 0 ? seconds * 1000L : null;
		} catch (NumberFormatException e) {
			// Retry-After may be an HTTP-date; the computed backoff covers that case.
			return null;
		}
	}

	// ------------------------------------------------------------------ parsing

	private CommitInfo parseCommit(JsonNode node) {
		var sha = textOrNull(node, "sha");
		if (sha == null || sha.isBlank()) {
			return null;
		}
		JsonNode commitNode = node.get("commit");
		var message = commitNode == null ? null : textOrNull(commitNode, "message");
		var committerName = commitNode == null ? null : textOrNull(commitNode.path("committer"), "name");
		var committerDate = commitNode == null ? null : parseInstant(textOrNull(commitNode.path("committer"), "date"));

		var parents = new ArrayList<String>();
		JsonNode parentsNode = node.get("parents");
		if (parentsNode != null && parentsNode.isArray()) {
			for (JsonNode parent : parentsNode) {
				var parentSha = textOrNull(parent, "sha");
				if (parentSha != null && !parentSha.isBlank()) {
					parents.add(parentSha);
				}
			}
		}
		return new CommitInfo(sha, message, committerName, committerDate, List.copyOf(parents));
	}

	private static Instant parseInstant(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(value.trim());
		} catch (Exception e) {
			log.warn("github: could not parse commit date '{}'", value);
			return null;
		}
	}

	private static String textOrNull(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asText();
	}

	private static Integer intOrNull(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.get(field);
		return value == null || !value.isNumber() ? null : value.asInt();
	}

	// ------------------------------------------------------------------ input validation

	/**
	 * Read once per request rather than cached, so rotating {@code GITHUB_TOKEN} and restarting picks
	 * the new value up and a blank value fails before any request leaves the process. The message names
	 * the environment variable but never the token.
	 */
	private String requireToken() {
		var configured = properties.getGithub().getToken();
		if (configured == null || configured.isBlank()) {
			throw new IllegalStateException(
					"code-review.github.token is not set; export GITHUB_TOKEN before running a code review");
		}
		return configured.trim();
	}

	private static String requireSlug(String value, String what) {
		if (value == null || !OWNER_OR_REPO.matcher(value).matches()) {
			throw new IllegalArgumentException("GitHub " + what + " is not a valid slug: '" + value + "'");
		}
		return value;
	}

	private static String requireSha(String value) {
		if (value == null || !COMMIT_SHA.matcher(value).matches()) {
			throw new IllegalArgumentException("Commit SHA is not hexadecimal: '" + value + "'");
		}
		return value;
	}

	private static String requireRef(String value) {
		if (value == null || !BRANCH_REF.matcher(value).matches()
				|| value.startsWith("/") || value.endsWith("/") || value.contains("..")) {
			throw new IllegalArgumentException("Branch reference is not a valid git ref: '" + value + "'");
		}
		return value;
	}

	/**
	 * Rejects the characters that would let a stored path change the shape of the request: {@code ?}
	 * starts a query string, {@code #} a fragment, {@code %} produces an escape this class did not
	 * intend, {@code \} is not a git separator, and {@code {} } would be read as a URI template
	 * variable by Spring's encoder. Control characters are refused so a path cannot inject log lines.
	 * <p>
	 * This is a deny-list rather than an allow-list on purpose: git permits almost any byte in a file
	 * name, and an allow-list strict enough to be obviously safe would silently skip legitimate files.
	 * The characters that matter for URL safety are a small, closed set.
	 */
	private static String requirePath(String path) {
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("File path must not be blank");
		}
		var trimmed = path.trim();
		if (trimmed.length() > 1024) {
			throw new IllegalArgumentException("File path exceeds 1024 characters");
		}
		for (var i = 0; i < trimmed.length(); i++) {
			var c = trimmed.charAt(i);
			if (c < 0x20 || c == 0x7F || c == '%' || c == '?' || c == '#' || c == '\\' || c == '{' || c == '}') {
				throw new IllegalArgumentException("File path contains a character that cannot be sent safely");
			}
		}
		if (trimmed.startsWith("/")) {
			throw new IllegalArgumentException("File path must be relative to the repository root");
		}
		for (var segment : trimmed.split("/", -1)) {
			if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
				throw new IllegalArgumentException("File path has an empty or traversing segment");
			}
		}
		return trimmed;
	}

	// ------------------------------------------------------------------ result types

	/** One commit as GitHub reports it. {@code parentShas} is empty for a root commit. */
	public record CommitInfo(String sha, String message, String committerName, Instant committerDate,
			List<String> parentShas) {
	}

	/**
	 * One changed file within a commit. {@code patch} is null when GitHub omits it — binary files, and
	 * diffs too large to inline — which callers treat as "not reviewable" rather than "no changes".
	 */
	public record CommitFile(String path, String previousFilename, String status, Integer additions,
			Integer deletions, String patch) {
	}

	public record CommitDetail(CommitInfo commit, List<CommitFile> files) {
	}

	/**
	 * A file's contents at an exact commit. When {@code oversized} is true, {@code text} is null: the
	 * file exists and its size is known, but it was deliberately not read into memory.
	 */
	public record FileContent(String path, String commitSha, long size, boolean oversized, String text) {
	}

	/**
	 * One file in a branch tree. {@code blobSha} is the git object hash of the content, which changes
	 * exactly when the content does — so it serves as the whole-branch review's idempotency key, standing
	 * in for the diff hash a commit-based review derives from its patch.
	 */
	public record BranchFile(String path, String blobSha, long size) {
	}

	/**
	 * A branch's whole file listing at one pinned commit. {@code head} is the commit the tree belongs to,
	 * and every subsequent read in the run must use its SHA so the review covers one immutable snapshot.
	 */
	public record BranchTree(CommitInfo head, String treeSha, List<BranchFile> files, boolean truncated) {
	}

	// ------------------------------------------------------------------ exceptions

	/** A GitHub call failed. Subclasses distinguish outcomes callers must handle differently. */
	public static class GithubApiException extends RuntimeException {
		@Serial
		private static final long serialVersionUID = 1L;

		public GithubApiException(String message) {
			super(message);
		}

		public GithubApiException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** The token was rejected or lacks access. Permanent — retrying only burns rate limit. */
	public static class GithubAuthException extends GithubApiException {
		@Serial
		private static final long serialVersionUID = 1L;

		public GithubAuthException(String message) {
			super(message);
		}
	}

	/** GitHub refused the request because of its rate limit, primary or secondary. */
	public static class GithubRateLimitException extends GithubApiException {
		@Serial
		private static final long serialVersionUID = 1L;

		public GithubRateLimitException(String message) {
			super(message);
		}
	}

	/** The repository, commit, or path does not exist — or is not visible to this token. */
	public static class GithubNotFoundException extends GithubApiException {
		@Serial
		private static final long serialVersionUID = 1L;

		public GithubNotFoundException(String message) {
			super(message);
		}
	}
}
