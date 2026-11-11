# AGENTS.md — Spring Boot 4 / Java 25 / Spring Data JPA / Hibernate / PostgreSQL

> **Purpose:** Give AI coding agents (Codex, Claude Code, Gemini CLI, Copilot, and similar tools) precise, durable instructions for working safely and consistently in this repository.
>
> **Applicability:** These instructions apply to the entire repository unless a more specific nested `AGENTS.md` overrides them.
>
> **Important:** Treat the repository's actual code, `pom.xml`, migrations, tests, and deployment configuration as the source of truth. Do not assume a library, API, or configuration exists merely because it is described here.

---

## 1. Core operating principles

### Karpathy-inspired coding guidelines

Apply these principles when writing, reviewing, debugging, or refactoring code. They complement—not replace—the repository-specific architecture and technology rules below.

1. **Think before coding.** Do not silently guess. Identify assumptions, uncertainties, and materially different interpretations. Ask a focused question when ambiguity affects correctness. Surface trade-offs and simpler alternatives before proceeding when useful.
2. **Simplicity first.** Implement the minimum complete solution that satisfies the request. Avoid speculative features, unnecessary abstractions, single-use helpers, excessive configurability, and defensive handling of impossible states. Prefer readable, idiomatic Java that fits the existing Spring Boot architecture.
3. **Surgical changes.** Change only what the request requires. Do not refactor unrelated code, reformat neighboring files, rename established APIs, or remove pre-existing code/comments merely because they appear unused. Match local style—even when another style might be preferred. Clean up only unused artifacts introduced by your own changes. Each changed line should be traceable to the task.
4. **Goal-driven execution.** Define observable acceptance criteria for nontrivial work. For bug fixes, reproduce the failure when practical; for behavior changes, add or update focused tests; for refactoring, verify behavior remains unchanged. Iterate until the stated criteria are met, and report checks actually performed.
5. **Scale rigor to the task.** Trivial changes do not require an elaborate plan. For multi-step or high-impact work, provide a brief plan with explicit verification steps before implementation.

1. **Understand before editing.** Inspect the relevant packages, existing conventions, dependency versions, tests, and schema migrations before proposing or implementing a change.
2. **Prefer minimal, cohesive changes.** Do not perform unrelated refactoring, formatting, renaming, dependency upgrades, or architectural changes in a task.
3. **Preserve compatibility.** Consider API consumers, database data, serialization, security, scheduled jobs, and operational behavior before changing public contracts.
4. **Use established patterns.** Reuse project abstractions and conventions unless they are demonstrably incorrect or harmful. Explain deviations.
5. **Correctness before cleverness.** Prefer clear, maintainable Java over clever streams, reflection, excessive generics, or premature optimization.
6. **Measure performance.** Do not claim a query or implementation is faster without evidence. For performance-sensitive changes, inspect SQL, query plans, indexes, and representative data volumes.
7. **No silent assumptions.** If requirements, business rules, cardinality, nullability, transaction semantics, or performance expectations are unclear and materially affect correctness, ask a focused question or state the assumption.
8. **No invented verification.** Never claim tests, builds, migrations, or runtime checks passed unless they were actually executed.
9. **Protect data.** Never run destructive database commands, reset environments, delete data, or apply irreversible migrations without explicit authorization.
10. **Keep instructions actionable.** Follow the rules below; do not turn this file into an excuse to add boilerplate or unnecessary abstractions.

---

## 2. Technology baseline and version discipline

Expected stack:

- Java 25
- Spring Boot 4.x
- Spring Framework version managed by the Spring Boot BOM
- Spring Data JPA
- Hibernate ORM version managed by Spring Boot
- PostgreSQL
- Maven (unless this repository uses another build system)
- Lombok
- MapStruct
- Blaze-Persistence where already adopted by the project

### Rules

- Confirm exact versions from `pom.xml`, Maven dependency tree, and existing code. Do not assume the latest version.
- Prefer Spring Boot dependency management. Do not independently override Spring Framework, Hibernate, Spring Data, Jackson, or other managed versions without a compelling compatibility reason.
- Do not copy APIs from Spring Boot 2/3 or older Hibernate examples without checking compatibility with the project's exact versions.
- Spring Boot 4 and Java 25 may have API, namespace, configuration, and dependency compatibility differences from earlier versions. Verify before coding.
- Use `jakarta.*` APIs, not legacy `javax.*`, for Jakarta EE components (except packages that remain Java SE, such as `javax.sql`).
- Do not upgrade the JDK, Spring Boot, Hibernate, MapStruct, Lombok, PostgreSQL driver, or Blaze-Persistence as part of an unrelated feature.
- When adding a dependency, explain why an existing Spring/JDK capability or project dependency is insufficient.
- Avoid duplicate libraries that solve the same problem.
- Use Maven Wrapper (`./mvnw` / `mvnw.cmd`) when present.

---

## 3. Repository discovery workflow

Before editing:

1. Read this file and any nested `AGENTS.md`.
2. Inspect `pom.xml`, Maven profiles, compiler configuration, annotation processors, and dependency management.
3. Identify package boundaries and representative implementations of:
   - REST controllers
   - application/service layer
   - entities and repositories
   - DTOs and MapStruct mappers
   - exception handling
   - security configuration
   - database migrations
   - tests
4. Search for existing abstractions before creating new ones.
5. Determine whether the task requires schema changes, API changes, data migration, or configuration changes.
6. Inspect relevant tests and run the narrowest useful test suite.
7. For query changes, inspect generated SQL and existing indexes/migrations.

After editing:

1. Review the complete diff for accidental or unrelated changes.
2. Run formatting/checks if configured.
3. Run targeted tests, then broader tests when appropriate.
4. Run compilation/package verification if feasible.
5. Report exactly what changed, what was tested, results, and remaining risks.

---

## 4. Architecture and Package Boundaries

Follow the architecture and package conventions already established in this repository. Do not introduce new architectural layers or packages without a clear requirement and explicit justification.

### 4.1 Package structure

Use the existing root package and follow this structure where applicable:

```text
<existing root package>
├── config/                 # Framework, infrastructure, and application configuration
├── controller/              # HTTP endpoints and request handling
├── bean/                   # Request/input objects and application data holders
├── service/                 # Business logic and application orchestration
├── entity/                  # JPA entities and persistence models
├── entityview/               # Blaze-Persistence Entity Views and read-oriented query results
├── utility/                  # Focused reusable utility functionality
├── netty/                   # Network communication and protocol handling (where applicable)
└── ...                     # Other existing packages when required by the codebase
```

### 4.2 Package responsibilities

- **`controller/`**: Handle HTTP routing, request/response flow, and delegate application work to services. Keep business logic out of controllers.
- **`bean/`**: Use for request/input objects, parameters, and application data holders. This repository uses `bean/`; do not create a `dto/` package.
- **`service/`**: Implement business logic, orchestration, and coordination between application components.
- **`entity/`**: Store JPA entities and persistence models. Do not expose entities directly as API response models unless an established endpoint explicitly requires it.
- **`entityview/`**: Use for Blaze-Persistence Entity View interfaces/implementations and read-oriented projections returned by Entity View queries. Prefer Entity Views for suitable query/read models rather than loading full entities unnecessarily.
- **`config/`**: Keep framework and application configuration here, following existing configuration conventions.
- **`utility/`**: Keep utilities focused and reusable. Avoid turning this package into a home for business logic.
- **`netty/`**: Use for Netty-related networking, communication, and protocol handling when applicable.

### 4.3 Package conventions to preserve

- Do **not** introduce `dto/`, `mapper/`, `repository/`, or `domain/` packages by default. This project does not use these as separate architectural layers.
- Do not add mapping layers or abstractions solely to follow a generic architecture template. Follow the actual project patterns.
- Inspect neighboring classes before creating a new class; match their package, naming, constructor, dependency-injection, and method conventions.
- Keep class placement consistent with existing usage and responsibility. If a new package appears genuinely necessary, explain why before introducing it.

### 4.4 Validation conventions

- Use **`IlKeiValidator`** as the preferred POJO/input validation mechanism, following the exact fluent API and conventions demonstrated by existing Java code.
- Use **`IlKeiEntityValidator`** for criteria-based database/entity validation, including entity existence and business conditions such as status codes.
- Inspect and reuse existing validator usage before implementing validation. Do not invent methods or usage patterns; verify them from project code and dependencies.
- Do not introduce Jakarta Bean Validation annotations (`@Valid`, `@NotNull`, `@NotBlank`, etc.) or another validation framework unless explicitly required or already established for a specific integration.
- Return early when validation fails. Populate the existing `ApiResponse` wrapper's message (and status where appropriate) and return it immediately.

## 5. Java 25 coding standards

### General

