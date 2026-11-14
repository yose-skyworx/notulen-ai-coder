# Automated Code Review Module — Implementation Plan

Phase 1 deliverable for `git.md`. Produced 2026-11-14 after inspecting the actual codebase.

Scope decisions confirmed with the maintainer:

1. **Deterministic pipeline first, AI review deferred** behind a config flag.
2. **Backend only** — no frontend code; endpoints documented via the existing Swagger UI.
3. **Authorization matches the existing RAG module** — valid JWT plus a project-exists-and-`AKT` check.

---

## 1. Discovery: `git.md` assumptions vs. reality

`git.md` is a well-written spec authored against a different codebase. Three of its premises are
false here, and two more describe infrastructure that does not exist.

| `git.md` claim | Verified reality | Consequence |
|---|---|---|
| "the existing Vue.js 3 frontend" with routing, components, API client (§13, five pages) | **Absent.** No `package.json`, no `.vue`/`.ts`/`.js` source, no bundler config, nothing in any of the 5 commits. `src/main/resources/static/` and `templates/` exist but are empty. `bean/Menu.java` and `Token.menu` are vestigial — no code ever populates them. | §13 dropped. Swagger UI is the delivery surface. |
| 5000-token input, structured JSON findings with 12 fields each (§6, §9) | The only working chat model on sky-inference is `lfm-1.2b`. Measured 2026-11-14: invents answers at ~750 tokens of context, cannot find a present answer at ~4400 tokens, `createObject` returns under-populated schemas, and a system prompt degrades an otherwise-correct answer. | §9's structured-output design is not achievable as written. Phase 5 becomes an inert seam. |
| server-side "project-level authorization" (§12, §14.9) | `entity/User.java` has **no project association of any kind**. `KnowledgeRagService.projectIsActive` only checks the project exists and is `AKT`, so any authenticated user can already query any project. | New module matches existing behaviour. Documented as a limitation, not solved. |
| "encrypted at rest using an existing application encryption mechanism if available" (§4.1, §14.1) | **None available.** The only crypto is `BCryptPasswordEncoder` (one-way — useless for a retrievable PAT) and an HMAC `SecretKey` in `JwtService` used solely for signing. `entity/Token.java` stores the raw JWT string in a plaintext column. | **Superseded by the 2026-11-14 decision:** one deployment-wide PAT from `GITHUB_TOKEN`, so no credential is written to the database and nothing needs encrypting at rest. See "GitHub credential" below. |
| "existing scheduler implementation" (§3.1) | **No cron scheduler.** No `@Scheduled`, `@EnableScheduling`, `TaskScheduler`, or `ThreadPoolTaskExecutor`. Background work today is event-driven: `@EnableAsync` + `@TransactionalEventListener(AFTER_COMMIT)` on virtual threads (`DocumentProcessingTrigger`). | `@EnableScheduling` must be added, gated so existing modules are unaffected. |
| "Jinfer" inference server (§7, §15) | Named **sky-inference** (`https://inference.example.internal/v1`), OpenAI-compatible, already wired through Embabel's `openai-custom` provider. | Naming only. The integration is real and reusable as-is. |

### Conventions that match and will be reused unchanged

These were verified in source, not assumed:

- **Build:** Spring Boot 4.1.2, Java 25, Spring AI 2.0.2, Embabel 1.5.4, Blaze-Persistence 1.6.20,
  OpenFeign QueryDSL fork 7.7, ilkeitool 1.0.2 / ilkeitool-entity-blazebit 1.0.2.1, springdoc 3.2.0.
- **Schema management:** `spring.jpa.hibernate.ddl-auto=update` with indexes and unique constraints
  declared on `@Table`. No Flyway, no Liquibase, no `.sql` files anywhere — matching `git.md` §10.
  Reference rows are seeded by `InitialMasterService` behind `POST /api/init/master`, guarded by
  natural-key existence checks so it is safe to re-run.
- **Entities:** `@Entity` + `@Table(name, indexes)`, `@Setter @Getter`, `Long` id from a named
  `@SequenceGenerator` with `allocationSize = 1`, `implements Serializable` with `@Serial`,
  explicit no-arg constructor, `@ManyToOne(fetch = LAZY)` with `@JoinColumn`.
  Template: `entity/DocumentProcessingJob.java`.
- **Services:** `@Slf4j @Service @RequiredArgsConstructor`; constructor-inject `EntityManager`,
  `CriteriaBuilderFactory`, `EntityViewManager`, `StatusService`. Every method opens
  `var wrap = new ApiResponse<T>(); wrap.setStatus(false);`, returns early on validation failure,
  and ends `wrap.insertNewData(payload); wrap.setStatus(true);`. Template: `service/ProjectService.java`.
- **Queries:** Blaze criteria with QueryDSL path metadata — `new QProject("p")` then
  `.from(Project.class, qp.getMetadata().getName())`. Never a bare `create` without `from`.
- **Reads:** return Entity Views from `entityview/`, paginated via `Utility.getPerPage` /
  `Utility.getOffsetPage` / `EntityViewSetting.create(View.class, of, num)` /
  `.withKeysetExtraction(true)`. Entities are never serialized.
- **Validation:** `IlKeiValidator` for request beans (using Lombok `@FieldNameConstants` → `Fields.x`),
  `IlKeiEntityValidator` for FK/existence checks. No Jakarta Bean Validation annotations.
- **Status:** `StatusGeneral` (`AKT`/`NAK`/`REM`) for soft delete, `StatusProcess`
  (`PENDING`/`PROCESSING`/`COMPLETED`/`FAILED`/`CANCELLED`, ids 20–24) for lifecycle. Both are rows in
  the single-table `status` hierarchy, resolved by `StatusService.getStatusProcessByCode(...)`.
- **Outbound HTTP:** Spring `RestClient`. `KnowledgeConfiguration.tikaRestClient` clones the
  auto-configured `RestClient.Builder`; `TikaExtractionService` parses with a **private Jackson 2
  `ObjectMapper`** because Boot 4 auto-configures Jackson 3. `EmbeddingService.callWithRetry` is the
  existing bounded-retry-with-backoff precedent.
- **Remote calls outside transactions:** `KnowledgeProcessingService` is the canonical pattern — a
  non-transactional `process()` with a `TransactionTemplate` built in `@PostConstruct`, one short
  transaction per state transition, errors truncated to 4000 chars. `git.md` §11 demands exactly this.
- **Embabel agent:** `service/ProjectKnowledgeRagAgent.java` — `@Agent(name, provider = "notulen-ai",
  description)`, `@RequiredArgsConstructor`, `@Action`, `@AchievesGoal`, an `OperationContext` parameter,
  `context.ai().withLlm(LlmOptions.withDefaultLlm().withTemperature(...)).generateText(prompt)`, and no
  `@Transactional`. Invoked from a service via
  `AgentInvocation.create(agentPlatform, RagAnswer.class).invoke(query)`.
- **Security:** stateless JWT. `/api/**` is `authenticated()`; `/api/user/login`, `/api/init/**`,
  `/v3/api-docs/**`, `/swagger-ui/**` are `permitAll()`. A new `/api/code-review/**` controller is
  therefore protected with **no change to `SecurityConfiguration`**.
- **Messages:** `src/main/resources/error_message.properties`, dot-namespaced Bahasa Indonesia, read
  through `Utility.getErrorMessage(key)`.

### Security trap: the global trust-all TLS customizer

`config/InsecureTlsConfiguration` registers a **global** `RestClientCustomizer` that installs a
trust-all `ClientHttpRequestFactory`, gated by `app.tls.insecure` — which currently **defaults to
`true`**. The house pattern (`builder.clone()` in `KnowledgeConfiguration`) inherits that customizer.

Copied blindly, every GitHub API call — **carrying a fine-grained PAT in the `Authorization` header** —
would run with certificate validation disabled. That is a direct MITM exposure and violates
`git.md` §14.7 ("restrict GitHub API hosts") in spirit.

The toggle exists because the internal `*.example.internal` services use a CA the JVM does not trust.
`api.github.com` presents a valid public certificate, so it needs no such exception.

**Decision:** build `githubRestClient` from a **fresh** `RestClient.builder()` — not the injected
auto-configured one — with a properly validating request factory and explicit connect/read timeouts.
This is a deliberate deviation from `tikaRestClient`; it will carry a comment explaining why.