- Use clear, descriptive names. Prefer domain terminology over abbreviations.
- Keep methods focused and reasonably small; extract only when it improves readability or reuse.
- Use `final` for fields that should not be reassigned; do not add it mechanically to every local variable if it conflicts with project style.
- Prefer immutable value objects and records for suitable data carriers.
- Use `var` only when the initializer makes the type obvious and readability is not reduced.
- Use enums for finite domain states; avoid stringly typed status fields.
- Avoid magic numbers and strings. Centralize meaningful constants, but do not create constants for self-explanatory literals used once.
- Use `Objects.requireNonNull` or validation at appropriate boundaries when null is not valid.
- Do not swallow exceptions or return `null` to represent errors.
- Do not catch `Exception` or `Throwable` without a specific, justified boundary-level reason.
- Do not log and rethrow the same exception at every layer.
- Avoid side effects in Java Stream intermediate operations.
- Prefer loops over complex streams when control flow, debugging, or performance is clearer.
- Use `Optional` primarily as a return type representing possible absence; avoid `Optional` fields, entity attributes, request parameters, and method parameters unless an established convention requires them.
- Do not use `Optional.get()` without checking presence. Prefer `orElseThrow`, `map`, `orElse`, or explicit branching.
- Use `BigDecimal` for money and exact decimal business values. Never use `double` or `float` for currency.
- Define explicit rounding modes and scale where business requirements demand them.
- Use `Instant` for timestamps representing a point in time; use `LocalDate` for dates and `LocalDateTime` only when the domain truly represents local wall-clock time without a zone.
- Use `Clock` injection for time-dependent business logic when testability matters.
- Use `UUID` or the project's established identifier type consistently; do not change identifier strategy casually.
- Prefer Java 25 language features only when supported by project compiler configuration and they improve clarity. Avoid preview features unless explicitly enabled and approved.


## 5A. Java Streams and iteration style

**Project preference:** Use Java Streams only for simple, readable transformations or straightforward collection processing. Prefer traditional `for` / enhanced `for` loops for complex logic.

### Use Streams when

- The operation is a short, straightforward transformation/filtering pipeline.
- The pipeline is easy to understand from left to right.
- Lambdas are short and free of side effects.
- The result is naturally expressed as `map`, `filter`, `flatMap`, `distinct`, `sorted`, `collect`, `toList`, `anyMatch`, `allMatch`, `noneMatch`, `findFirst`, `count`, `min`, or `max`.
- The code does not need complex exception handling, mutable state, or multiple branching decisions.
- The pipeline improves readability compared with a loop.

Examples:

```java
List<String> activeNames = users.stream()
        .filter(User::isActive)
        .map(User::getName)
        .toList();

boolean hasOverdueInvoice = invoices.stream()
        .anyMatch(Invoice::isOverdue);

Map<UUID, User> usersById = users.stream()
        .collect(Collectors.toMap(User::getId, Function.identity()));
```

### Prefer traditional loops when

- The logic contains multiple branches (`if` / `else`, `switch`) or nested conditions.
- Each element requires several steps or intermediate decisions.
- The operation has meaningful side effects (database calls, logging, mutation, external calls).
- You need `break`, `continue`, early exit, or complex control flow.
- Exception handling inside the lambda would make the code awkward.
- Mutable accumulators or state would be needed.
- The pipeline becomes long, deeply nested, or difficult to debug.
- The code is performance-sensitive and a loop is measurably clearer or more efficient.
- You need to process a sequence in a precise order with state carried between elements.
- The stream would obscure business rules or make a reviewer work harder to understand the behavior.

Example — prefer a loop for branching business logic:

```java
List<Invoice> eligibleInvoices = new ArrayList<>();

for (Invoice invoice : invoices) {
    if (invoice.isCancelled()) {
        continue;
    }

    if (invoice.isOverdue() && invoice.getOutstandingAmount().signum() > 0) {
        eligibleInvoices.add(invoice);
    }
}
```

### Stream-specific rules

- **Do not use streams for side effects.** Avoid `forEach` that mutates external collections, entities, counters, or application state.
- Do not perform repository/database calls or remote HTTP calls inside stream pipelines.
- Do not use `parallelStream()` for JPA/Hibernate work, transaction-bound operations, blocking I/O, or shared mutable state.
- Do not use `peek()` for business logic, mutation, or production debugging. It is intended primarily for diagnostics and may not execute as expected when the pipeline is optimized.
- Do not force a stream into code that is naturally imperative.
- Avoid deeply nested streams and `flatMap` chains that are difficult to reason about.
- Avoid lambdas with several statements; extract a method or use a loop.
- Do not throw checked exceptions from streams by wrapping them in opaque runtime exceptions unless there is a clear, documented reason.
- Avoid collecting into mutable structures through side effects. Use proper collectors when the stream is simple; otherwise use a loop.
- Prefer `toList()` when an unmodifiable result is acceptable and supported by the project's Java baseline. Use `Collectors.toCollection(...)` when a specific mutable collection is required.
- Be aware that `Stream.toList()` returns an unmodifiable list. Do not assume it can be modified afterward.
- Avoid unnecessary stream creation for a single lookup or simple operation.
- Do not use `Optional.stream()` or stream conversions merely to make code look functional.
- Do not use streams to hide null-handling or business validation.
- Keep stream pipelines sequential by default. Parallelism must be justified by measurement and safe semantics.

### Review rule

When reviewing or generating Java code, ask:

> Is this stream pipeline simpler to understand than a normal loop?

If the answer is **no**, use a traditional loop.

The objective is readability, maintainability, and explicit business logic—not maximizing the number of Stream API usages.

### Concurrency

- Do not assume a Spring singleton bean is thread-safe merely because it is managed by Spring.
- Avoid mutable shared state in singleton services.
- Use concurrent collections/atomic types only when their semantics are appropriate; they do not replace correct transaction design.
- Do not start unmanaged threads from services or controllers. Use configured task executors or established asynchronous infrastructure.
- Do not use `parallelStream()` for repository access, transaction-bound operations, or blocking I/O.
- Understand transaction context propagation before using asynchronous execution.
- Use optimistic/pessimistic locking only for a defined consistency requirement.

---

## 6. Spring Boot conventions

### Dependency injection

- Prefer constructor injection.
- Use Lombok `@RequiredArgsConstructor` only when it is already accepted by the project and does not obscure required initialization.
- Do not use field injection (`@Autowired` on fields).
- Avoid injecting the application context or using service locators to hide dependencies.
- Do not annotate every class with `@Component` indiscriminately; choose the stereotype that communicates its role (`@Service`, `@Repository`, `@Controller`, `@RestController`, etc.).

### Configuration

- Use typed `@ConfigurationProperties` for grouped application configuration.
- Validate configuration at startup where appropriate (`@Validated`, Jakarta validation constraints).
- Do not scatter `@Value` expressions across many classes for the same configuration group.
- Do not hardcode credentials, API keys, passwords, private keys, or production URLs.
- Use environment variables, secret management, or deployment configuration for secrets.
- Do not add configuration to a ConfigMap or environment file without understanding the project's deployment conventions and whether the value is secret.
- Keep profiles purposeful (`local`, `test`, `staging`, `prod`); avoid profile-specific business logic.
- Do not disable production security, CSRF protections, TLS validation, or authentication to make a test pass.

### Service validation and early-return pattern

Follow the existing service method style:

```java
var wrap = new ApiResponse<ResponseBean>();
wrap.setStatus(false);

var pr = IlKeiValidator.builder().withPojo(src)
        .pick(RequestBean.Fields.idExample, "Example").isMandatory().asLong().pack()
        .validate();

if (!pr.getResult()) {
    wrap.setStatus(false);
    wrap.setMessage(pr.getMessage());
    return wrap;
}
```

For database-backed validation, use the established `IlKeiEntityValidator` + Blaze-Persistence criteria-builder pattern:

```java
var ppr = IlKeiEntityValidator.builder()
        .withCriteriaBuilderFactory(em, configBuilder)
        .pick().withEntity(ExampleEntity.class, "e")
        .whereCriteriaEqual("e.id", src.getIdExample())
        .whereCriteriaEqual("e.status.code", "AKT")
        .withErrorMessage("Example tidak terdapat di database")
        .pack()
        .validate();

if (!ppr.getResult()) {
    wrap.setMessage(ppr.getMessage());
    return wrap;
}
```

- Use `var` for local variables when the initializer makes the type clear, matching existing code.
- Chain `.pick(...)`, validation constraints/conversions, and `.pack()`; call `.validate()` at the end.
- Use `.withErrorMessage(...)` to provide the intended user-facing entity-validation message.
- Prefer entity validation over a separate manual query when the query is only checking existence/criteria. If the entity is subsequently needed for business processing, fetch it using the project's established query/EntityManager pattern.
- Do not copy illustrative entity/field names literally. Adapt them to actual classes and verify the validator API against repository code.

### REST API

- Use the project's `bean/` classes for request/input objects and response data; do not introduce a `dto/` package.
- Use the project's standard generic **`ApiResponse<T>`** wrapper for service/API responses. Do not create alternate response-wrapper classes unless explicitly requested or required by an established integration.
- Preserve the response contract and field semantics: `status`, `message`, `maxPage`, `perPage`, and generic `data`.
- Use `ApiResponse<T>` methods consistently: `insertNewData(...)` for a single payload and `addData(...)` when appending payload items, following the existing class implementation.
- For Blaze-Persistence paginated/read queries, return **Entity View** results from `entityview/` as the `ApiResponse<T>` data rather than exposing JPA entities.
- Initialize response wrappers using the established pattern, typically `var wrap = new ApiResponse<...>();` followed by `wrap.setStatus(false);`. Set success status only after successful processing.
- For expected validation or business-rule failures, set `wrap.setMessage(...)` (and `wrap.setStatus(false)` where appropriate) and **return early**. Do not throw exceptions for routine validation branches.
- Define consistent HTTP semantics: `200`, `201`, `202`, `204`, `400`, `401`, `403`, `404`, `409`, `422` (if project convention supports it), and `5xx`.
- Use `ResponseEntity` when status/headers need explicit control; otherwise prefer a simple return type.
- Do not return JPA entities, Hibernate proxies, `Page<Entity>`, or entity graphs directly from controllers.
- Do not expose internal stack traces, SQL, entity internals, secrets, or sensitive values in API errors.
- Use a consistent pagination/sorting contract. Validate page size and sort properties; never concatenate untrusted sort expressions into SQL.
- Treat API changes as compatibility changes. Do not rename/remove response fields or alter semantics without authorization.
- Use idempotency and concurrency controls for APIs that create financial, workflow, or other duplicate-sensitive operations where required by the domain.

### Exception handling

- Prefer centralized exception handling (`@RestControllerAdvice`) consistent with the existing project.
- Map expected domain/application exceptions to explicit HTTP responses.
- Use stable error codes and safe messages if the API has an error contract.
- Do not expose `exception.getMessage()` blindly to clients.
- Do not convert every exception into `200 OK`.
- Do not use exceptions as routine control flow for expected branching.
- Preserve causes when wrapping exceptions.
- Log unexpected errors once at the appropriate boundary with correlation/request context, without leaking sensitive data.

### Observability

- Use structured logging where configured.
- Include useful contextual identifiers (request/correlation ID, business entity ID) without exposing secrets or sensitive personal data.
- Do not log access tokens, passwords, database credentials, session IDs, sensitive request bodies, or health/medical/financial personal data.
- Avoid excessive logging inside loops and hot paths.
- Prefer metrics/tracing for recurring operational questions instead of unbounded verbose logs.
- Do not add a new logging framework or observability library without approval.

---

## 7. Spring Data JPA and Hibernate: entity modeling

### Entity fundamentals

- Use `@Entity` and an explicit, stable table mapping where naming conventions require it.
- Keep entities focused on persistence state and persistence behavior.
- Use a no-argument constructor with the visibility required by JPA.
- Do not make entities Java records.
- Do not make entity classes or persistent methods `final` if that interferes with Hibernate proxying/lazy loading, unless bytecode enhancement and project conventions explicitly support it.
- Avoid public setters for every field. Prefer controlled state changes and constructors/factory methods where compatible with JPA.
- Do not create entities with incomplete/invalid state and populate them through a long sequence of setters.
- Use `@Version` for optimistic locking where concurrent updates must be detected.
- Define unique constraints and indexes in migrations, not solely through annotations.
- Ensure Java field types, database types, nullability, precision, and scale agree.

### Lombok and entities

- **Never use `@Data` on JPA entities.** It generates `equals`, `hashCode`, and `toString` that may traverse lazy relationships, cause recursion, trigger database queries, or violate entity identity semantics.
- Avoid `@EqualsAndHashCode` over all entity fields.
- Avoid `@ToString` over relationships and large/secret fields.
- If Lombok equality is needed, explicitly restrict included fields and carefully define entity identity semantics. Do not blindly base equality on a generated ID that is `null` before persistence.
- Prefer explicit methods for business state transitions.
- `@Builder` on entities can create invalid instances and interact poorly with JPA constructors. Use only with controlled construction and required-field validation.
- Do not use `@Builder` as a substitute for entity lifecycle design.
- If using `@Getter`, ensure it does not expose sensitive fields unnecessarily at API boundaries; entities must not be serialized directly.

### IDs and entity equality

- Follow the project's existing ID strategy.
- Do not assume generated IDs exist before persistence.
- Avoid changing equality/hash code after an entity enters a `HashSet`/`HashMap`.
- Do not use mutable business fields in entity hash codes.
- If entities are used in sets, carefully evaluate proxy compatibility, inheritance, transient state, and ID generation.
- Prefer comparing identifiers in business logic when identity comparison is needed, while accounting for transient entities.

### Relationships

- Explicitly set fetch strategy. Do not rely on defaults:
  - `@ManyToOne(fetch = FetchType.LAZY)`
  - `@OneToOne(fetch = FetchType.LAZY)` where supported and correctly configured
  - `@OneToMany(fetch = FetchType.LAZY)`
  - `@ManyToMany(fetch = FetchType.LAZY)`
- Do not use `FetchType.EAGER` as a quick fix for `LazyInitializationException`.
- Avoid bidirectional relationships unless both directions are genuinely needed.
- If bidirectional relationships exist, maintain both sides through helper methods (`addChild`, `removeChild`, etc.).
- Avoid cascading `REMOVE` from a parent to shared/reference entities.
- Use `CascadeType.ALL` only when lifecycle ownership is clear; do not apply it mechanically.
- `orphanRemoval = true` is appropriate only when a child is privately owned and removal from the aggregate truly means deletion.
- Avoid `@ManyToMany` for relationships with their own attributes, lifecycle, audit, or business rules; model the join table as an entity.
- Do not fetch huge collections into memory merely to count, filter, or update them.
- Avoid exposing collection mutation that bypasses aggregate invariants.
- For optional relationships, define and enforce nullability consistently.

### Inheritance and mapping

- Avoid entity inheritance for convenience. Use it only when the domain and relational model genuinely support it.
- Understand Hibernate inheritance strategies and query costs before using `SINGLE_TABLE`, `JOINED`, or `TABLE_PER_CLASS`.
- Use `@MappedSuperclass` for shared mapped fields only when appropriate; do not create deep inheritance hierarchies.
- Avoid embedding persistence-specific behavior in unrelated domain value objects.
- Use `@Embeddable` for cohesive value groups with a clear lifecycle and mapping.

### Enum and temporal persistence

- Persist enums as strings (`@Enumerated(EnumType.STRING)`) unless a stable numeric/code mapping is explicitly required.
- Never rely on enum ordinal persistence for evolving business enums.
- For timestamps, choose database and Java types deliberately; use timezone-aware semantics for instants.
- PostgreSQL `timestamp with time zone` (`timestamptz`) stores an instant normalized internally; it does not preserve the original zone ID. Store the zone separately if the business needs it.
- Avoid implicit date/time conversion and JVM-default timezone dependence.
- Test temporal boundaries, DST transitions when applicable, and precision differences.

### PostgreSQL-specific types

- Map UUID, JSON/JSONB, arrays, enums, and custom types explicitly and consistently with the project's Hibernate version.
- Do not add Hibernate custom type annotations from older versions without checking current Hibernate 6/7 APIs.
- Use `jsonb` only when flexible document semantics are justified; do not use it to avoid relational modeling of frequently queried data.
- Prefer normalized relational columns for fields used in joins, filtering, constraints, or reporting.
- Avoid storing large binary objects in ordinary entity fields unless explicitly designed for it; consider object storage and metadata.
- Validate numeric precision and scale for PostgreSQL `numeric` mappings.

---

## 8. Query optimization: mandatory practices

### First principles

1. Query performance is a database, data-model, ORM, and application concern—not just a Java concern.
2. Understand cardinality, selectivity, data distribution, row width, and access patterns.
3. Avoid loading columns or rows the caller does not need.
4. Optimize based on representative data and execution plans.
5. Be aware of the difference between query count, total rows transferred, database work, memory usage, and end-to-end latency.
6. Do not solve performance problems by indiscriminately adding indexes, caching, eager fetching, or larger connection pools.

### N+1 prevention

- Identify all queries executed while iterating over returned entities.
- Do not access lazy relationships in loops without understanding query behavior.
- For read use cases, prefer purpose-built projections/DTO queries when only a subset of fields is needed.
- Use `JOIN FETCH` for suitable to-one relationships and carefully chosen to-many relationships when it does not break pagination or result cardinality.
- Consider `@EntityGraph` for reusable fetch plans, but inspect the generated SQL; entity graphs do not guarantee an optimal query.
- Consider batch fetching (`@BatchSize` or Hibernate batch-fetch configuration) when loading multiple lazy associations is appropriate.
- Batch fetching reduces round trips but may still transfer more data than a projection.
- Do not add a fetch join simply to eliminate a warning without checking row multiplication.
- Add tests or query-count instrumentation for known N+1-sensitive use cases when the project supports it.

### Projections and DTO queries

- Prefer projections for read-only use cases that do not need entity lifecycle or dirty checking.
- Use constructor projections, interface projections, JPQL DTO projections, Criteria, Blaze-Persistence Entity Views, or the project's established query mechanism.
- Select only required columns.
- Avoid loading a full entity graph for list screens, dashboards, reports, search results, and exports.
- Do not map a large entity into a DTO after loading every field if the database can return the required projection directly.
- Avoid DTO projections that trigger lazy relationship access in mapper code.
- For complex queries, inspect SQL and execution plan rather than assuming the ORM generated efficient SQL.

### Join and cardinality rules

- Before joining collections, estimate the row multiplication. Joining two independent collections can multiply result rows dramatically.
- Avoid multiple collection fetch joins in the same query unless cardinality is bounded and behavior is verified.
- Use separate queries, batch fetching, aggregation, or projection-based approaches when appropriate.
- Use `DISTINCT` only when it is semantically correct and understand whether it applies in SQL, ORM result processing, or both.
- Do not add `DISTINCT` reflexively to hide duplicate rows caused by joins.
- Avoid Cartesian products caused by missing join predicates.
- For aggregate queries, group only by required keys and inspect PostgreSQL's plan.

### Pagination