---

## 2. Deviations from `git.md`, with rationale

`git.md` §3.2 and AGENTS.md both instruct that where the spec conflicts with the existing
architecture, adapt to the architecture. These are the conflicts.

| `git.md` | This plan | Why |
|---|---|---|
| §12 RESTful verbs: `POST /repositories`, `PUT /repositories/{id}`, `DELETE /repositories/{id}`, `GET /repositories/{id}` | House style: `POST /save`, `POST /update`, `POST /delete` with the id in the request body; `GET /list`; `GET /detail` | `ProjectController` and `UserController` both work this way. Mixing two API styles in one app is worse than either. |
| §12 trigger returns HTTP `202 Accepted` | Returns `ApiResponse<T>` with `status = true` and the run id in `data` | AGENTS.md §25.11: controllers return the service `ApiResponse` directly and must not re-wrap. The async dispatch is what matters, not the status code. |
| §6 "use the appropriate tokenizer when available" | Reuse the ~4-chars-per-token estimator from `DocumentChunkingService.estimateTokens` | No tokenizer dependency exists and `git.md` §3.2 forbids adding libraries without justification. The existing estimator is already the project's token proxy. |
| §9 `ai.withDefaultLlm().createObject(prompt, FileReviewResult.class)` | `generateText(...)` plus a server-side parser | Measured on this exact model: `createObject` under-populates schema fields. The RAG module already abandoned structured output for the same reason. |
| §11 seven run statuses and five file statuses as new enums | Four new `StatusProcess` rows; reuse the existing five | `StatusProcess` already holds `PENDING`/`PROCESSING`/`COMPLETED`/`FAILED`. Only `COMPLETED_WITH_WARNING`, `NO_COMMIT`, `GUIDELINE_NOT_FOUND`, `SKIPPED` are genuinely new. |
| §13 five Vue pages | Nothing | No frontend exists. |

---

## 3. Phase 2 — Backend foundation

### Entities (`entity/`)

All seven follow the `DocumentProcessingJob` template. `CodeRepository` references the **existing**
`Project` entity and its `Long` primary key — no duplicate project table.

| Class | Table | Notes |
|---|---|---|
| `CodeRepository` | `code_repositories` | `project` FK (not null), `owner`, `repoName`, `defaultBranch`, `enabled`, `status` → `StatusGeneral` for `REM` soft delete, `createdAt`/`updatedAt`. Unique on `(project_id, owner, repo_name)`. No credential column — the PAT is deployment-wide configuration. |
| `CodeReviewBranch` | `code_review_branches` | `repository` FK, `branchName`, `enabled`. Unique on `(repository_id, branch_name)`. |
| `CodeReviewSchedule` | `code_review_schedules` | `repository` FK, `cronExpression`, `timezone`, `enabled`. Unique on `repository_id` (one schedule per repository). |
| `CodeReviewRun` | `code_review_runs` | `repository`, `branch`, `reviewDate` (`LocalDate`), `selectedCommitSha`, `parentCommitSha`, `commitMessage`, `committer`, `commitDate`, `strategy`, `status` → `StatusProcess`, `startedAt`/`completedAt`, `durationMs`, `totalFiles`/`analyzedFiles`/`skippedFiles`/`totalFindings`, `summary`, `errorMessage`. **Unique on `(repository_id, branch_id, review_date, strategy)`** — this constraint *is* the duplicate-run prevention required by §4.2 and §10. |
| `CodeReviewGuideline` | `code_review_guidelines` | `run` FK, `guidelinePath`, `guidelineCommitSha`, `contentHash` (SHA-256), `contentSnapshot` (text). Index on `review_run_id`. |
| `CodeReviewRunFile` | `code_review_run_files` | `run` FK, `filePath`, `oldFilePath`, `changeStatus`, `language`, `additions`, `deletions`, `diffHash`, `status` → `StatusProcess`, `skipReason`, `segmentCount`, `segmentsCompleted`, `startedAt`/`completedAt`, `errorMessage`. Unique on `(review_run_id, file_path)`. `segmentCount`/`segmentsCompleted` implement §6's "a file must not be `COMPLETED` until all segments succeed". |
| `CodeReviewFinding` | `code_review_findings` | `run` FK, `runFile` FK, `filePath`, `lineNumber`, `endLineNumber`, `side`, `ruleId`, `severity`, `category`, `title`, `description`, `evidence`, `guidelineReference`, `recommendation`, `confidence` (`BigDecimal`, precision 3 scale 2), `validationStatus`, `validationDetail`, `createdAt`. Indexes on `review_run_id`, `run_file_id`, `severity`. |

### Enums (`bean/enums/`, alongside the existing `ShowType`)

Persisted with `@Enumerated(EnumType.STRING)` per AGENTS.md §7 — never ordinals.

- `ReviewSeverity` — `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO` (exactly the §8 set)
- `DiffSide` — `NEW`, `OLD`
- `FileChangeStatus` — `ADDED`, `MODIFIED`, `REMOVED`, `RENAMED`
- `FindingValidationStatus` — `VALID`, `INVALID_LINE`, `OUT_OF_FILE`, `MISSING_EVIDENCE`,
  `BAD_SEVERITY`, `BAD_CONFIDENCE`, `UNKNOWN_GUIDELINE`

Lifecycle status stays on the `StatusProcess` foreign key, consistent with `DocumentProcessingJob`.

### Status seeds

Seeded by a new `InitialMasterService.seedCodeReviewStatuses()`, called from `init()` after
`seedProcessStatuses()` — a separate method so the existing one stays untouched. Id range is clear of
10–12 and 20–24:

```
30  COMPLETED_WITH_WARNING
31  NO_COMMIT
32  GUIDELINE_NOT_FOUND
33  SKIPPED
```

`StatusService.getStatusProcessByCode` resolves them with no change.

**Operational note:** seeds only apply when someone calls `POST /api/init/master`. This must appear in
the setup documentation, and the orchestrator must log a clear "status missing — run POST
/api/init/master" error the way `KnowledgeProcessingService.beginProcessing` already does.

### Configuration

`config/CodeReviewProperties` — `@ConfigurationProperties(prefix = "code-review")`, mirroring the
nested-static-class shape of `KnowledgeProperties`, with defaults inline:

```
enabled            = false          # module inert until explicitly switched on
timezone           = Asia/Jakarta
schedule.cron      = 0 5 0 * * *
execution          : global-concurrency 1, file-concurrency 1,
                     retry-attempts 3, retry-initial-delay-ms 3000
context            : max-input-tokens 5000, max-output-tokens 1500,
                     max-guideline-tokens 1200, max-diff-tokens 1500,
                     max-source-context-tokens 1200
inference.enabled  = false          # the Phase 5 kill switch
inference.timeout-seconds = 300
github             : token ${GITHUB_TOKEN},   # no committed default
                     base-url https://api.github.com, api-version 2022-11-28,
                     connect-timeout, read-timeout, page-size, max-pages
exclusions         : node_modules/, target/, dist/, build/, *.min.js,
                     package-lock.json, yarn.lock, *.class, *.jar, *.png, ...
branch-review      : max-files 200   # ceiling for one whole-branch run; added later with BRANCH_FULL
```

`config/CodeReviewConfiguration` — `@EnableConfigurationProperties(CodeReviewProperties.class)`,
`@EnableScheduling`, and the `githubRestClient` bean built from a fresh builder with validating TLS.

The `context.*` values are carried over from `git.md` §6 as **placeholders for the eventual agent**,
not as tuned numbers — the measurements say `lfm-1.2b` cannot use a 5000-token budget. They stay
config-driven so a larger model can be adopted by editing properties alone.

### GitHub credential (`code-review.github.token`)

**Decision 2026-11-14:** one deployment-wide personal access token, read from the `GITHUB_TOKEN`
environment variable, replaces the per-repository encrypted credential that git.md §4.1 describes.

- No secret is ever written to the database, so there is nothing to encrypt at rest and no
  `CODE_REVIEW_ENC_KEY` to generate or safeguard. `CredentialCipher` (AES-256-GCM) was built for the
  per-repository design and has been deleted along with its 13 tests.
- **No committed default.** `application.properties` is git-tracked, so a token written there is a
  token leaked into history — which is what happened during Phase 2 bring-up, and why the two PATs
  used while testing must be revoked.
- Consequence: every registered repository must be readable by that single PAT. Repositories that
  need different credentials cannot be mixed within one deployment.
- Rotating the token means changing an environment variable and restarting. No data migration, and no
  risk of making stored ciphertext undecryptable.
- Phase 3 must treat an absent token as a configuration error (`codereview.github.token_missing`)
  rather than calling GitHub unauthenticated, and must never log the token or the `Authorization`
  header.
- Reintroducing per-repository credentials later means reintroducing encryption at rest: a retrievable
  secret in the shared remote database cannot be stored in plaintext.

### Request beans

Separate create and update beans per AGENTS.md §25.10 — the create bean carries no `id`, so a client
cannot supply one. Payload beans live in `bean/`, id-only and query-parameter beans in `bean/web/`,
matching where `ProjectRequest`/`ProjectIdRequest` already sit.

- `bean/CodeRepositoryRequest` / `CodeRepositoryUpdateRequest` — update adds mandatory `id`. Both
  carry `projectId`, `owner`, `repoName`, `enabled`. Neither exposes `defaultBranch`: it is
  discovered from the GitHub API, so accepting it would let a client point reviews at a branch that
  does not exist. Neither carries a credential field, so registering a repository cannot introduce a
  secret. On update a null `enabled` means "leave unchanged"; every other field is a full replacement.
- `bean/CodeReviewBranchRequest` — `repositoryId` + `List<String> branchNames` + `enabled`, with
  whole-selection-replacement semantics, because git.md §4.1 says "select one or more branches".
- `bean/CodeReviewScheduleRequest` — `repositoryId`, `cronExpression`, `timezone`, `enabled`. No id
  and no separate update bean: `uq_crs_repository` makes a schedule a singleton per repository, so
  save is an upsert on that natural key and a second way to address the same row would add nothing.
- `bean/CodeReviewTriggerRequest` — `repositoryId`, optional `branchName`, optional `reviewDate`
  defaulting to the previous calendar day in the configured zone. No `strategy` field: the service
  stamps `MANUAL`, which is what keeps a manual run from colliding with the day's `DAILY` run.
- `bean/web/CodeRepositoryIdRequest`, `CodeReviewRunIdRequest`
- `bean/web/CodeRepositoryRequestParameter`, `CodeReviewRunRequestParameter`,
  `CodeReviewFindingRequestParameter` — standalone classes exposing only the fields their own query
  uses, like `ProjectRequestParameter` and `DocumentRequestParameter`. They deliberately do **not**
  extend `GenericRequestParameter`, which is legacy carry-over from the sibling codebase and unused
  here. `LocalDate` filters carry `@DateTimeFormat(iso = DATE)` because the application registers no
  global date formatter. Run status is filtered by `StatusProcess` **code**, never by row id.

All are plain beans with `@Getter @Setter @ToString(includeFieldNames = true)` (plus
`@FieldNameConstants` on the payload beans, which is what `IlKeiValidator` picks against), exposing
only client-settable columns so server-controlled fields (`status`, audit columns, `defaultBranch`)
cannot be mass-assigned. No bean carries a credential field.

### Entity views (`entityview/codereview/`)

Covered by the existing `@EnableEntityViews("com.ilkeiapps.skyworx.notulenai.entityview")` scan.

- `CodeRepositoryListView` — flat, with nested project and status views, mirroring `ProjectListView`
- `CodeReviewBranchListView`, `CodeReviewScheduleListView` — added beyond the original list because
  branch selection and the schedule are otherwise write-only: nothing in the route table reads them
  back. Both nest the repository (to-one, so no row multiplication).
- `CodeReviewRunListView`, `CodeReviewRunDetailView` — the list omits `parentCommitSha` and the
  free-text `summary`; the detail adds them plus the project
- `CodeReviewRunFileListView` — omits `diffHash`, an internal idempotency key
- `CodeReviewFindingListView` — nests `run.selectedCommitSha` and `run.repository.{owner,repoName}` so
  the permalink resolves to the reviewed revision rather than branch HEAD

No view exposes a credential, because none is stored: the PAT lives in configuration, not in a row.

**Not built:** `CodeReviewDashboardView`. The dashboard is a set of aggregates over `code_review_runs`
and `code_review_findings`, and an Entity View is the wrong tool for that — it maps entity attributes,
not `GROUP BY` results. The dashboard query will be a Blaze aggregate returning a purpose-built bean,
written alongside the endpoint in Phase 7 so its shape follows the actual query instead of a guess.

**Verification:** `mvn -o compile` green. The Blaze annotation processor resolved every view attribute
against the JPA metamodel and emitted all 22 view implementations, including the nested-nested
`CodeReviewFindingListViewRunViewRepositoryViewImpl`, so a misspelled attribute path would have failed
the build. Full `EntityViewManager` bootstrap validation still needs a context load, which needs the
database — see the DDL note in §10.

**Phase 2 verification:** `mvn test` green; tables created by `ddl-auto`; `/api/init/master` seeds the
four new statuses; repository/branch/schedule CRUD round-trips through Swagger.

---

## 4. Phase 3 — GitHub change retrieval

### `service/GithubApiClient`

Synchronous `RestClient`, parsed with a private Jackson 2 `ObjectMapper` (matching
`TikaExtractionService`, because Boot 4 auto-configures Jackson 3).

Methods, each mapped to the §4.1 endpoint list:

- `getRepository(owner, repo)` — connectivity validation and `default_branch` discovery
- `listBranches(owner, repo)` — paginated
- `listCommits(owner, repo, branch, sinceUtc, untilUtc)` — paginated, returning committer dates
- `getCommit(owner, repo, sha)` — metadata plus `files[]` with `status`, `additions`, `deletions`,
  `patch`, `previous_filename`
- `getFileContent(owner, repo, path, ref)` — **exact SHA only**, Base64-decoded. §4.1 forbids
  retrieving historical context from branch HEAD; this method has no HEAD-taking overload.
- `listBranchTree(owner, repo, branchRef)` — every file a branch holds, for the `BRANCH_FULL` strategy
  added later. Two calls: `/commits?sha={ref}&per_page=1` to pin the head commit and read its
  `commit.tree.sha`, then `/git/trees/{treeSha}?recursive=1`. Pinning the head first is what keeps the
  result self-consistent — listing at `refs/heads/x` and then fetching each file at `refs/heads/x` would
  span a push that landed in between, so a file could be listed and not found, or found with content the
  listing never described. Every subsequent read uses the pinned SHA, so one run reviews one immutable
  snapshot. Only `type = blob` entries are kept: trees are directories and commits are submodule
  pointers, neither of which has content to read. Returns `BranchTree(head, treeSha, files, truncated)`,
  where `truncated` is GitHub's own cap on a recursive tree (100,000 entries or 7 MB) surfaced rather
  than swallowed — a truncated listing means the branch holds files this review never saw, and a run
  that silently covered half a repository would look exactly like one that covered all of it. The branch
  name travels as a **query parameter**, never a path segment, so `UriBuilder`'s percent-encoding of
  query values means a branch named `feature/a b` cannot reshape the request; in a path segment the `/`
  would. The tree SHA that does go into a path is a 40-hex value from GitHub's own response,
  re-validated against `COMMIT_SHA`, not a stored column.

Cross-cutting behaviour:

- Headers: `Authorization: Bearer <token>`, `Accept: application/vnd.github+json`,
  `X-GitHub-Api-Version: 2022-11-28`
- **Pagination:** follow the `Link` header's `rel="next"`, bounded by `github.max-pages`. Never
  unbounded — §4.1 lists "large responses" as a case to handle.
- **401/403 auth failure:** permanent. Do not retry. Never log the token or the header.
- **403/429 rate limit:** transient. Honour `Retry-After` and read `X-RateLimit-Remaining`.
- **404 on contents:** "file absent at this SHA" — surfaced as a recorded skip reason, not an
  exception storm.
- **404 on the commit listing:** ambiguous by design. GitHub answers 404 rather than 403 for a private
  repository the token cannot see, so one status covers a wrong slug, a missing branch, and a token
  lacking access. `listCommits` catches it and probes `GET /repos/{owner}/{repo}` once to say which: a
  second 404 is reported as a token/visibility problem naming the specific things to check (repository
  selection, `repo` scope or Contents read permission, org SAML SSO authorisation); a 200 means the ref
  is wrong, and the message includes the repository's actual `default_branch`. If the probe itself
  fails, the original exception is kept — it must never mask the real error. Added after the first live
  trigger, where a private `skyworx` repository 404'd and the bare "Not Found" gave nothing to act on.