- For ordinary pagination, use deterministic ordering with a unique tie-breaker. `ORDER BY createdAt DESC` alone is not stable when timestamps collide; add a unique ID.
- Avoid deep offset pagination for large tables when latency is unacceptable. PostgreSQL must still walk/skip preceding rows.
- Prefer keyset/seek pagination for sequential navigation over large datasets:
  - Use a stable, indexed ordering tuple.
  - Include a unique tie-breaker.
  - Build the cursor from the last row returned.
  - Use matching predicates and order (`>` / `<` as appropriate).
  - Define behavior when sort fields are nullable.
  - Validate cursor integrity and avoid exposing sensitive data.
- Offset pagination may remain appropriate when users need arbitrary page-number navigation and the dataset is modest; choose deliberately.
- Do not paginate a query that fetch-joins a collection. Page IDs or root rows first, then fetch required data in a second query, or use Blaze-Persistence's supported pagination/entity-view strategy.
- Beware of Hibernate's in-memory pagination warning with collection fetches. Treat it as a correctness/performance defect, not a harmless warning.
- Count queries must be designed separately when joins, grouping, distinct, or projections make the automatic count query expensive or incorrect.
- Avoid exact total counts for huge result sets when the product requirement can use `hasNext`, approximate counts, or deferred counts.
- Verify page boundaries under concurrent inserts/deletes; offset pagination can skip or duplicate rows as the dataset changes.

### Bulk operations

- Use bulk JPQL/Criteria/native SQL for large updates/deletes when appropriate.
- Bulk DML bypasses entity lifecycle callbacks, normal dirty checking, and may leave the persistence context stale.
- After bulk DML, clear/refresh the persistence context when necessary and understand second-level cache implications.
- Do not load millions of entities and update them one by one without a clear reason.
- For batch inserts/updates:
  - Configure Hibernate JDBC batching only after validating driver/database behavior.
  - Avoid `IDENTITY` generation if it prevents batching and the workload requires high insert throughput; evaluate PostgreSQL sequences or another strategy.
  - Flush and clear in bounded chunks to control persistence-context memory.
  - Do not flush after every row.
  - Preserve transaction and failure semantics.
- For massive ETL, consider JDBC, `COPY`, staging tables, or dedicated batch infrastructure rather than entity-by-entity ORM processing.

### SQL inspection and plan validation

For meaningful query changes:

- Capture generated SQL (and bind values in safe non-production environments).
- Inspect query count and rows returned.
- Use PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` on representative data when safe.
- Examine:
  - sequential vs index scans
  - join strategy (nested loop, hash join, merge join)
  - estimated vs actual row counts
  - filter selectivity
  - sort method and memory/disk spill
  - shared buffer hits/reads
  - temporary I/O
  - loops and repeated execution
- Do not run `EXPLAIN ANALYZE` on expensive or mutating statements in production without an approved safe procedure; `ANALYZE` executes the statement.
- Do not blindly force join types, disable planner features, or globally increase `work_mem`.
- Remember `work_mem` can be consumed per sort/hash operation and per parallel worker; setting it high globally can exhaust memory.
- Compare plans before and after and record data volume/parameters.
- Avoid logging SQL parameters that contain PII or secrets.

---

## 9. PostgreSQL schema and indexing rules

### Migrations

- Use the project's migration tool (Flyway, Liquibase, or established alternative).
- Never modify a migration that has already been applied in shared/staging/production environments unless the project explicitly uses a controlled baseline/rebuild process.
- Create a new forward migration for schema changes.
- Migrations must be deterministic, reviewable, and safe for deployment.
- Consider deployment order and backward compatibility:
  1. add nullable/new structures
  2. deploy compatible application
  3. backfill safely
  4. add constraints/indexes after validation
  5. remove obsolete structures in a later release
- Avoid long blocking DDL on large production tables. Evaluate lock behavior.
- For large PostgreSQL indexes, consider `CREATE INDEX CONCURRENTLY` when operationally appropriate; note it cannot run inside a transaction block and must be supported by the migration configuration.
- Do not add destructive data migrations without explicit authorization, backup/recovery plan, and validation.

### Indexes

- Add indexes based on actual query patterns, not every foreign key or every column automatically.
- Consider composite index column order based on equality predicates, range predicates, sorting, and selectivity.
- Match indexes to common filters and ordering; an index may not help if the query expression or collation differs.
- Evaluate partial indexes for stable, selective predicates when appropriate.
- Evaluate expression indexes only when query expressions are consistent.
- Use `INCLUDE` columns for covering indexes only after validating write/storage costs.
- Avoid redundant/overlapping indexes; every index adds storage and write amplification.
- Index foreign keys where joins/deletes/updates need them; PostgreSQL does not automatically index the referencing side of a foreign key.
- Consider GIN for `jsonb`, arrays, or full-text search only when access patterns justify it.
- Consider BRIN for very large tables with physical correlation (e.g., time-series append patterns); do not use it as a default replacement for B-tree.
- Do not index low-selectivity booleans alone unless a partial/composite design has evidence.
- Verify with `EXPLAIN (ANALYZE, BUFFERS)` and realistic data.
- Include index changes in migrations and explain expected query benefit.

### Constraints and data integrity

- Prefer database constraints for invariants that must hold regardless of application code: `NOT NULL`, `UNIQUE`, `CHECK`, and foreign keys.
- Keep application validation for clear user feedback; it does not replace database constraints.
- Avoid relying on “check then insert” for uniqueness under concurrency. Use a unique constraint and handle conflicts.
- Understand PostgreSQL transaction isolation and locking before implementing concurrency-sensitive workflows.
- Do not use `ON CONFLICT` without defining correct conflict semantics and update behavior.
- Ensure soft-delete, tenant, and audit filters are consistently applied if the project uses them.

### Connection and server configuration

- Do not change HikariCP pool size based solely on a slow query.
- Consider total connection budget across all app replicas, PostgreSQL max connections, pool wait time, transaction duration, and workload concurrency.
- Keep transactions short; do not hold DB connections while making remote HTTP calls or performing long computations.
- Tune PostgreSQL settings only with workload evidence and operational approval.
- Do not assume `work_mem`, shared buffers, autovacuum, or max connections should be increased globally.
- Monitor slow queries, lock waits, deadlocks, pool saturation, and vacuum behavior when performance work is operational.

---

## 10. Blaze-Persistence (when included in the project)

### Version and integration

- Confirm the exact Blaze-Persistence version and compatibility with the project's Hibernate and Spring Boot versions.
- Use the project's configured integration and dependencies; do not mix incompatible Blaze/Hibernate versions.
- Follow existing conventions for `CriteriaBuilderFactory`, `EntityViewManager`, Spring integration, and repository/query services.
- Do not introduce Blaze-Persistence into a module that uses simple Spring Data JPA queries unless its capabilities materially improve the use case.

### Criteria queries

- Use Blaze-Persistence for complex dynamic filtering, joins, projections, CTEs, window functions, keyset pagination, and Entity Views when these solve a concrete requirement.
- Keep query construction readable. Extract reusable predicates only when they are genuinely shared or improve comprehension.
- Build predicates from typed paths/parameters; never concatenate untrusted user input into JPQL/SQL.
- Whitelist sort fields and directions. Do not accept arbitrary client-supplied property paths.
- Separate query concerns (filters, sorting, pagination, projection) when doing so clarifies the contract.
- Avoid generating one giant query for every use case. A simpler dedicated query can be better.
- Check generated SQL and database execution plans for complex Blaze queries.

### Entity Views

- Use Entity Views as read models/projections when the caller needs a controlled subset of entity data.
- Prefer DTO-like Entity Views for API read paths; do not serialize managed entities directly.
- Define fetches and nested views deliberately. A nested collection can still cause row multiplication or expensive materialization.
- Do not expose internal Entity Views as public API contracts if versioning/compatibility requires a stable DTO boundary.
- Avoid overly deep nested Entity Views and recursive fetches.
- Use updatable Entity Views only when their update semantics, flush behavior, and lifecycle are clearly understood.
- Validate view attributes, mappings, and collection behavior against the actual Blaze version.

### Keyset pagination

- Prefer Blaze-Persistence keyset pagination for deep, sequential traversal of large datasets when the sort is deterministic and supported.
- Define a unique ordering. For example, `(createdAt DESC, id DESC)`; the cursor must include all ordering components.
- Ensure appropriate database indexes match filters and ordering.
- Understand that keyset pagination is naturally suited to next/previous navigation; it does not inherently provide cheap arbitrary jumps to page 20,000.
- Do not pretend keyset pagination can efficiently jump to an arbitrary page number without a known cursor/anchor or additional strategy.
- If the UI requires arbitrary page numbers, evaluate:
  - offset pagination for acceptable depths
  - cached/maintained page anchors
  - a hybrid offset/keyset strategy
  - redesigned navigation (next/previous, search, filters)
- Define behavior for inserts/deletes/updates between requests. Keyset pagination reduces shifting caused by inserts before the cursor but does not create a full snapshot across requests.
- Cursor tokens should be validated, versioned if the format can change, and opaque to clients when appropriate.
- Do not use nullable sort keys without defining null ordering and cursor predicate semantics.
- Verify first page, subsequent page, previous page, ties, deleted cursor row, changed sort value, and concurrent inserts.
- For keyset count metadata, do not compute an expensive total count unless the API contract requires it.
- Use Blaze's built-in keyset mechanisms rather than hand-rolling cursor predicates unless there is a demonstrated limitation.

### CTEs, window functions, and advanced SQL

- Use CTEs/window functions when they improve clarity or are necessary for the query semantics.
- Understand PostgreSQL CTE materialization/inlining behavior for the deployed version.
- Avoid unnecessary CTE layers and repeated scans.
- Validate window-function partitions, ordering, frame semantics, and null handling.
- For recursive CTEs, ensure termination and protect against cycles.
- For native SQL, document why JPQL/Criteria/Blaze cannot express the requirement adequately and map results safely.

---

## 11. MapStruct

### Mapping principles

- Use MapStruct for deterministic structural mapping—not business logic, authorization, persistence, or network calls.
- Keep mappings explicit for public API DTOs and complex objects.
- Prefer compile-time generated mapping over reflection-based mapping.
- Follow the project's mapper configuration (`componentModel`, injection strategy, null strategies, reporting policies).
- If Spring is used, use the configured Spring component model and constructor injection convention.
- Reuse central mapping configuration only when the shared rules are truly consistent.
- Use `uses` for other mappers; avoid circular mapper dependencies.
- Do not call repositories or services from mapper methods.
- Do not put `@Transactional` on mappers.
- Do not use `expression = "java(...)"` for complex logic. Extract logic into a service or a small, pure, testable helper if it is genuinely mapping-related.
- Avoid mapping a DTO to an entity and then blindly calling `repository.save()` for updates; update semantics must be explicit.

### Entity-to-DTO

- Never return entities directly from REST controllers.
- Explicitly control nested mapping depth.
- Ignore relationships not needed in the response.
- Avoid accidental traversal of lazy collections.
- Use projections/Entity Views when mapper-based entity loading would be too expensive.
- Do not map bidirectional relationships recursively.
- Map IDs/reference summaries rather than full nested entities where appropriate.

### DTO-to-entity and updates

- For create operations, use explicit mapping and validate required fields.
- For update operations, use `@MappingTarget` only when update semantics are well-defined.
- Explicitly define null behavior:
  - Does `null` mean “clear this field”?
  - Does absent/null mean “leave unchanged”?
  - Is the request a full replacement or partial update?
- Do not use `NullValuePropertyMappingStrategy.IGNORE` globally without verifying that it matches API semantics.
- Avoid updating IDs, version fields, audit fields, tenant IDs, ownership, or immutable fields from request DTOs.
- Ignore server-controlled fields explicitly.
- Do not let MapStruct update relationship collections without carefully defined add/remove semantics.
- Prefer service-level lookup and authorization for relationship references, then assign the validated relationship.
- Do not create stub entities from arbitrary IDs unless the project explicitly uses that pattern and validates referential and authorization rules.

### Compile-time safety

- Use `unmappedTargetPolicy = ReportingPolicy.ERROR` (or the repository's equivalent strict policy) for critical mappings where feasible.
- Use explicit `ignore = true` for intentionally unmapped fields, with rationale when non-obvious.
- Do not suppress mapping warnings broadly.
- Keep generated code out of source control unless project conventions require it.
- If annotation processing fails, inspect Maven compiler and processor configuration; do not switch to manual mapping just to bypass the build.

### Mapping tests

- Test mapping behavior for:
  - nested DTOs
  - null values
  - collection values
  - enum conversions
  - update semantics
  - ignored/server-controlled fields
  - precision-sensitive values
- Unit-test custom conversion methods independently.
- Do not test only that MapStruct can compile; test contract behavior where it is nontrivial.

---

## 12. Lombok

### General rules

- Use Lombok only where it reduces boilerplate without hiding important semantics.
- Check annotation processing and IDE/Maven configuration before assuming Lombok-generated members exist.
- Do not introduce Lombok to a module that deliberately avoids it.
- Avoid stacking annotations that generate conflicting constructors or methods.

### Recommended use

- `@Getter` for suitable classes.
- `@Setter` for simple mutable DTO/configuration classes where appropriate, not automatically for entities or domain objects.
- `@RequiredArgsConstructor` for constructor injection where established.
- `@Slf4j` if the project uses Lombok logging.
- `@Value` for immutable value classes when appropriate; consider Java records first.
- `@Builder` for immutable DTOs or complex construction where it improves usability and all invariants are enforced.

### Avoid or scrutinize

- `@Data` on entities and domain objects.
- `@EqualsAndHashCode` on entities without explicit identity semantics.
- `@ToString` on entities with relationships or sensitive values.
- `@AllArgsConstructor` on entities, which can bypass invariants and JPA requirements.
- `@Builder` on entities without a safe construction strategy.
- `@SneakyThrows` in application logic; it obscures checked exception behavior.
- `@Cleanup` when ordinary try-with-resources is clearer.
- `@NonNull` as a replacement for boundary validation or domain-specific error handling.
- `@Accessors(chain = true)` or fluent accessors if they conflict with bean/JPA/serialization conventions.
- `@UtilityClass` for classes that should be injectable or have dependencies.

### Explicitness

- Important business rules, constructors, state transitions, validation, equality, and persistence lifecycle should remain visible and reviewable.
- Do not use Lombok to conceal substantial logic.
- Verify generated constructors when combining `@Builder`, `@NoArgsConstructor`, `@RequiredArgsConstructor`, and inheritance.

---

## 13. Transactions and consistency

- Define transaction boundaries at the service/application use-case layer unless the repository architecture explicitly differs.
- Use `@Transactional` for a coherent unit of work, not every method by default.
- Use `@Transactional(readOnly = true)` for read-only use cases where appropriate; understand that it is a hint/optimization, not a security or write-prevention guarantee.
- Do not call a transactional method through self-invocation and assume proxy-based transaction advice applies.
- Understand propagation and isolation before using `REQUIRES_NEW`, `NESTED`, or custom isolation levels.
- Do not hold a transaction open across remote HTTP calls, user interaction, large file processing, or long computations.
- Handle checked exceptions and rollback rules deliberately. Spring's rollback behavior depends on exception type and configuration.
- Do not assume `readOnly = true` prevents modifications.
- Avoid modifying detached entities without a clear merge/update strategy.
- Avoid using `save()` as a universal update operation. For managed entities, dirty checking may be sufficient; for detached updates, define semantics explicitly.
- Use pessimistic locks only when justified by contention and correctness requirements.
- Use optimistic locking for conflict detection where appropriate; handle `OptimisticLockException` as a meaningful business conflict.
- For concurrent counters, inventory, balance, or quota changes, prefer atomic SQL updates, locking, or other domain-appropriate concurrency controls rather than read-modify-write without protection.
- For multi-step workflows, consider outbox/inbox, idempotency, and compensating actions; a database transaction does not make remote systems transactional.

---

## 14. Testing strategy

Follow the project's existing test conventions. Use the narrowest appropriate test type.

### Unit tests

- Use JUnit 5 and Mockito where established.
- Unit-test business logic without starting the full Spring context when possible.
- Mock external dependencies, not every internal class mechanically.
- Prefer testing observable behavior over private implementation details.
- Avoid brittle tests that assert exact internal call sequences unless order is part of the contract.
- Use deterministic fixtures and injected `Clock` where time matters.
- Do not rely on production databases, network access, or real external services in unit tests.

### Spring tests

- Use focused Spring test slices where appropriate (`@WebMvcTest`, `@DataJpaTest`, or relevant test facilities).
- Use `@SpringBootTest` only when the test requires full application wiring.
- Avoid full-context tests for ordinary service unit tests.
- Test validation, security, serialization, and exception mappings at the appropriate boundary.
- Ensure test profiles do not accidentally connect to production resources.

### JPA/PostgreSQL integration tests

- Prefer Testcontainers PostgreSQL for tests that depend on PostgreSQL-specific behavior.
- H2 is not a substitute for PostgreSQL when validating:
  - JSONB, arrays, enums, native SQL, collations
  - locking and isolation
  - query plans or index behavior
  - PostgreSQL-specific functions/operators
  - migrations and constraint behavior
- Test migrations from a clean database and, where applicable, upgrade paths.
- Verify lazy loading, transaction boundaries, optimistic locking, and uniqueness races when relevant.
- Avoid tests that pass only because the persistence context remains open or OSIV is enabled.
- Ensure test cleanup is safe and isolated.

### Performance tests

- Use realistic data volume, distributions, indexes, and query parameters.
- Measure latency, throughput, memory, query count, and rows read/transferred as relevant.
- Avoid microbenchmarks that do not represent actual workload.
- Document environment and data assumptions.
- Do not run load tests against production without explicit approval.

### Required verification reporting

When finishing a task, report:

- Tests run (exact commands)
- Pass/fail status
- Build/compile result
- Any tests not run and why
- Migration/schema implications
- Performance/security caveats

---

## 15. Security and data handling

- Validate all untrusted input at boundaries.
- Enforce authorization in the application/service layer, not only in the UI.
- Do not rely on entity IDs being unguessable for authorization.
- Avoid mass assignment: request DTOs must not bind protected fields.
- Use parameterized JPQL, Criteria, JDBC, and native SQL.
- Never concatenate user input into SQL/JPQL.
- Whitelist dynamic sort columns and filter operators.
- Protect against tenant/data-scope leakage if multi-tenancy exists.
- Do not expose sensitive fields through DTOs, logs, exception messages, or `toString()`.
- Use least privilege for database roles.
- Do not disable TLS verification or security filters as a workaround.
- Do not add secrets to tests, fixtures, migrations, source code, or generated files.
- Consider PII retention, auditability, and data minimization in new features.

---

## 16. Caching

- Do not add caching before identifying the bottleneck and defining correctness/freshness requirements.
- Understand cache key completeness, invalidation, tenant isolation, and stale-data risk.
- Do not cache mutable JPA entities or Hibernate proxies as general application cache values.
- Prefer immutable DTO/value representations for cache entries.
- Avoid caching authorization-sensitive responses without correct user/tenant context in the key.
- Do not assume Hibernate second-level cache is enabled or appropriate.
- Understand first-level persistence context identity and its lifecycle.
- Avoid cache stampedes for expensive computations; use the project's supported approach.
- Define TTL and invalidation behavior explicitly.
- Do not use cache as the source of truth for business-critical state.

---

## 17. API and database performance anti-patterns

Do not introduce these without a documented, evidence-based reason:

- `FetchType.EAGER` to fix lazy loading.
- Returning entities from REST controllers.
- Loading all rows and filtering/paging in Java.
- `findAll()` for large tables without a bounded use case.
- Collection fetch joins combined with pageable queries.
- Deep offset pagination for high-volume tables without measuring.
- One repository query per item inside a loop.
- Multiple independent collection joins that multiply result rows.
- Automatic full entity mapping for dashboard/list projections.
- Unbounded `IN` lists with thousands of parameters without considering batching/temp tables.
- `LIKE '%term%'` on large tables without evaluating trigram/full-text indexing.
- `lower(column)` filters without considering expression indexes/collation.
- Exact `COUNT(*)` for every page when it is not required.
- Blind use of `DISTINCT`.
- Blind use of native SQL when JPQL/Blaze can express the query clearly.
- Blind use of JPQL/ORM when bulk SQL or PostgreSQL features are demonstrably better.
- Increasing connection pool size to hide slow queries.
- Increasing `work_mem` globally to fix one sort.
- Caching as a substitute for a missing index or poor query.
- Logging every SQL statement with sensitive bind values in production.
- Repeated flushes/clears per row in batch processing.
- Unbounded entity collections in API responses.

---

## 18. Build and code quality

- Use the repository's configured Maven goals and profiles.
- Check compiler warnings and annotation processor errors.
- Do not change formatter/checkstyle/spotless rules without approval.
- Do not commit generated build output (`target/`) unless project conventions explicitly require it.
- Keep dependency scopes correct (`test`, `runtime`, `provided`, etc.).
- Avoid adding a dependency for a trivial helper that can be implemented clearly with the JDK.
- Do not suppress warnings broadly. If suppression is necessary, keep it narrow and explain why.
- Do not change compiler release/source/target levels without authorization.
- Ensure MapStruct and Lombok annotation processors are configured correctly and compatible.

Typical commands (adjust to the repository's actual scripts/profiles):

```bash
# Compile and run tests
./mvnw clean verify