- **Missing `patch`:** GitHub omits it for very large diffs and binaries; treated as an explicit skip
  reason (§4.1 "missing patch content").
- **Retry:** bounded exponential backoff **with jitter**, transient failures only — the §7 rule. The
  existing `EmbeddingService.callWithRetry` is linear; this needs to be exponential, so it is new code
  shaped like the existing one.
- **Host restriction (§4.1, §14.7):** the base URL comes from configuration only, never from a request
  bean. `owner`, `repo`, `path`, and `sha` are validated against strict patterns and URI-encoded. No
  user-supplied string is ever used as a request target.

### `service/CommitSelectionService`

The highest-value unit-test target in the module. Pure logic, no I/O beyond the injected client:

1. Compute the review window: `LocalDate.now(ZoneId.of(timezone)).minusDays(1)`, giving
   `[start, start + 1 day)` in that zone (§4.2's worked example).
2. Convert both bounds to `Instant` for the GitHub `since`/`until` parameters.
3. Filter to commits whose **committer** date — not author date — falls inside the window.
4. Pick the latest; break ties deterministically on SHA so a re-run selects the same commit.
5. Resolve the parent: **first parent** for merge commits; for a root commit there is no parent, so
   compare against the empty tree `4b825dc642cb6eb9a060e54bf8d69288fbee4904`.
6. If nothing falls in the window, return an empty result and let the orchestrator record `NO_COMMIT`.

### `service/ChangedFileClassifier`

Two entry points producing the same `ClassifiedFile` shape:

- `classify(List<CommitFile>)` — one commit's changed files. Maps GitHub's `files[].status` onto
  `FileChangeStatus`, derives `language` from the file extension, applies the configured exclusion
  patterns, and detects binary and oversized files. For renames it preserves **both**
  `previous_filename` and the new path (§4.3). For removals the source comes from the parent commit;
  for additions from the selected commit.
- `classifyBranchFiles(List<BranchFile>)` — a branch's whole tree listing, added later for the
  `BRANCH_FULL` strategy. A tree entry carries no diff, so there is nothing to detect as binary or
  oversized and no change to classify: every entry is reported as `MODIFIED` with `additions`,
  `deletions`, `previous_filename`, and `patch` all null. Exclusion patterns still apply unchanged, and
  the target-role whitelist is applied here too — see `JavaRoleFilter` below.

Every rejection produces a persisted `skipReason`. §4.3 is explicit: *do not silently ignore files.*

The two entry points do not gate on the same things, deliberately. A commit's file list is already short
and already interesting — someone changed those files — so `classify` checks only exclusion and patch
presence, and the role question is settled later by `JavaRoleFilter.isExamined` on content the pipeline
has fetched anyway. A branch's listing is the entire repository, so `classifyBranchFiles` gates it up
front from the path alone.

`ClassifiedFile` also carries a `contentHash`, which identifies the content a review covered so a re-run
can tell an unchanged file from a changed one — never null. For a commit file it is the SHA-256 of the
patch; for a branch file, the git blob SHA GitHub reported (40 hex, fits `varchar(64)`, and it changes
exactly when the content does), falling back to a hash of the path only if GitHub ever omits it. Both
are content-derived, so one column serves both strategies with no schema change. The commit-side value
was moved here verbatim from the orchestrator's former `diffHash` and is byte-identical to it — a test
recomputes the SHA-256 independently to pin that, because this hash is the idempotency key for rows
already in the database, and changing it would stop every existing `COMPLETED` row matching.

The whole-branch file ceiling lives here rather than in the orchestrator, because it is a
review-eligibility decision that produces a skip reason and is therefore testable without a database.
`code-review.branch-review.max-files` (default 200) bounds how many examinable files one run covers;
overflow entries are marked `SKIPPED` with reason `BRANCH_FILE_LIMIT`, never dropped, so the response
still accounts for every file in the branch. The first `max-files` examinable files in tree order are
kept — arbitrary, but stable, since git's tree order does not change between runs, so re-triggering
resumes the same run and re-examines the same files rather than a different arbitrary slice. Zero or
negative disables the ceiling. A commit-based run is bounded by what that commit touched and needs no
ceiling of its own, so `classify` ignores the property entirely.

The check order is **exclusion → target role → ceiling**, and the order is the whole point of doing the
role filter here. The role check was originally applied only in `reviewOneFile`, after the fetch, which
is correct for a commit run and useless for a branch one: the ceiling had already been spent on whatever
git listed first — `.github/`, root configs, `docs/` — and every one of those was then rejected as
`NOT_A_TARGET_ROLE`, so a run against a large repository reviewed nothing at all while reporting 200
files covered.

### `service/JavaRoleFilter` (added later, at the user's request)

Narrows which `.java` files are examined to the three Spring Boot roles worth reviewing — service,
controller, entity. Files of every other language pass through untouched: this is a filter on Java, not
a general allowlist, so a SQL migration or a Vue component is still reviewed.

Three tiers, first hit wins, configured under `code-review.java-filter.*`:

1. **Filename patterns** — `**/*Service.java`, `**/*Controller.java`, `**/*Entity.java`, …
2. **Directory patterns**, only when no filename matched — `**/service/**`, `**/controller/**`,
   `**/entity/**`, …
3. **Annotations in the file content**, only when neither pattern list matched — `@Service`,
   `@RestController`, `@Controller`, `@Entity`.

The ordering matters when a file could match more than one tier: `entity/OrderService.java` is a service
by name and an entity by location, and the name is the more specific claim.

**Two methods, because the two run strategies can afford different things.**

- `isExamined(path, content)` is the commit-run decision and runs all three tiers. It is called from
  `reviewOneFile`, where the pipeline has already fetched the content to build the review context, so
  tier three costs no additional GitHub call. Deciding from the *diff* was never an option: a patch holds
  only changed lines, so an edit near the bottom of an existing `@Service` class carries no annotation to
  find.
- `isTargetByPath(path)` is the whole-branch decision, added when the `BRANCH_FULL` strategy exposed that
  the first method's permissiveness does not survive contact with an entire repository. It runs tiers one
  and two only, and it drops the `.java` gate — so it is a **whitelist over every language**, which is
  what "only service, controller and entity, and all files in the service folder" means when the input is
  the whole tree rather than one commit's eight files. Without it a branch review would examine every
  Vue component, migration and README in the repository.

Dropping tier three for a branch run is a **deliberate false negative**, and a test pins both sides of it
so it is not later mistaken for a bug: `isExamined` accepts an `@Service` class named `OrderProcessor`
outside any role directory, while `isTargetByPath` rejects the same path. Consulting tier three there
would mean fetching every Java file in the branch that missed both patterns — thousands of GitHub calls
to catch the occasional unconventionally named service, all spent before the file ceiling could bound
anything.

Two implementation decisions worth keeping:

- **It is a text search, not a parse.** An annotation named in a comment or a string literal counts as a
  match, and there is a test asserting exactly that so the behaviour is not later mistaken for a bug.
  Matching is on a whole-token boundary, so `@Controller` does not match `@ControllerAdvice`. The bias is
  deliberate: a false positive costs one review, a false negative silently drops a file the user asked to
  have examined.
- **One skip token for both call sites.** `NOT_A_TARGET_ROLE` lives on `ChangedFileClassifier` beside the
  other classifier-produced reasons, and `reviewOneFile` references it, so a report reads the same
  whichever strategy or stage produced the rejection.

A file matching none of the applicable tiers is recorded as `SKIPPED` with reason `NOT_A_TARGET_ROLE`
and logged, never dropped — the same rule the exclusion patterns follow.

Known gap: the directory patterns also reach test sources, so `src/test/java/.../service/OrderServiceTest.java`
is examined. Excluding tests means adding a `src/test` pattern to `code-review.exclusions.patterns`; that
was left to configuration rather than defaulted, because it would also drop test resources of every other
language and that is a separate decision.

**Phase 3 verification:** all of the above green under mocked HTTP, with **no AI and no real
credentials** in the test path (§16). Optionally one manual smoke run against a public repository with
a read-only PAT.

---

## 5. Phase 4 — Guidelines, context, validation

### `service/AgentsGuidelineResolver`

Implements §5:

- For a file path, compute the ancestor directory chain and probe for `AGENTS.md` at the root and each
  ancestor, **fetched at the exact selected commit SHA**.
- Return an ordered list, root first, each entry carrying path, verbatim content, and a SHA-256
  content hash.
- **Cache per run.** Without this, a 200-file commit re-fetches the same root `AGENTS.md` 200 times and
  burns the rate limit.
- When nothing resolves, record `GUIDELINE_NOT_FOUND` explicitly (§11) — never proceed silently as if
  guidelines were present.
- Guideline text is stored verbatim and will later be placed inside a clearly delimited *untrusted*
  block in the prompt. It is never permitted to alter system instructions (§5, §8).

**Conflict detection — honest scoping.** §5 asks that conflicting guidelines be reported rather than
silently resolved. Genuine semantic conflict detection needs an LLM, which is deferred. v1 implements a
**lexical** check only: same section heading appearing at two levels with differing content is recorded
as a warning in the run summary. This is documented as a known limitation rather than oversold.

### `service/ReviewContextBuilder`

Builds the per-file context (§6) and enforces the token budget:

- Small files: whole file. Medium: diff plus enclosing method/class. Large: diff, imports,
  declarations, relevant methods.
- Deleted files source from the parent commit; added files from the selected commit.
- Oversized files are split on class/method boundaries into segments; `segmentCount` and
  `segmentsCompleted` on the run-file row enforce "not `COMPLETED` until every segment succeeded".
- Never silently truncate — a truncation is recorded.
- Budgeting uses the ~4-chars-per-token estimator already in `DocumentChunkingService`.

### `utility/SecretRedactor`

Regex-based scrubbing of API keys, bearer tokens, `password=` assignments, PEM private-key blocks, and
credentials embedded in JDBC/URI connection strings — each replaced with `[REDACTED]`. Applied to the
diff and source context **before** anything leaves the process (§8, §14.4). Needs a negative test
proving ordinary code is left untouched, or it will silently mangle reviews.

### `service/FindingValidator`

Built now, works regardless of which model is eventually used. Enforces every §9 rule:

- severity within the allowed set; confidence within `[0.0, 1.0]`; evidence non-blank
- `filePath` equals the file under review — cross-file findings are rejected
- line range lies inside the actual snapshot; for `side = OLD`, validated against the **parent** snapshot
- when a finding claims changed code, the line must actually appear in the diff hunk
- `guidelineReference` must match one of the guidelines resolved for this run

Invalid findings are **persisted** with `validationStatus` and `validationDetail`, never discarded —
§9 requires the diagnostics survive.

---

## 6. Phase 5 — AI seam, deliberately inert

The contract is defined now so the pipeline compiles and is fully testable without a model:

- `bean/codereview/FileReviewInput` and `FileReviewResult` — the §6/§9 shapes, as project-convention
  beans rather than the spec's illustrative records
- `service/CodeReviewAgent` — `@Agent` / `@Action` / `@AchievesGoal`, following
  `ProjectKnowledgeRagAgent` exactly
- `service/CodeReviewInvoker` — **the seam.** While `code-review.inference.enabled=false` it marks the
  run-file `SKIPPED` with reason `INFERENCE_DISABLED` and the run completes as
  `COMPLETED_WITH_WARNING`. Everything upstream — GitHub retrieval, classification, guideline
  resolution, context building, redaction, persistence, aggregation — is exercised end to end with no
  LLM in the loop.

When inference is eventually enabled:

- Use `generateText(...)` with a server-side parser, **not** `createObject`. Measured behaviour on this
  model is that structured output comes back under-populated.
- Keep the prompt to one small diff slice with one instruction and no system prompt — the shape that
  was validated and accepted on the RAG path.
- Re-verify `/v1/models` first. That check could not be performed during planning (the live call was
  correctly blocked as out of scope for a local analysis task), so the model constraint rests on the
  measurements recorded 2026-11-14.
- Derive severity/confidence sanity server-side via `FindingValidator`; do not trust the model's
  self-assessment. The RAG module learned this the hard way with an inverted `grounded` flag.

---

## 7. Phase 6 — Orchestration

`service/CodeReviewRunService` — non-transactional top-level method with a `TransactionTemplate`
built in `@PostConstruct`, structurally identical to `KnowledgeProcessingService`:

```
run(repositoryId, branchId, reviewDate)
  tx1        claim or create the run row; unique constraint rejects a duplicate; set PROCESSING
  (no tx)    GitHub: select the commit for the day                      [remote]
  tx2        persist commit metadata — or set NO_COMMIT and return
  (no tx)    GitHub: fetch the commit's changed files                   [remote]
  tx3        persist run-file rows as PENDING / SKIPPED
  (no tx)    GitHub: fetch AGENTS.md at the exact SHA                   [remote]
  tx4        persist guideline snapshots — or GUIDELINE_NOT_FOUND
  for each file, sequentially (file-concurrency = 1):
     tx      mark the file PROCESSING
     (no tx) GitHub: fetch source at the exact SHA                      [remote]
     (no tx) build context, redact secrets
     (no tx) invoke the agent — only when inference.enabled              [remote]
     tx      validate findings, persist them, mark COMPLETED / FAILED
  tx-final   aggregate counts; set COMPLETED / COMPLETED_WITH_WARNING / FAILED
```

The `BRANCH_FULL` pipeline, added later, is the same skeleton with commit selection replaced by a tree
listing. `executeRun(repository, branch, reviewDate, strategy, Consumer<ClaimedRun> pipeline)` owns the
claim, the `FAILED` fallback, and the run id; the two strategies differ only in what they hand it:

```
runBranch(repositoryId, branchId)          # review_date = today in code-review.timezone
  tx1        claim or create the run row with strategy BRANCH_FULL; set PROCESSING
  (no tx)    GitHub: resolve the branch head, then list its whole tree recursively   [remote]
             classify, applying exclusions and the max-files ceiling
  tx2        persist the head commit with parent = null — there is no diff to compare against
  tx3        persist run-file rows as PENDING / SKIPPED, hash = git blob SHA
  (no tx)    GitHub: fetch AGENTS.md at the pinned head SHA                          [remote]
             — probed only against the paths that will actually be reviewed
  tx4        persist guideline snapshots — or GUIDELINE_NOT_FOUND
  for each file, sequentially (file-concurrency = 1):
             identical to the commit pipeline, except parentSha is null so every file is
             read at the head SHA and validated against the NEW side
  tx-final   aggregate counts, plus a note when GitHub truncated the tree listing
```

Three differences follow from covering the branch instead of one commit: no parent SHA to fall back to
for a removed file, no patch to put in the review context (`ReviewContextBuilder` needed no change — a
null patch simply yields an empty diff section), and a ceiling on how many files are examined. The
guideline resolver is given only the paths that will be reviewed, not the whole tree: it probes one
`AGENTS.md` per ancestor directory of every path it receives and caps the probe list, so handing it the
entire branch would spend the cap on directories nothing is being reviewed in.

No transaction is ever held open across a GitHub or inference call — §11, and the project-specific
`@Transactional` rules in AGENTS.md.

- **Failure isolation:** a per-file exception is caught, recorded on that row, and the loop continues.
  A-completes / B-fails / C-completes yields `COMPLETED_WITH_WARNING`, exactly §11's example.
- **Idempotent resume:** a run-file already `COMPLETED` whose commit SHA, file path, content hash, and
  guideline hash are all unchanged is skipped without re-invoking inference (§10). The content hash is
  the diff for a commit run and the git blob SHA for a branch run — both content-derived, so the resume
  check is strategy-agnostic.
- **Restartability:** v1 does **not** auto-resume on startup — that needs a claim/lease mechanism to be
  safe across instances. Instead, re-triggering is made safe by the unique constraint plus per-file
  idempotency. A stale-`PROCESSING` reaper is recorded as a follow-up rather than silently built.
- **Concurrency:** global and file concurrency stay at 1. §7 and §15 both forbid raising them before
  inference performance is measured.

`service/CodeReviewScheduler` — `@Scheduled(cron = "${code-review.schedule.cron}", zone =
"${code-review.timezone}")`, gated by `@ConditionalOnProperty` on `code-review.enabled`. Iterates
enabled repositories × enabled branches, wrapping each in its own try/catch so one repository's failure
cannot stop the rest. The manual trigger endpoint calls the same service method (§4.2).

Adding `@EnableScheduling` is a new capability for this application; it is gated so the existing
knowledge-base and user modules are untouched.

---

## 8. Phase 7 — REST API

`controller/CodeReviewController`, `@RequestMapping("/api/code-review")`, automatically JWT-protected
by the existing `/api/**` rule. Returns the service `ApiResponse<T>` directly, typed to the concrete
`T`, per AGENTS.md §25.11.

```
GET  /api/code-review/repository/list            # Phase 2 — delivered
POST /api/code-review/repository/save            # Phase 2 — delivered
POST /api/code-review/repository/update          # Phase 2 — delivered
POST /api/code-review/repository/delete          # Phase 2 — delivered
GET  /api/code-review/branch/list                # Phase 2 — delivered, reads back the selection
POST /api/code-review/branch/save                # Phase 2 — delivered, replaces the selection by flag
GET  /api/code-review/schedule/list              # Phase 2 — delivered
POST /api/code-review/schedule/save              # Phase 2 — delivered, upsert on repositoryId
POST /api/code-review/repository/validate        # Phase 3 — live GitHub connectivity check
GET  /api/code-review/repository/branch/list     # Phase 3 — live branches from GitHub
POST /api/code-review/run/trigger-project        # Phase 6 — delivered, synchronous, body is { projectId, reviewDate? },
                                                 #           response is { run, files[] } per repository×branch pair
POST /api/code-review/run/trigger-branch         # added later — delivered, synchronous, whole-branch (BRANCH_FULL) review,
                                                 #           body is { repositoryId, branchId }, same { run, files[] } response
POST /api/code-review/run/trigger                # Phase 6 — repository-scoped, not delivered
GET  /api/code-review/run/list                   # Phase 7
GET  /api/code-review/run/detail                 # Phase 7
GET  /api/code-review/run/file/list              # Phase 7 — paginated history; the trigger already returns the files inline
GET  /api/code-review/run/finding/list           # Phase 7
GET  /api/code-review/dashboard                  # Phase 7
```

### Delivered: `POST /run/trigger-project` (Phases 3–6)

Body is `CodeReviewProjectTriggerRequest`: **`projectId`**, plus an optional **`reviewDate`**
(`yyyy-MM-dd`). Strategy, commit, and branch list stay server-derived — accepting a branch would let a
caller point a run at one that was never selected, and accepting a commit would bypass the
one-commit-per-day rule `uq_crr_identity` is built on. This is a separate bean from the existing
`ProjectIdRequest`, whose field is named `id`.

`reviewDate` was added after the first live trigger, when the default target (previous calendar day)
came back `NO_COMMIT` on a branch whose last commit was older. Absent still means the previous calendar
day in `code-review.timezone`, so the scheduled job's target is unchanged. A named day is applied to
every repository in the fan-out, keeping the results comparable. Jackson 3 carries `java.time` support
in `jackson-databind` (verified: `tools/jackson/databind/ext/javatime/deser/LocalDateDeserializer`), so
an ISO date string binds from the JSON body with no `@JsonFormat` and no module registration — note
that the `@DateTimeFormat(iso = ISO.DATE)` annotations elsewhere in `bean/web/` serve *query-string*
binding and have no effect on a request body.

Two decisions shape it:

- **Synchronous.** The request blocks until every repository×branch pair has finished. A project with
  several active repositories can hold the connection open for minutes, so callers must set a client
  read timeout well above the usual default. The async dispatch described in §7 was not built.
- **Partial failure is data, not an error.** The response is one `CodeReviewRunResult` per run, each
  with its own terminal status. `status=true` means at least one run executed; individual entries say
  whether that run was `COMPLETED`, `COMPLETED_WITH_WARNING`, `NO_COMMIT`, `GUIDELINE_NOT_FOUND`, or
  `FAILED`. Re-triggering the same project on the same day resumes the existing runs rather than
  duplicating them, so a retry after a partial failure redoes only the outstanding work.

The per-file breakdown was added to the trigger response after the first successful live run reported
counts but no paths — `totalFiles=8, analyzedFiles=0` does not distinguish "eight files reviewed and
clean" from "eight files excluded as lock files and binaries". `CodeReviewRunResult` is
`record (CodeReviewRunDetailView run, List<CodeReviewRunFileListView> files)`, so each entry now names
every file the commit touched, with `changeStatus`, `language`, `additions`/`deletions`, and a
`skipReason` on the ones not reviewed. `files` is legitimately empty for a run that ended before files
were persisted (`NO_COMMIT`, or a failed commit fetch).

Composed as a record rather than as a collection attribute on `CodeReviewRunDetailView`: this codebase
maps no collection in any Entity View and Blaze 1.6.20 exposes no `@OrderBy` for views, and view
mappings are validated when the `EntityViewManager` is built at startup — introducing the first one on
the endpoint whose whole purpose is reporting results would trade a flatter response for a failure mode
that stops the application booting. Instead `loadRunResult` issues both queries inside one
`TransactionTemplate` step, ordering the files ascending by id, which reproduces the order GitHub
reported them because `persistRunFiles` inserts in that order. Two queries per run, and the run count is
repository×branch pairs (single digits), not files.

Jackson 3 record serialization was verified empirically rather than assumed, since no record had been
returned from an endpoint here before: `CodeReviewRunResult` emits `{"run": {...}, "files": [...]}` and
the generated Blaze view impls leak none of their `$$_`-prefixed `EntityViewProxy` methods, because
those names do not begin with `get`/`is`. Deserializing the record fails (`CodeReviewRunDetailView` is
an interface with no creator), which is expected and already true of every Entity View in the project —
this type is response-only.

Guard order in `triggerProject`, each returning early with its own message key: module disabled →
`codereview.module_disabled`; blank/invalid `projectId` → the validator's message; project not `AKT` →
`codereview.repository.project_not_found`; absent `GITHUB_TOKEN` → `codereview.github.token_missing`;
no enabled repository → `codereview.repository.none_enabled`. A repository with no enabled branch is
reported as `missingBranches` while the runs that did execute are still returned.

The review date is resolved **once** from `CommitSelectionService.reviewZone()` so every repository in
the project reviews the same day.

### Delivered: `POST /run/trigger-branch` (added later, at the user's request)

Answers a different question from `trigger-project`: *"what is wrong with this branch"* rather than
*"what changed yesterday"*. Body is `CodeReviewBranchTriggerRequest` — **`repositoryId`** and
**`branchId`**, both mandatory, and nothing else. The strategy is server-derived, so a caller cannot ask
for this endpoint and get a commit review or the other way round. The ids come from
`GET /repository/list` and `GET /branch/list`; the branch must belong to the repository, which the query
enforces so one repository's id cannot be substituted for another's.

A branch **name** was deliberately not accepted. `code_review_runs.branch_id` is `NOT NULL` and part of
`uq_crr_identity`, so a name would have to be resolved to a registered branch row before a run could be
claimed — and resolving it by creating one would let a request silently add review configuration nobody
chose. `enabled` is *not* required on either row: that flag governs inclusion in the automatic fan-out,
and an explicit request naming a branch is a deliberate act.

`review_date` is `NOT NULL` and part of `uq_crr_identity`, but a whole-branch review covers no particular
day. It records the day the run was **requested**, in `code-review.timezone` — which makes re-triggering
on the same day a resume of the existing run rather than a duplicate, and gives the next day a fresh run
against a fresh head. Reusing the existing unique key is why this needed no DDL at all: no new column, no
new constraint, no new status seed.

The response is the same `CodeReviewRunResult` record — one `run` plus every `files[]` entry, each with
`changeStatus`, `language`, and a `skipReason` on the ones not reviewed. Two of its fields read
differently for this strategy, both correctly: `additions`/`deletions` are null because a whole file has
no diff, and `side` on any finding is always `NEW` with `parentCommitSha` null because there is no
earlier snapshot to compare against. `files[]` is marked, not filtered — every file GitHub reports is
present, including the ones excluded by pattern (`EXCLUDED_PATTERN`), the ones outside the target roles
(`NOT_A_TARGET_ROLE`), and the ones past `code-review.branch-review.max-files` (default 200,
`BRANCH_FILE_LIMIT`), so the response accounts for the whole branch.

What is *examined* is much narrower than what is listed. `code-review.java-filter` acts as a whitelist
here rather than as a narrowing of Java: the service, controller and entity files plus everything under
those directories, and nothing else. See §4's `JavaRoleFilter` for why the annotation tier is dropped for
this strategy, and for the false negative that costs.

**Synchronous, and far slower than the project trigger.** One call fetches and reviews up to `max-files`
whole files sequentially at a file concurrency of 1, so the wall-clock cost is the file count times the
per-file cost. Callers must set a client read timeout well above the usual default, and this endpoint
should not be triggered concurrently against the same repository.

Guard order, each returning early with its own message key: module disabled → `codereview.module_disabled`;
missing/invalid ids → the validator's message; absent `GITHUB_TOKEN` → `codereview.github.token_missing`;
repository absent or `REM` → `codereview.repository.item_not_found`; branch not belonging to that
repository → `codereview.branch.not_in_repository`. A claim failure — the run row could not be created or
found at all, so there is no run to report on — returns `codereview.run.claim_failed`.

The guideline gate is unchanged and applies with more force here: the `AGENTS.md` check runs **before**
the per-file loop, so a repository with no `AGENTS.md` terminates at `GUIDELINE_NOT_FOUND` and a
whole-branch review produces no findings at all (§5's rule — never proceed silently as if the guidelines
were empty). What to do for such a repository is still undecided: fall back to an app-stored per-project
guideline, a built-in default ruleset, or review with no guideline. Until it is, this endpoint is only
observable against a repository that actually carries an `AGENTS.md`.

Classes delivered with it: `GithubApiClient`, `CommitSelectionService`, `ChangedFileClassifier`,
`AgentsGuidelineResolver`, `ReviewContextBuilder`, `FindingValidator`, `ReviewAgent` +
`DisabledReviewAgent`, `utility/SecretRedactor`, and the `CodeReviewRunService` orchestrator.

Services are `CodeRepositoryService`, `CodeReviewBranchService`, `CodeReviewScheduleService` — all
three follow `ProjectService` exactly: `IlKeiValidator` for the payload, `IlKeiEntityValidator` for
foreign keys, Blaze + QueryDSL path metadata with an explicit `.from(...)`, Entity View pagination via
`withKeysetExtraction(true)`, and soft delete through the `REM` general status. Reads are
`@Transactional(readOnly = true)`, writes plain `@Transactional`.

`CodeRepositoryService.findActiveRepository(Long)` is the shared resolver the other two use, so the
`REM` check lives in one place.

Every endpoint that takes a `projectId` or resolves a repository validates the project through
`IlKeiEntityValidator` on `p.status.code = 'AKT'` before any work, mirroring
`KnowledgeRagService.projectIsActive`. Reads paginate through Entity Views; findings support severity,
repository, and date filters plus keyword search.

Finding responses include a GitHub permalink built from the **reviewed commit SHA**, never branch HEAD
(§13.4's rule, honoured in the API even though there is no UI).

New Bahasa Indonesia keys go into `error_message.properties` under a `codereview.*` namespace.

No frontend is written. Swagger UI (`/swagger-ui`) is the delivery surface.

---

## 9. Phase 8 — Tests and documentation

Mockito unit tests; no `@SpringBootTest` (§16). In priority order:

| Test | Covers | State |
|---|---|---|
| `CommitSelectionServiceTest` | Jakarta day boundaries and their UTC conversion, committer-vs-author date, pagination merge, merge-commit first parent, root-commit empty tree, no-commit case, tie-break determinism | **Delivered** (9 tests) — pagination and merge-parent cases still open, they belong with `GithubApiClientTest` |
| `ChangedFileClassifierTest` | Every change status, rename preserving both paths, binary, oversized, each exclusion pattern, and that a skip reason is always populated. Plus the branch entry point: the blob SHA as content hash and its path-hash fallback, no-change reporting for a whole file, the same exclusions, order preserved, an empty branch, a content hash that is never null, and the `max-files` ceiling marking rather than dropping overflow — counted against examinable files only, disabled when not positive, and ignored by a commit-based run. Plus the target-role whitelist: role filenames and role directories across every language, exclusion ahead of the whitelist, a file outside the roles costing nothing against the ceiling, disabled filter, the annotation tier *not* running for a branch listing, and `classify` deliberately left un-narrowed. Plus a test that recomputes the patch SHA-256 independently to pin the hash byte-for-byte, since it is the idempotency key for rows already in the database | **Delivered** (33 tests) |
| `ReviewContextBuilderTest` | Governing-guideline selection including the directory-prefix boundary, redact-before-truncate, per-section budgets, overall-ceiling trim order | **Delivered** (5 tests) — not in the original list |
| `AgentsGuidelineResolverTest` | Root-only, root+nested ordering, deep nesting, none found, and per-run caching (asserts no duplicate fetch) | Not written |
| `FindingValidatorTest` | Each rejection rule, `OLD`-side parent validation, cross-file rejection, confidence bounds, and that invalid findings are persisted with diagnostics | **Delivered** (13 tests) — persistence of rejected findings belongs with the orchestrator test |
| `SecretRedactorTest` | Each secret shape, plus a negative test that ordinary code is untouched | **Delivered** (7 tests) |
| `JavaRoleFilterTest` | Non-Java files untouched, each of the three tiers in isolation, whole-token annotation boundary (`@ControllerAdvice` is not a controller), scanning past a near miss, annotation in a comment, absent content, disabled, replaced patterns, blank entries. Plus the path-only whitelist used by a whole-branch run: role names and role directories accepted, every other language rejected, no annotation fallback, disabled, replaced patterns — including one test asserting the same file is accepted by `isExamined` and rejected by `isTargetByPath`, so the deliberate divergence cannot be read as a bug later | **Delivered** (18 tests) — not in the original list |
| `GithubApiClientTest` | Mocked HTTP: `Link` pagination, 401 not retried, 429 honours `Retry-After`, 404 on contents, timeout, oversized body, missing `patch`, and that a blank configured token fails before any request is made. Also `listBranchTree`: head pinned before the tree is read, non-blob entries skipped, `truncated` surfaced, a branch with no commit, and a missing tree SHA | Not written |
| `CodeReviewRunServiceTest` | Failure isolation (A/B/C → `COMPLETED_WITH_WARNING`), duplicate-run prevention via the unique constraint, idempotent resume skipping a `COMPLETED` file. Also `triggerBranch`: the branch-must-belong-to-the-repository guard, the `BRANCH_FULL` claim and its same-day resume, and the `claim_failed` path | Not written — needs a database, so it is blocked on the test-profile decision below |

### How these are run

`mvn test` in this project applies `ddl-auto=update` to a **shared remote PostgreSQL**, so the suite
is a database operation and must not be run without authorization. The delivered tests are all
context-free — plain construction, no Spring, no database — so they are run directly through the JUnit
Platform Launcher against `target/classes` and `target/test-classes`, bypassing the Maven test
lifecycle entirely. Latest result: **95 tests, 0 failures** (the six classes above plus the
pre-existing `PassageRerankerServiceTest`).

That suite covers the classification and validation logic only. **No test exercises the whole-branch
service path**: `triggerBranch` needs a database to claim a run, and `listBranchTree` needs mocked HTTP,
so both are verified only by `mvn -o test-compile`. The same gap already applied to `triggerProject`.

`mvn -o test-compile` is the compile gate. Two production bugs were found this way and fixed before
delivery: a greedy quantifier in `SecretRedactor` that matched `getPasswor` out of `getPassword()`, and
a replacement string that discarded the captured key so `password=…` redacted to `[REDACTED]` with no
label.

A `src/test/resources/application.properties` or a Testcontainers profile is still needed before any
test that touches persistence can be written.

Documentation to deliver (§18): GitHub fine-grained PAT setup (repository metadata and contents,
read-only), repository registration, branch selection, schedule configuration, `AGENTS.md` resolution
behaviour, review statuses and what each means, the `GITHUB_TOKEN` requirement, the
`POST /api/init/master` seeding step, configuration property reference, troubleshooting, and the
security posture.

Verification commands use the project Maven at `D:\app\mvn\bin\mvn.cmd` — not the `mvnw` wrapper.

---

## 10. Risks and known limitations

Stated plainly, per `git.md` §18.14 and AGENTS.md §22.

1. **The module produces no findings until a capable model exists.** With inference disabled every run
   ends `COMPLETED_WITH_WARNING` and every file is `SKIPPED: INFERENCE_DISABLED`. What ships is the
   ingestion, guideline-resolution, and tracking half — genuinely useful and fully tested, but it is not
   yet an automated *reviewer*. This is the single largest gap against `git.md`'s stated objective.
2. **No frontend.** Five specified pages are not delivered. Swagger UI is the only interface.
3. **Authorization is coarse.** Any authenticated user can read any project's reviews. This matches the
   existing RAG module rather than regressing it, but `git.md` §14.9 is not satisfied. Closing it needs a
   user↔project mapping that does not exist anywhere in this schema — a cross-cutting change affecting
   the knowledge-base module too.
4. **The seven `code_review_*` tables were created on the shared remote database before that was
   authorized.** `NotulenAiApplicationTests` is a plain `@SpringBootTest` and there is no
   `src/test/resources` override, so `mvn -o test` boots the real `EntityManagerFactory` against
   `localhost:5434/ragdb` with `spring.jpa.hibernate.ddl-auto=update`. Running the suite during
   Phase 2 therefore applied additive DDL: the new tables, their indexes, and (because the tables were
   new, so Hibernate went through its schema *creator* rather than its migrator) their unique
   constraints. Nothing was dropped and no rows were touched — `update` mode does not remove tables or
   columns — but it was a write to a shared environment that should have been gated.

   Two consequences. First, running `mvn test` in this project **is** a database operation; until a test
   profile with its own datasource exists, the suite cannot be run casually. Second, whether
   `uq_crr_identity` actually landed is still **unverified** — the read-only `information_schema` probe
   that would confirm it was blocked pending authorization. If it did not, the duplicate-run guard that
   §4.2 of `git.md` requires is absent, and Hibernate's `update` mode will never add it to the
   now-existing table. That needs a manual `ALTER TABLE`, plus explicit authorization.

   A third consequence arrived with the 2026-11-14 credential decision. `code_repositories` was
   created with `credential_encrypted varchar(1024) NOT NULL` and `credential_label varchar(16)`, and
   the entity no longer maps either. `ddl-auto=update` never drops columns, so both survive in the
   shared schema — and the `NOT NULL` on `credential_encrypted` will reject every insert from now on,
   because Hibernate omits columns it does not map. Registering a repository therefore needs this
   first, run manually with explicit authorization:

   ```sql
   SELECT count(*) FROM code_repositories;   -- confirm nothing is stored before dropping
   ALTER TABLE code_repositories DROP COLUMN credential_encrypted, DROP COLUMN credential_label;
   ```

   Dropping is preferred over a bare `DROP NOT NULL` because both columns are dead under the new
   design. The count check matters: no repository row should exist, since every startup after
   `CODE_REVIEW_ENABLED=true` failed in `CredentialCipher` before the module could serve a request,
   but that is an inference from the logs rather than a verified fact.

   The durable fix is a `src/test/resources/application.properties` pointing the test suite at a local
   or Testcontainers PostgreSQL, which also removes the standing risk of a green build having silently
   mutated the shared schema.
5. **Guideline conflict detection is lexical only.** Semantic conflicts between nested `AGENTS.md` files
   are not detected in v1.
6. **GitHub rate limits.** The contents endpoint is called once per file plus once per guideline. The
   per-run guideline cache and the `max-pages` bound mitigate this but do not eliminate it; a 200-file
   commit is the stress case.
7. **`app.tls.insecure` currently defaults to `true`.** The GitHub client deliberately opts out of that
   customizer. If it were changed to inherit the auto-configured builder, PAT-bearing requests would
   lose certificate validation.
8. **No stale-run reaper.** A crash mid-run leaves the row in `PROCESSING`. Recovery is manual
   re-trigger, which the unique constraint and per-file idempotency make safe.
9. **Context budget values are unvalidated.** The `context.*` numbers come from `git.md`, not from
   measurement against `lfm-1.2b`. They are placeholders for a future model.
10. **The repository uniqueness check is case-insensitive but the database constraint is not.**
    `saveRepository` folds `owner`/`repoName` to lowercase before checking for a duplicate, because
    GitHub itself treats `IlkeiApps/x` and `ilkeiapps/x` as the same repository. `uq_code_repo` on
    `(project_id, owner, repo_name)` is an ordinary B-tree constraint and so is case-sensitive. The
    application check is therefore *stricter* than the constraint, which fails closed — but it also
    means the constraint will never be the thing that catches a case-variant duplicate, and two
    concurrent registrations differing only in case would both be inserted. Closing that properly needs
    a unique index over `lower(owner), lower(repo_name)`, which is DDL against the shared database and
    is deliberately not done here. The practical impact is a duplicate configuration row, not data loss.
11. **Deselecting a branch does not delete it.** `code_review_runs.branch_id` references
    `code_review_branches`, so removal would orphan run history. Deselection sets `enabled = false` and
    re-selecting re-enables the existing row rather than inserting a second one, which is what
    `uq_crb_repo_branch` requires.
12. **The trigger endpoint holds a request thread for the whole review.** `/run/trigger-project` is
    synchronous by decision, so one call occupies a Tomcat worker for as long as every
    repository×branch pair takes — minutes when GitHub is slow or rate-limited. A handful of concurrent
    triggers against large projects could exhaust the worker pool and stall unrelated API traffic. With
    `execution.global-concurrency = 1` the work itself is serialized, which bounds GitHub load but not
    thread occupancy. Moving to async dispatch (the §7 design, returning a run id to poll) is the fix if
    this endpoint ever gets more than one caller at a time. `/run/trigger-branch` has the same shape and
    a much larger exposure: it is bounded by `code-review.branch-review.max-files` rather than by what
    one commit happened to touch, so it holds the worker for the ceiling's worth of sequential fetches
    and, when inference is on, model calls. It is the endpoint that would exhaust the pool first.
13. **A whole-branch review covers at most `max-files` files, and the ceiling is arbitrary.** 200 was
    chosen to bound one synchronous call, not from any measurement of repository size. Overflow is marked
    `BRANCH_FILE_LIMIT` rather than dropped, so the response is honest about it, but a run against a
    large repository reviews a *slice* — the first 200 files that survive exclusion and the target-role
    whitelist, in git's tree order. The slice is stable across re-triggers, which makes resume work, but
    it also means the same files are always the ones left out. Raising the ceiling without async dispatch
    turns item 12 into an outage.
14. **Stale run-file rows are never pruned.** `persistRunFiles` inserts and updates but never deletes a
    row whose path is absent from the current listing. For a commit run that is harmless — each run is
    keyed on its own commit. For a `BRANCH_FULL` run re-triggered on a later day, a file deleted from the
    branch leaves its old row behind, so `files[]` can name a path the branch no longer holds. Fixing it
    means a delete scoped to the run inside `persistRunFiles`, which is a behaviour change to an existing
    method and is deliberately not bundled with the feature that exposed it.
15. **A whole-branch review can miss a correctly-annotated role class, and reviews non-Java files in role
    directories.** Both follow from `isTargetByPath` deciding eligibility from the path alone. A
    `@Service` class named `OrderProcessor` outside any `service/` directory is `NOT_A_TARGET_ROLE`, where
    a commit run would have examined it via the annotation tier — accepted, because running that tier on a
    branch would mean fetching every Java file that missed both patterns before the ceiling could bound
    anything. The mirror case is that the directory tier is not gated on `.java`, so a `.ts` or `.sql` file
    sitting in a `service/` directory *is* examined; that is what "all files in the service folder" asks
    for, but it is worth knowing when reading a branch run's file list. Widening the filename patterns is
    the configuration lever for the first case; no code change is needed.

---

## 11. Out of scope for v1

Explicitly deferred, so the boundary is unambiguous:

- Any write to GitHub — pushes, pull requests, comments (§4 and §14 require read-only; this stays true)
- Executing repository scripts, builds, or binaries
- GitHub App authentication (§4.1 asks only that the design not preclude it — with the PAT held in
  configuration rather than in a column, swapping in an App installation token needs no schema change)
- Embedding source code into pgvector / a codebase RAG pipeline (§6 explicitly excludes this)
- Supervisor-planner orchestration (§7 explicitly forbids it)
- Concurrent inference (§15 forbids raising concurrency before measurement)
- Automatic resume of interrupted runs on startup