# Run tests
./mvnw test

# Run a specific test class
./mvnw -Dtest=MyServiceTest test

# Run a specific test method (if supported by the configured test plugin)
./mvnw -Dtest=MyServiceTest#shouldHandleCase test

# Inspect dependency tree
./mvnw dependency:tree

# Package without running tests only when justified
./mvnw -DskipTests package
```

On Windows:

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd test
.\mvnw.cmd -Dtest=MyServiceTest test
```

**Never skip tests silently.** If tests are skipped, state the reason and the verification gap.

---

## 19. Database change checklist

Before adding/changing a table, column, index, or constraint:

- [ ] Is the change required by the business/domain model?
- [ ] Is the migration forward-only and safe for existing data?
- [ ] Is nullability correct for existing and future rows?
- [ ] Are defaults and backfill semantics correct?
- [ ] Are unique/FK/check constraints required?
- [ ] Will the DDL lock a large table?
- [ ] Is a concurrent index appropriate?
- [ ] Is rollback/recovery understood?
- [ ] Are old and new application versions compatible during deployment?
- [ ] Are entity mappings consistent with the database type?
- [ ] Are tests covering migration and constraint behavior?
- [ ] Is the query/index benefit validated against realistic access patterns?

---

## 20. Query change checklist

Before finalizing a significant query:

- [ ] What is the expected result cardinality?
- [ ] Are filters applied in SQL rather than in memory?
- [ ] Are only required columns selected?
- [ ] Are joins necessary and correctly constrained?
- [ ] Could joins multiply rows?
- [ ] Is there an N+1 risk?
- [ ] Is fetch strategy explicit?
- [ ] Is pagination deterministic?
- [ ] Is the count query correct and affordable?
- [ ] Is keyset pagination appropriate for deep navigation?
- [ ] Do indexes align with filter/order patterns?
- [ ] Have generated SQL and bind parameters been inspected safely?
- [ ] Has `EXPLAIN (ANALYZE, BUFFERS)` been considered on representative data?
- [ ] Are concurrent modifications and consistency semantics understood?
- [ ] Are query tests covering empty, boundary, duplicate-sort, and null cases?

---

## 21. Entity and DTO change checklist

- [ ] Are entity invariants preserved?
- [ ] Are relationships lazy and deliberately designed?
- [ ] Are cascades and orphan removal justified by ownership?
- [ ] Is entity equality safe before and after persistence?
- [ ] Is `@Version` required?
- [ ] Are database constraints and migration included?
- [ ] Is the API DTO separate from the entity?
- [ ] Does MapStruct explicitly map or ignore every relevant field?
- [ ] Are server-controlled fields protected?
- [ ] Are null/update semantics defined?
- [ ] Could mapping trigger lazy loads?
- [ ] Are serialization cycles impossible?
- [ ] Are tests covering mapping and persistence behavior?

---

## 22. Agent behavior and response format

When implementing a task:

1. Briefly state the approach for nontrivial changes.
2. Inspect relevant code before editing.
3. Make changes in the smallest coherent scope.
4. Run targeted verification.
5. Review the diff and identify side effects.
6. Report the result concisely but precisely.

Final response should include:

### Summary
- What was changed and why.

### Implementation details
- Important design decisions and trade-offs.
- Any new dependencies or abstractions.

### Verification
- Exact commands run and outcomes.
- Explicitly state if verification was not performed.

### Database/API impact
- Migrations, endpoint/DTO changes, compatibility implications.

### Risks or follow-up
- Known limitations, operational considerations, or work still required.

Do not state “production-ready,” “fully optimized,” “secure,” or “tested” without appropriate evidence.

---

## 23. Instruction precedence and nested AGENTS.md

- Instructions in the current user request take precedence over this file, provided they do not conflict with higher-priority safety/system constraints.
- More specific nested `AGENTS.md` files apply to their directory scope.
- If instructions conflict, follow the closest applicable instruction and explain the conflict when it affects implementation.
- Do not edit this `AGENTS.md` merely to accommodate a one-time task.
- If a recurring repository convention is missing and would improve future work, propose an update rather than silently changing project-wide policy.

---

## 24. Definition of done

A task is complete only when applicable conditions are satisfied:

- [ ] Implementation matches the requested behavior.
- [ ] Code follows existing architecture and these conventions.
- [ ] No unrelated changes were introduced.
- [ ] Input validation and authorization are addressed.
- [ ] Transaction and persistence semantics are correct.
- [ ] Query behavior and performance risks were considered.
- [ ] DTO/MapStruct behavior is explicit and safe.
- [ ] Tests were added/updated where appropriate.
- [ ] Relevant tests/build were run, or the limitation is stated.
- [ ] Database migrations are included when needed.
- [ ] Diff was reviewed.
- [ ] Final response accurately reports changes, verification, and remaining risks.


## Transaction Management (`@Transactional`)

### 1. Use the Correct Spring Transaction Annotation
- Use `org.springframework.transaction.annotation.Transactional` for Spring-managed transactions.
- Do not use `jakarta.transaction.Transactional` unless there is a specific Jakarta interoperability requirement and the project explicitly standardizes on it.
- Prefer service-layer transaction boundaries. Controllers must not open transactions; repositories should generally rely on transactions established by the service.
- Annotate public service methods that represent a single business operation and require atomicity.
- Do not add `@Transactional` indiscriminately to every service method. Read-only queries that do not require a transaction may omit it, but transaction requirements must be deliberate and documented.

### 2. Read and Write Transactions
- Use `@Transactional(readOnly = true)` for service methods that perform read-only database work.
- Use plain `@Transactional` (readOnly defaults to `false`) for operations that persist, update, or delete data.
- Do not rely on `readOnly = true` as a security boundary or guarantee that writes are impossible. It is a transaction/provider optimization hint.
- Do not set `readOnly = true` on a method that modifies entities, executes modifying queries, or triggers persistence changes.
- Keep read-only and write operations separated when that makes transaction intent clearer.

### 3. Transaction Scope and Boundaries
- Keep transactions as short as practical. Do not include remote HTTP calls, message-broker waits, file I/O, long computations, or user interaction inside a database transaction unless consistency requirements explicitly demand it.
- A single business use case that updates multiple entities or tables should usually execute in one service-layer transaction.
- Avoid multiple independent transactions for steps that must succeed or fail atomically.
- Do not manually open, commit, or roll back transactions when declarative Spring transactions are sufficient.
- Do not call `EntityManager.flush()` routinely. Flush only when required for constraint timing, generated values, ordering, or a clearly justified persistence behavior.
- Do not use `REQUIRES_NEW` by default. Apply it only when independent commit/rollback semantics are explicitly required and documented.
- Avoid `NOT_SUPPORTED`, `NEVER`, and other propagation modes unless there is a concrete architectural reason.

### 4. Propagation and Isolation
- Use the default `Propagation.REQUIRED` in normal application services unless a different propagation behavior is necessary.
- Do not specify propagation or isolation values merely for decoration.
- Use `REQUIRES_NEW` only when the inner operation must commit or roll back independently of the outer transaction. Consider connection-pool pressure and partial-commit consequences.
- Use `NESTED` only when the transaction manager and database support savepoints and the behavior is understood.
- Do not raise isolation levels globally or per-method without a demonstrated consistency requirement and PostgreSQL-specific review.
- Document the business invariant that requires non-default isolation, propagation, or timeout.

### 5. Rollback Semantics
- Spring rolls back by default on `RuntimeException` and `Error`, but not on checked exceptions.
- If a checked exception must trigger rollback, specify `rollbackFor` explicitly, for example:
  `@Transactional(rollbackFor = Exception.class)`.
- Prefer precise rollback rules rather than broad rules that unexpectedly roll back recoverable or expected outcomes.
- Do not catch and suppress exceptions inside a transactional method. If an exception is swallowed, Spring may not observe it and may commit the transaction.
- If an exception is caught for translation or logging, rethrow it or explicitly mark the transaction rollback-only when rollback is required.
- Do not use `noRollbackFor` without documenting why the transaction should commit despite that exception.

### 6. Proxy and Invocation Rules
- Spring’s default declarative transaction support is proxy-based.
- Do not rely on `@Transactional` on private, protected, or package-private methods for proxy interception; declare transaction boundaries on public service methods.
- Avoid self-invocation: calling another `@Transactional` method through `this` bypasses the proxy and does not apply the callee’s transaction settings.
- Do not solve self-invocation by injecting the same service into itself. Refactor the transaction boundary into another bean or use `TransactionTemplate` when programmatic control is genuinely needed.
- Do not assume transactions automatically propagate to newly created threads, executor tasks, `CompletableFuture` callbacks, or parallel streams.
- Do not use `@Async` and `@Transactional` together without explicitly designing transaction creation and execution on the async thread.

### 7. JPA/Hibernate Transaction Correctness
- Perform entity mutations within an active transaction. Avoid modifying managed entities outside a clearly defined transaction.
- Prefer dirty checking for ordinary managed-entity updates; do not call `save()` on an already-managed entity solely to trigger an update.
- For new entities, use `save()` when repository semantics require it; understand that JPA may defer SQL until flush or commit.
- Do not assume `save()` immediately executes SQL or surfaces constraint violations.
- Do not mix bulk JPQL/Criteria/native update or delete statements with managed entities without accounting for persistence-context staleness.
- After bulk modifying queries, clear or refresh the persistence context when necessary; use `clearAutomatically`/`flushAutomatically` only with deliberate semantics.
- Do not access lazy associations after the transaction has ended. Fetch the required data within the transaction and map to DTOs before returning.
- Do not return JPA entities from service APIs when the caller would access lazy state after transaction completion. Prefer DTO projections or MapStruct mapping inside the transaction.
- Avoid long-running transactions around paginated or streaming reads unless the database cursor, connection usage, and transaction lifetime have been explicitly evaluated.

### 8. Transactional Events and External Effects
- Do not send email, call external APIs, publish messages, or perform irreversible external side effects before the database transaction commits when consistency matters.
- Use `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` for best-effort post-commit actions when appropriate.
- Recognize that an after-commit listener alone is not durable: the process can fail after commit and before delivery.
- For reliable publication, prefer the transactional outbox pattern: write the business change and outbox record in the same transaction, then publish asynchronously with retries and idempotency.
- Do not assume a database transaction can atomically include PostgreSQL changes and an external service/message broker operation.

### 9. Testing
- Test transactional behavior at the service boundary with integration tests against PostgreSQL (prefer Testcontainers where available).
- Verify rollback behavior for both runtime and checked exceptions when applicable.
- Test that multi-entity business operations are atomic.
- Be aware that Spring test transactions may roll back after a test, which can hide commit-time behavior, deferred constraints, and after-commit events. Add tests that explicitly commit when those behaviors matter.
- Avoid relying only on H2 for PostgreSQL transaction, locking, isolation, native SQL, or constraint semantics.

### 10. Code Review Checklist
- Is the transaction boundary at the service/business-operation layer?
- Is `org.springframework.transaction.annotation.Transactional` used?
- Is `readOnly = true` accurate for every operation in the method?
- Is the transaction short and free of unnecessary external calls?
- Are propagation, isolation, timeout, and rollback rules justified?
- Could self-invocation bypass the annotation?
- Are exceptions swallowed or checked exceptions preventing rollback?
- Are lazy associations or managed entities accessed outside the transaction?
- Are bulk operations leaving the persistence context stale?
- Are external side effects handled after commit or through an outbox?
- Are transaction semantics tested against PostgreSQL where relevant?



## Project-Specific `@Transactional` Rules (Authoritative for This Codebase)

These rules refine the general transaction guidance above and follow the supplied project `CLAUDE.md`. If a general rule conflicts with this section, follow this project-specific section.

### Annotation and placement
- Always import **`org.springframework.transaction.annotation.Transactional`**.
- Do not use `jakarta.transaction.Transactional` in new or modified code. Existing usage may be migrated when the file is already being changed; do not perform unrelated mass refactoring.
- Put transaction boundaries on **public service methods** that represent a business operation. Do not annotate controllers.
- Services access persistence directly through `EntityManager` and Blaze-Persistence; there is no Spring Data repository layer in this project.
- Do not annotate every method automatically. Choose transaction semantics based on the operation.

### Read methods
- For new service read methods, use:
  ```java
  @Transactional(readOnly = true)
  public ApiResponse<List<DataRecordView>> findAll(...) {
      // Build Blaze CriteriaBuilder and return fully materialized Entity Views.
  }
  ```
- Existing reads may currently run without a transaction because Blaze Entity Views are fully materialized by the query. However, **standardize new code on `readOnly = true`** for consistent service boundaries and Hibernate read behavior.
- A read method marked `readOnly = true` must not mutate entities, invoke write operations, or execute modifying queries.
- Return Entity Views/DTOs, not managed entities or lazy associations that callers may access after the transaction ends.

### Write methods
- Use plain `@Transactional` for create/edit/delete/business operations that modify the database:
  ```java
  @Transactional
  public ApiResponse<EditMeasurement> edit(EditMeasurement src) {
      // validate → load → mutate or update view → flush when explicitly needed
  }
  ```
- Keep validation before database access when validation is independent of persisted state, following the project's `IlKeiValidator` convention.
- For loaded entities, rely on JPA dirty checking. `em.merge()` is unnecessary for an entity loaded and still managed in the same persistence context; use it only for detached entities when merge semantics are intended.
- Explicit `em.flush()` is acceptable in this codebase when the goal is to surface database constraint/SQL failures inside the service method. Do not flush reflexively in every method; preserve the established local convention and justify deviations.

### Transaction semantics
- Default to `Propagation.REQUIRED`; do not specify propagation, isolation, timeout, or rollback attributes unless the use case requires them.
- Keep the transaction short. Do not place HTTP, SSH, filesystem, or other blocking external I/O inside the database transaction.
- Do not use `REQUIRES_NEW` to “make it safer”; it creates an independently committed transaction and can leave partial business outcomes.
- Spring default rollback applies to `RuntimeException` and `Error`, not checked exceptions. If a checked exception must roll back, add a precise `rollbackFor`, for example:
  ```java
  @Transactional(rollbackFor = Exception.class)
  ```
  Use broad rollback rules only when the method's contract requires every exception to abort the transaction.
- Never catch and suppress an exception when the transaction must roll back. Rethrow it or explicitly mark rollback-only.

### Spring proxy constraints
- Spring's default transaction handling is proxy-based. A call from one method to another on the same instance (`this.someTransactionalMethod()`) bypasses proxy interception.
- Do not rely on `@Transactional` on private methods or self-invoked methods.
- If distinct transaction behavior is needed, extract the method to another Spring bean or use `TransactionTemplate` for genuinely programmatic transaction control.
- Transactions do not automatically transfer to new threads, executors, `@Async` methods, or parallel streams.

### Blaze-Persistence / JPA interaction
- The service method owns the transaction; `EntityManager`, `CriteriaBuilderFactory`, and `EntityViewManager` participate in that transaction.
- Entity Views returned by Blaze must be fully materialized for the fields required by the response before leaving the transaction.
- For writes using an Updatable Entity View, perform `viewManager.save(em, view)` inside the write transaction. Call `em.flush()` only when required by the project's explicit flush convention or to surface database errors before returning.
- Do not access lazy entity associations after the transaction closes.
- After bulk JPQL/Criteria/native updates, account for stale managed entities and persistence-context state; clear/refresh only when necessary and intentional.
- Do not assume `@Transactional(readOnly = true)` prevents database writes; it is an optimization hint, not an authorization mechanism.

### Recommended code review checklist
1. Is the Spring `@Transactional` import used?
2. Is the annotation on a public service method, not a controller/private helper?
3. Is the method a read (`readOnly = true`) or write (plain `@Transactional`) accurately?
4. Is the operation atomic at the business-use-case level?
5. Are exception and rollback semantics correct, including checked exceptions?
6. Is there self-invocation that bypasses the proxy?
7. Are external calls kept outside the transaction?
8. Are Blaze Entity Views fully materialized before returning?
9. Is `flush()` needed and justified rather than habitual?
10. Are transaction behavior and PostgreSQL-specific effects covered by integration tests?

---

## 25. Canonical implementation patterns (verified)

> The sections above are principles; this section is the **concrete house style** every new service/query/endpoint must follow. It is verified against the sibling `com.ilkeiapps` codebases and the actual newlis dependencies/APIs. Where an earlier illustrative snippet and this section differ, follow this section. Still treat real code as the source of truth and verify APIs before use.

### 25.1 Dependencies (already in `pom.xml`)
- Validation: `com.ilkeiapps:ilkeitool` and `com.ilkeiapps:ilkeitool-entity-blazebit`.
- JWT: `io.jsonwebtoken` `jjwt-api` + `jjwt-impl` (runtime) + `jjwt-gson` (0.12.x — use the 0.12 API: `Jwts.builder()…signWith(key).compact()`, `Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload()`).
- Security: `spring-boot-starter-security` (BCrypt + filter chain).

### 25.2 Service shell
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class XxxService {
    private final EntityManager em;                 // constructor-injected; see 25.3
    private final CriteriaBuilderFactory configBuilder;
    private final EntityViewManager viewManager;
    private final PasswordEncoder passwordEncoder;  // when hashing
    private final StatusService statusService;       // status-by-code lookups
    // other services as needed
}
```
- Write methods (`create`/`edit`/`remove`/`login`): annotate `@Transactional`. Read methods (`list`/`get`): leave unannotated.
- Every method starts `var wrap = new ApiResponse<T>(); wrap.setStatus(false);`. On failure set `wrap.setMessage(...)` and **return early**. On success `wrap.insertNewData(payload); wrap.setStatus(true);`.
- Writes build/mutate the entity then `em.persist(...)` / `em.merge(...)` followed by `em.flush()`.
- Prefer `List.getFirst()` over `.get(0)` (guard with `isEmpty()` first).

### 25.3 EntityManager injection
Constructor-inject `private final EntityManager em`. Spring Data JPA auto-registers the shared `EntityManager` bean, so **do not** define a custom `EntityManager` `@Bean` (a second one makes injection ambiguous). Spring Boot's own JPA autoconfig registers only `EntityManagerFactory`.

### 25.4 Queries — Blaze-Persistence + QueryDSL path metadata
Use the generated `Q` classes for type-safe path strings and Blaze to execute. Always give an explicit `.from(...)`:
```java
var q = new QUser("u");
var rows = configBuilder.create(em, User.class).from(User.class, q.getMetadata().getName())
        .where(q.username.toString()).eq(username)
        .where(q.status.code.toString()).eq("AKT")
        .getResultList();
```
- Count / existence: `…​.getCountQuery().getSingleResult()` (returns `Long`).
- Case-insensitive search + OR:
  ```java
  query = query.whereOr()
      .where("LOWER(" + q.name.toString() + ")").like().value("%" + kw.toLowerCase() + "%").noEscape()
      .where("LOWER(" + q.code.toString() + ")").like().value("%" + kw.toLowerCase() + "%").noEscape()
      .endOr();
  ```
- Ordering: `query.orderByDesc(q.id.toString())`.

### 25.5 Reads return Entity Views (never entities)
Put view interfaces under `entityview/` (scanned by `@EnableEntityViews`). Reads return `ApiResponse<SomeView>`:
```java
var num = filter.getPerPage() != null ? filter.getPerPage() : Constant.PER_PAGE;
var of  = Utility.getOffsetPage(filter.getPage(), num);
var res = viewManager.applySetting(EntityViewSetting.create(XxxListView.class, of, num), query)
        .withKeysetExtraction(true).getResultList();   // PagedList
wrap.setMaxPage(res.getTotalPages());
wrap.setData(res.stream().toList());
```
Single-row read: `EntityViewSetting.create(XxxDetailView.class)` and `wrap.setMaxPage(1)`. View example:
```java
@EntityView(User.class)
public interface UserListView {
    @IdMapping Long getId();
    String getUsername();
    StatusView getStatus();
    @EntityView(GeneralStatus.class) interface StatusView { String getCode(); String getName(); }
}
```

### 25.6 Validation — real `ilkeitool` API
POJO/input validation (beans carry Lombok `@FieldNameConstants` → `Bean.Fields.x`):
```java
var pr = IlKeiValidator.builder().withPojo(src)
        .pick(CreateXxx.Fields.name, "Nama").isMandatory().asString().withMinLen(2).pack()
        .pick(CreateXxx.Fields.idKota, "Kota").isMandatory().asLong().pack()
        .validate();
if (!pr.getResult()) { wrap.setMessage(pr.getMessage()); return wrap; }
```
Chain: `pick(field, label)` → `isMandatory()`/`asString()`/`asLong()`/`asInteger()`/`asDouble()`/`asDate()` / `withMinLen(n)`/`withMaxLen(n)`/`withLabel(..)` → `pack()`; end with `validate()`. `ProcessResult` exposes `getResult()` (`Boolean`) and `getMessage()`.

Entity/FK existence (needs the `configBuilder`):
```java
var ppr = IlKeiEntityValidator.builder().withCriteriaBuilderFactory(em, configBuilder)
        .pick().withEntity(Kota.class, "o").whereCriteriaEqual("o.id", src.getIdKota())
        .whereCriteriaEqual("o.status.code", "AKT").withErrorMessage("Kota tidak ditemukan").pack()
        .validate();
if (!ppr.getResult()) { wrap.setMessage(ppr.getMessage()); return wrap; }
```
Chain multiple `.pick()…pack()` before `.validate()` to check several entities at once.

### 25.7 Status & soft delete
Status rows are looked up by code via a `StatusService` (e.g. `getGeneralStatusByCode("AKT")`). Active = `"AKT"`; soft delete sets status `"REM"` and lists filter `where(q.status.code).notEq("REM")`. Do not hard-delete.

### 25.8 Authentication — stateless JWT stack
- `service/JwtService` — `generateToken(UserDetails)`, `extractUserName(token)`, `isTokenValid(token, userDetails)`; HS256 key from `JwtProperties`.
- `service/UserJwtService` — supplies a `UserDetailsService` (loads the user via Blaze, maps role to authorities).
- `config/JwtAuthenticationFilter extends OncePerRequestFilter` — reads `Authorization: Bearer`, validates, populates the `SecurityContext`; wrap parsing in try/catch so a bad/expired token proceeds unauthenticated.
- `config/SecurityConfiguration` — `@EnableWebSecurity`, CSRF off, CORS, `STATELESS`, `addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`, permit the login endpoint + API docs, `BCryptPasswordEncoder` bean.
- Login lives in a service, verifies the password with `passwordEncoder.matches(...)`, and returns `ApiResponse<String>` (the JWT). Passwords are always BCrypt.

### 25.9 Blaze configuration
`config/BlazePersistenceConfiguration`: `@EnableEntityViews("com.ilkeiapps.indotelemed.newlis.entityview")`, `@PersistenceUnit EntityManagerFactory`, and `@Bean` `CriteriaBuilderFactory` (`Criteria.getDefault().createCriteriaBuilderFactory(emf)`) + `EntityViewManager`.

### 25.10 Request beans: separate create and update
Create and update take **separate request beans**, not one shared bean with a nullable id:
- **`XxxRequest`** — the create payload. It carries **no `id`** (the id is server-generated), so a client cannot supply one on create.
- **`XxxUpdateRequest`** — the update payload. Same client-settable fields **plus a mandatory `id`** identifying the row being changed.

Both are plain beans in `bean/` with `@Getter @Setter @ToString(includeFieldNames = true) @FieldNameConstants`, exposing only client-settable columns so server-controlled fields (`status`, audit columns, FKs that must be looked up and authorized) cannot be mass-assigned. Validate against each bean's own `Fields.*` — `updateProject` picks `XxxUpdateRequest.Fields.id` first, then the rest. The controller's `save` and `update` endpoints bind their respective bean. Delete keeps its own id-only bean (`XxxIdRequest`). Reference: `ProjectRequest` / `ProjectUpdateRequest` and `ProjectService`/`ProjectController`.

### 25.11 Controllers return the service `ApiResponse` directly
A controller endpoint **returns the service's `ApiResponse<T>` as-is**, typed to the concrete `T` (`ApiResponse<ProjectRequest>`, `ApiResponse<UserRequest>`, `ApiResponse<UserIdRequest>`, …). Do **not** add an `ack(...)` helper that re-wraps the response into `ApiResponse<String>` to carry only status + message — the service already owns the full response contract (`status`, `message`, `data`, `maxPage`), so re-wrapping is redundant and drops useful payload.

This is safe **because services return scrubbed request DTOs, never managed entities** — `saveUser`/`resetPassword`/`changeMyPassword` null the password before returning, create/update/delete echo the request bean, and reads return Entity Views. The rule that makes it safe is the real constraint: **a service method whose result reaches a controller must not put a managed JPA entity in the response** (OSIV is disabled and entities can carry secrets/lazy graphs — see §6, §15). If an operation would otherwise return an entity, return a DTO/Entity View / a simple value instead — e.g. the avatar/signature uploads return `ApiResponse<String>` (the stored relative path), not `ApiResponse<User>`. Reference: `ProjectController`, `UserController`, and `UserService.uploadMyImage`.


