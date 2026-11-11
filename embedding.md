# Claude Code Implementation Prompt
# Project-Based Document Knowledge Base and RAG Module

## 1. Project Context

You are working on an existing enterprise application repository.

Your task is to implement a production-oriented Document Knowledge Base and RAG (Retrieval-Augmented Generation) module directly inside the existing application.

**Do not create a new project or replace the existing application architecture.**

The application technology stack:

| Component | Technology |
|---|---|
| Java | JDK 25 |
| Backend | Spring Boot 4 |
| AI Integration | Spring AI 2 |
| AI Orchestration | Embabel |
| Frontend | Vue.js 3 |
| Database | PostgreSQL 18 |
| Vector Database | pgvector |
| Document Storage | MinIO |
| Document Extraction | Apache Tika Server |
| Embedding Model | BGE-M3 |
| Build Tool | Maven |

Before implementing anything, inspect the existing repository to understand its architecture, conventions, dependencies, entities, services, repositories, security, and configuration.

Reuse existing components whenever possible.

---

# 2. Core Requirements

Implement a document knowledge base with the following capabilities:

1. Upload documents.
2. Associate every document with an existing project.
3. Store original documents in MinIO.
4. Extract document content using Apache Tika Server.
5. Normalize extracted content into structured document elements.
6. Split extracted content into semantically meaningful chunks.
7. Generate embeddings using BGE-M3.
8. Store document chunks and embeddings in PostgreSQL with pgvector.
9. Perform project-scoped semantic search.
10. Retrieve relevant document context for RAG.
11. Generate answers grounded in retrieved documents.
12. Provide document source references and page/slide provenance.
13. Support document versioning.
14. Support asynchronous document processing.
15. Track document-processing status through the existing master-status architecture.

Supported document formats should include, where supported by the existing extraction infrastructure:

- PDF
- DOC
- DOCX
- XLS
- XLSX
- PPT
- PPTX
- TXT
- HTML
- Other formats supported by Apache Tika.

The implementation must preserve document structure and source provenance as much as possible.

---

# 3. Strict Database Implementation Strategy: JPA Entity-First

Implement the database model using Spring Data JPA and Hibernate Entity mapping.

Mandatory requirements:

1. Do NOT use Flyway.
2. Do NOT use Liquibase.
3. Do NOT create Flyway migration files.
4. Do NOT create manual SQL DDL scripts to define application tables.
5. Define application tables using JPA Entity classes.
6. Use Hibernate/JPA annotations for:
   - Table names.
   - Column names.
   - Column types.
   - Primary keys.
   - Foreign keys.
   - Unique constraints.
   - Indexes.
   - Entity relationships.
7. Follow the existing Hibernate schema-management configuration.
8. Do not silently modify existing database tables.
9. Do not create a new `projects` table.
10. Do not introduce a database schema management mechanism that conflicts with the existing application.

Use the existing Spring Boot, Hibernate, Spring Data JPA, and PostgreSQL dependencies.

Do not upgrade dependencies unless there is a documented technical reason.

Important production consideration:

Do not assume that `spring.jpa.hibernate.ddl-auto=update` is a reliable database migration mechanism.

Implement the entities first and document the required schema creation and validation procedure according to the existing application's Hibernate configuration.

PostgreSQL-specific features that cannot be represented through standard JPA annotations must be documented separately. Do not silently create SQL migration files.

---

# 4. Primary Key and Foreign Key Conventions

All new knowledge-base entity primary keys must use BIGSERIAL.

Java mapping:

- Database primary key: BIGSERIAL.
- Java primary key: `Long`.
- Generation strategy: `GenerationType.IDENTITY`.
- New BIGINT foreign keys: Java `Long` references.
- Do NOT use UUID.
- Do NOT use `GenerationType.UUID`.
- Do NOT generate UUID primary keys in application code.

Example:

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;
```

For entity relationships, use appropriate JPA associations.

Example:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "project_id", nullable = false)
private Project project;
```

## Existing Project Entity

The application already has a `Project` entity and a corresponding database table.

Requirements:

1. Inspect the existing `Project` entity.
2. Inspect the actual database primary-key type of the existing projects table.
3. Reuse the existing entity.
4. Do not create another Project entity.
5. Do not create a new projects table.
6. Do not change the existing project primary-key type without explicit approval.
7. Ensure all project relationships are compatible with the actual existing project ID type.

If the existing project ID is not BIGINT, stop and report the incompatibility. Propose a migration strategy instead of silently changing the existing schema.

## Existing User Entity

Inspect the existing user entity and its primary-key type.

For fields such as `createdBy`, use the actual existing user ID type and relationship conventions.

Do not assume that user IDs are BIGINT.

---

# 5. Project-Based Data Isolation

The application uses project-based data isolation.

There is no organization-level or department-level isolation in this module.

Strict requirements:

- Every document must belong to an existing project.
- Every document chunk must be associated with a project.
- Every processing job must be associated with its document and project where appropriate.
- Every document retrieval operation must be project-scoped.
- Every vector search operation must be restricted to authorized project IDs.
- Validate that the current user has permission to access the requested project.
- Never retrieve documents or chunks belonging to unauthorized projects.
- Do not introduce `organization_id`.
- Do not introduce `department_id`.
- Do not create organization or department entities for this feature.

The `project_id` filter must be applied at the database query level, not merely after retrieving search results.

For vector search, project authorization must be part of the retrieval query itself.

If PostgreSQL Row-Level Security (RLS) is considered, first inspect the existing security architecture. Do not introduce RLS or transaction-scoped security context without documenting its compatibility with the current connection-pooling and transaction model.

---

# 6. Required JPA Entities

Implement the following entities using the existing package structure and coding conventions.

## 6.1 KnowledgeDocument

Entity: `KnowledgeDocument`

Table: `knowledge_documents`

Purpose:

Store document metadata, storage information, project ownership, versioning information, and processing lifecycle.

Suggested fields:

| Field | Java Type | Description |
|---|---|---|
| id | Long | BIGSERIAL primary key |
| project | Project | Existing project relationship |
| logicalDocumentId | Long | Logical document identifier shared across versions |
| parentVersion | KnowledgeDocument | Previous document version |
| versionNumber | Integer | Version sequence |
| isCurrent | Boolean | Indicates current active version |
| originalFilename | String | Original uploaded filename |
| storedFilename | String | Internal stored filename |
| contentType | String | MIME type |
| fileExtension | String | File extension |
| fileSize | Long | Original file size |
| checksum | String | Document checksum |
| storageBucket | String | MinIO bucket |
| storageObjectKey | String | MinIO object key |
| documentTitle | String | Document title |
| description | String | Document description |
| sourceType | String or existing master reference | UPLOAD or MICROSOFT_TEAMS |
| processingStatus | StatusProcess | Processing status master relationship |
| processingError | String | Processing error details |
| createdBy | Existing user ID type | Creator |
| createdAt | Instant | Creation timestamp |
| updatedAt | Instant | Last update |
| processedAt | Instant | Processing completion timestamp |

Important:

- `processingStatus` MUST reference the existing `StatusProcess` master entity/table.
- Do NOT use a Java Enum for `processingStatus`.
- Do NOT store processing status as a String directly in `knowledge_documents`.
- Follow the existing `statusGeneral` implementation pattern for master-status generation and management.

Use appropriate column lengths, nullability, indexes, and JPA relationships.

## 6.2 DocumentElement

Entity: `DocumentElement`

Table: `document_elements`

Purpose:

Store normalized document extraction output while preserving document structure and provenance.

Suggested fields:

| Field | Java Type | Description |
|---|---|---|
| id | Long | BIGSERIAL primary key |
| document | KnowledgeDocument | Parent document |
| elementType | String or appropriate existing master reference | Element type |
| parent | DocumentElement | Parent element |
| sequenceNumber | Integer | Original extraction order |
| headingLevel | Integer | Heading hierarchy |
| pageNumber | Integer | PDF/document page |
| slideNumber | Integer | Presentation slide |
| elementPath | String | Structural location |
| content | String | Extracted text |
| structuredContent | JSONB | Structured content |
| metadata | JSONB | Extraction metadata |
| createdAt | Instant | Creation timestamp |

Element types should support:

- DOCUMENT
- HEADING
- PARAGRAPH
- TABLE
- TABLE_ROW
- LIST
- IMAGE
- FOOTNOTE
- OTHER

Preserve:

- Original extraction sequence.
- Heading hierarchy.
- Parent-child relationships.
- Page and slide references.
- Table structure.
- Relevant extraction metadata.

Use PostgreSQL JSONB mapping supported by the existing Hibernate version.

Prefer `@JdbcTypeCode(SqlTypes.JSON)` where compatible.

## 6.3 DocumentChunk

Entity: `DocumentChunk`

Table: `document_chunks`

Purpose:

Store RAG-ready text chunks and their embeddings.

Suggested fields:

| Field | Java Type | Description |
|---|---|---|
| id | Long | BIGSERIAL primary key |
| project | Project | Existing project relationship |
| document | KnowledgeDocument | Source document |
| chunkIndex | Integer | Chunk sequence |
| content | String | Chunk text |
| tokenCount | Integer | Estimated token count |
| pageStart | Integer | Starting page |
| pageEnd | Integer | Ending page |
| slideStart | Integer | Starting slide |
| slideEnd | Integer | Ending slide |
| embedding | pgvector vector | 1024-dimensional embedding |
| metadata | JSONB | Additional chunk metadata |
| createdAt | Instant | Creation timestamp |

### Embedding Requirements

- Initial embedding model: BGE-M3.
- Embedding dimension: 1024.
- Use PostgreSQL pgvector.
- Use cosine similarity unless the existing embedding implementation requires another metric.
- Use a pgvector-compatible Java/Hibernate integration supported by the project's actual dependency versions.
- Do not map the embedding as an ordinary PostgreSQL array or JSON field.
- Ensure the actual database vector dimension matches the configured embedding model.

Keep the embedding implementation configurable where practical.

## 6.4 DocumentChunkElement

Entity: `DocumentChunkElement`

Table: `document_chunk_elements`

Purpose:

Maintain traceability between document chunks and their original document elements.

Requirements:

- One chunk can contain multiple document elements.
- One document element can be referenced by multiple chunks.
- Preserve the order of source elements within each chunk.

Suggested fields:

| Field | Java Type | Description |
|---|---|---|
| id | Long | BIGSERIAL primary key |
| chunk | DocumentChunk | Chunk relationship |
| element | DocumentElement | Source element |
| elementOrder | Integer | Element order within the chunk |
| createdAt | Instant | Creation timestamp |

Prefer a dedicated entity with a BIGSERIAL primary key and two `ManyToOne` relationships.

Add an appropriate unique constraint to prevent duplicate chunk-element associations.

## 6.5 DocumentProcessingJob

Entity: `DocumentProcessingJob`

Table: `document_processing_jobs`

Purpose:

Track asynchronous document-processing jobs, retries, execution status, and failures.

Suggested fields:

| Field | Java Type | Description |
|---|---|---|
| id | Long | BIGSERIAL primary key |
| project | Project | Existing project relationship |
| document | KnowledgeDocument | Document being processed |
| jobType | String or appropriate existing master reference | Processing job type |
| status | StatusProcess | Processing status master relationship |
| attemptCount | Integer | Number of attempts |
| maxAttempts | Integer | Maximum retry attempts |
| errorMessage | String | Error details |
| startedAt | Instant | Processing start |
| completedAt | Instant | Processing completion |
| createdAt | Instant | Creation timestamp |
| updatedAt | Instant | Last update |

Inspect the existing status architecture before deciding whether `DocumentProcessingJob` should also use `StatusProcess`.

If the existing application uses a shared master-status mechanism, follow it.

Do not introduce a Java Enum for a status that should be managed through the existing master-status architecture.

---

# 7. StatusProcess Master Data Integration

This requirement is mandatory.

`KnowledgeDocument.processingStatus` must reference the `status_process` master table.

Do not use a Java Enum for this field.

## 7.1 Inspect Existing StatusGeneral

Before implementing `StatusProcess`, locate and inspect the complete existing `statusGeneral` implementation.

Study:

1. Entity structure.
2. Table mapping.
3. Primary-key type.
4. Repository implementation.
5. Service implementation.
6. Master-data generation or initialization mechanism.
7. Duplicate prevention mechanism.
8. Data retrieval conventions.
9. Entity relationships.
10. DTO and API usage, if applicable.

Follow the same architectural and coding conventions.

Do not modify the existing working `statusGeneral` implementation.

## 7.2 StatusProcess Implementation

Create or reuse the `StatusProcess` entity representing the `status_process` table.

Follow the existing `statusGeneral` implementation pattern.

The document processing workflow requires these statuses:

- PENDING
- PROCESSING
- COMPLETED
- FAILED
- CANCELLED

Inspect the actual master-data structure before deciding how these values should be represented.

Do not assume column names, primary-key types, or entity structures.

## 7.3 Master Data Generation

Generate or initialize the required status records using the same mechanism used by `statusGeneral`.

Strict requirements:

- No Flyway.
- No Liquibase.
- No manual SQL seed scripts.
- No alternative initialization mechanism that conflicts with the existing architecture.
- Initialization must be idempotent.
- Repeated application startup must not generate duplicate status records.
- Existing status records must be reused.
- Follow the existing application's naming, code, description, and active-status conventions.

## 7.4 JPA Relationship

Use the actual `StatusProcess` entity and its primary-key type.

Conceptual example:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "processing_status_id", nullable = false)
private StatusProcess processingStatus;
```

This is illustrative only.

Adapt the implementation to the actual existing master-status conventions.

## 7.5 Status Transitions

Implement appropriate business logic for status transitions:

```text
PENDING
   |
   v
PROCESSING
   |
   +----> COMPLETED
   |
   +----> FAILED
```

A failed job may be retried according to the configured retry policy.

A retry must transition the processing status according to the application's existing status-management conventions.

CANCELLED must be supported when a processing job is explicitly cancelled.

Do not hardcode status IDs.

Resolve statuses through the appropriate repository or service mechanism.

---

# 8. Document Versioning

Implement document versioning without unnecessary circular foreign-key dependencies.

Requirements:

1. Multiple document versions belong to the same logical document.
2. Each version has its own `KnowledgeDocument` record.
3. `logicalDocumentId` identifies the logical document across versions.
4. `versionNumber` increases sequentially.
5. `parentVersion` references the preceding version when applicable.
6. Only one version can be current for a logical document within a project.
7. A new version must finish processing successfully before becoming current.
8. Failed processing must not replace the existing current version.
9. Old versions must remain available for historical reference.
10. Normal RAG search must use only the current successfully processed version.

Define the logical document identity carefully.

Avoid circular foreign-key dependencies. Decide whether `logicalDocumentId` refers to the first version record or whether a separate logical-document entity is justified.

Prefer the simplest design consistent with the existing application architecture.

Use appropriate JPA uniqueness constraints where possible.

If a PostgreSQL partial unique index is required to enforce one current version per logical document, document the limitation of standard JPA annotations and propose a PostgreSQL-specific schema-management solution.

Do not create a manual DDL migration file.

---

# 9. Document Extraction and Normalization

Integrate with the existing Apache Tika Server.

Do not implement document extraction by loading entire files into application memory unnecessarily.

The document processing pipeline should be:

1. Receive document upload.
2. Validate file type and size.
3. Validate project authorization.
4. Store the original document in MinIO.
5. Create a `KnowledgeDocument` record.
6. Create a `DocumentProcessingJob` record.
7. Process asynchronously.
8. Send the document to Apache Tika Server.
9. Extract metadata and document content.
10. Normalize the extraction result.
11. Persist normalized `DocumentElement` records.
12. Split content into chunks.
13. Generate embeddings.
14. Persist `DocumentChunk` and `DocumentChunkElement` records.
15. Mark processing as COMPLETED only after successful persistence.
16. Update document processing timestamps.

Use the appropriate Apache Tika endpoint depending on extraction requirements.

Preserve:

- Document title.
- Page numbers.
- Slide numbers.
- Heading hierarchy.
- Paragraph boundaries.
- Table content.
- Extraction sequence.
- Metadata.
- Source provenance.

Do not assume Apache Tika always extracts PDF tables accurately.

If the existing architecture requires additional parsers for PDF tables, Office formats, or scanned documents, document the limitations and design extension points.

Scanned documents may require OCR. Do not silently assume that all PDF files contain extractable text.

## Normalization Layer

Implement a dedicated normalization layer between Tika extraction and document-element persistence.

Responsibilities:

- Convert extraction output into a consistent internal structure.
- Normalize whitespace.
- Preserve heading hierarchy.
- Preserve table structure where possible.
- Preserve page and slide references.
- Preserve original extraction order.
- Remove irrelevant extraction artifacts.
- Avoid destroying meaningful document structure.

Do not couple the RAG chunking implementation directly to raw Tika response formats.

---

# 10. Document Chunking Strategy

Implement structure-aware chunking.

Initial recommended configuration:

| Parameter | Initial Value |
|---|---:|
| Target chunk size | 700 tokens |
| Chunk overlap | 100 tokens |
| Minimum chunk size | 150 tokens |
| Maximum chunk size | 1,000 tokens |

These values are initial configuration defaults, not hardcoded permanent rules.

Chunking requirements:

1. Prefer heading boundaries.
2. Preserve paragraph boundaries.
3. Avoid splitting sentences unnecessarily.
4. Keep related content together.
5. Preserve page and slide provenance.
6. Maintain references to source `DocumentElement` records.
7. Avoid duplicate or empty chunks.

### Table Chunking

Treat tables separately from ordinary paragraphs.

Requirements:

- Preserve table headers.
- Repeat relevant headers when splitting large tables.
- Keep related rows together.
- Preserve table metadata.
- Preserve page and slide references.
- Ensure that a retrieved table chunk remains understandable without unrelated chunks.

Implement configurable chunking parameters.

Do not use a simplistic fixed-character splitter as the primary strategy.

---

# 11. Embedding Generation

Use Spring AI integration where compatible with the existing project.

Initial embedding model: BGE-M3.

Requirements:

- 1024-dimensional vectors.
- Support batch embedding where appropriate.
- Avoid embedding duplicate unchanged document content unnecessarily.
- Handle embedding failures.
- Support asynchronous processing.
- Persist vectors in PostgreSQL pgvector.
- Ensure vector dimensions match the configured model.
- Keep model configuration externalized through the application's existing configuration conventions.

Do not send documents to external embedding services if the configured deployment requires local embedding inference.

The embedding service should be abstracted so the model can be replaced without redesigning the document persistence layer.

---

# 12. Vector Search and RAG Retrieval

Implement project-scoped semantic search.

Requirements:

1. Use PostgreSQL pgvector.
2. Use cosine similarity unless otherwise justified.
3. Support configurable top-K retrieval.
4. Apply project authorization filters inside the database query.
5. Retrieve only chunks from current document versions.
6. Exclude documents that are not successfully processed.
7. Support metadata filtering.
8. Return source document metadata.
9. Return page and slide references.
10. Preserve chunk-to-element traceability.

Use native SQL only where required for pgvector operations or PostgreSQL-specific functionality.

Do not use SQL DDL to create application tables.

## Retrieval Security

Never perform global vector search and filter unauthorized projects afterward.

The project authorization condition must be part of the vector-search query itself.

Validate authorization before performing retrieval.

## RAG Answer Generation

Use Spring AI for retrieval-augmented generation.

The RAG service should:

1. Accept a user question.
2. Validate project access.
3. Generate the query embedding.
4. Retrieve relevant chunks within the authorized project.
5. Construct the context.
6. Generate an answer using the configured LLM.
7. Include source references.
8. Avoid unsupported factual claims.
9. Clearly indicate when the available documents do not contain sufficient information.

Answers must be grounded in retrieved document content.

Provide document title, version, page number, and relevant source information whenever available.

Do not fabricate citations or page references.

---

# 13. PostgreSQL and Hibernate Mapping

Use PostgreSQL 18 and the existing Hibernate version.

## JSONB

For JSONB fields:

- Use Hibernate JSON mapping supported by the existing Hibernate version.
- Prefer `@JdbcTypeCode(SqlTypes.JSON)` where compatible.
- Use `JsonNode` or `Map<String, Object>` where appropriate.
- Do not introduce unnecessary custom converters.

## pgvector

For vector fields:

- Use a pgvector-compatible integration.
- Verify compatibility with the existing Hibernate and Spring Boot versions.
- Map the embedding to a PostgreSQL vector column.
- Ensure the vector dimension is 1024.
- Do not substitute JSON or ordinary arrays.

## Indexes

Use JPA index and unique-constraint annotations where appropriate.

Consider indexes for:

- Project relationships.
- Document relationships.
- Processing status relationships.
- Document versions.
- Current document lookup.
- Chunk retrieval.
- Processing job lookup.
- Chunk-element relationships.

HNSW vector indexing should be considered for semantic search.

If the required PostgreSQL-specific index cannot be represented through standard JPA annotations, report it separately.

Do not silently generate DDL scripts.

---

# 14. JPA Relationship and Performance Conventions

Follow these conventions:

- Use `FetchType.LAZY` for `ManyToOne` relationships unless there is a documented reason otherwise.
- Avoid unnecessary bidirectional relationships.
- Avoid unnecessary `CascadeType.ALL`.
- Avoid recursive serialization of entity relationships.
- Do not expose JPA entities directly through REST APIs.
- Use DTOs for requests and responses.
- Use pagination for document and chunk listing.
- Avoid N+1 query problems.
- Use explicit repository queries or projections for read-heavy operations.
- Use `@Version` for optimistic locking where appropriate.
- Follow the existing auditing conventions if the application already has them.

For complex transformations, branching, and side-effect-heavy processing logic, prefer traditional Java loops rather than unnecessarily complicated Stream pipelines.

Use Java Streams for simple transformations and straightforward collection operations.

---

# 15. Spring Data JPA Repositories

Implement repositories for:

- `KnowledgeDocumentRepository`
- `DocumentElementRepository`
- `DocumentChunkRepository`
- `DocumentChunkElementRepository`
- `DocumentProcessingJobRepository`
- `StatusProcessRepository`, if not already available.

Requirements:

- Extend `JpaRepository` where appropriate.
- Use the correct ID type for every entity.
- Implement project-scoped queries.
- Implement current-version queries.
- Implement document-processing status queries.
- Implement pagination and sorting.
- Implement vector similarity search.
- Exclude non-current document versions from normal retrieval.
- Exclude documents that are not successfully processed.
- Use the existing master-status repository/service conventions.
- Avoid duplicating existing repositories or services.

Use native queries only when necessary, particularly for pgvector similarity search.

---

# 16. Asynchronous Document Processing

Document extraction, chunking, and embedding generation must not block the upload HTTP request.

Use the existing asynchronous processing architecture if one already exists.

If no suitable mechanism exists, propose an implementation that fits the current project architecture.

Requirements:

- Durable processing-job tracking.
- Retry handling.
- Failure handling.
- Processing timestamps.
- Idempotent processing.
- Duplicate job prevention where necessary.
- Configurable retry limits.
- Clear processing-status transitions.
- No duplicate chunks when retrying a partially completed job.

The upload API should return a document identifier and processing status without waiting for the entire extraction and embedding pipeline to finish.

Ensure that failed processing does not expose incomplete document content through normal RAG retrieval.

---

# 17. MinIO Integration

Use the existing MinIO integration if available.

Requirements:

- Store original uploaded files in MinIO.
- Store bucket and object key in `KnowledgeDocument`.
- Do not store binary document content directly in PostgreSQL.
- Use unique object keys.
- Validate file size and MIME type.
- Avoid filename collisions.
- Support document retrieval and download.
- Handle storage failures.
- Ensure unauthorized users cannot access documents through predictable object keys.

Do not duplicate an existing MinIO client or storage service.

---

# 18. REST API and DTO Layer

Follow the existing REST API structure, URL conventions, exception handling, validation, and response envelope.

Implement the necessary APIs for:

1. Uploading a document to a project.
2. Listing project documents.
3. Retrieving document details.
4. Downloading an original document.
5. Retrieving document versions.
6. Retrieving processing status.
7. Deleting or archiving a document according to existing application conventions.
8. Searching document content.
9. Asking questions against the project knowledge base.
10. Retrieving source references for generated answers.

All APIs must validate project access.

Use request and response DTOs.

Do not return JPA entities directly.

Avoid exposing internal MinIO object keys or other implementation details unnecessarily.

---

# 19. Testing Requirements

Write unit tests and integration tests.

## Unit Tests

Use Mockito for service-layer unit tests.

Do not use `@SpringBootTest` for ordinary service unit tests.

Test:

- Document upload validation.
- Project authorization.
- Document metadata persistence.
- Status resolution.
- Status transitions.
- Master-data initialization.
- Document versioning.
- Chunk generation.
- Chunk-element traceability.
- Processing retry behavior.
- Processing failure handling.
- RAG context construction.
- Retrieval authorization.

## Repository Integration Tests

Use an appropriate PostgreSQL test environment for PostgreSQL-specific behavior.

Do not assume H2 supports:

- pgvector.
- PostgreSQL JSONB behavior.
- PostgreSQL vector similarity operators.
- PostgreSQL-specific indexes.

Test:

- Entity relationships.
- Foreign-key mappings.
- JSONB persistence.
- pgvector persistence.
- Project-scoped queries.
- Vector similarity queries.
- Current-version filtering.
- Processing-status relationships.

Ensure tests follow the existing project's testing conventions.

---

# 20. Implementation Workflow

Before writing code, perform repository inspection.

## Phase 1 — Existing Architecture Inspection

Inspect:

1. Project directory structure.
2. Maven dependencies.
3. Spring Boot version.
4. Hibernate version.
5. Existing JPA entities.
6. Existing `Project` entity.
7. Existing user entity.
8. Existing `statusGeneral` implementation.
9. Existing `StatusProcess` implementation, if any.
10. Existing master-data initialization mechanism.
11. Existing repositories and services.
12. Existing security and project authorization.
13. Existing MinIO integration.
14. Existing Apache Tika integration.
15. Existing Spring AI and Embabel integration.
16. Existing async processing infrastructure.
17. Existing PostgreSQL and pgvector configuration.

Do not assume that a component is missing before inspecting the repository.

## Phase 2 — Design Validation

Before implementation, report:

- Existing components that can be reused.
- New entities required.
- Existing entities that need relationships.
- Actual primary-key types.
- Compatibility of the existing Project entity.
- Existing status-management pattern.
- pgvector integration compatibility.
- Any schema requirements that cannot be expressed through JPA annotations.

Do not modify unrelated modules.

## Phase 3 — Implementation

Implement in the following order:

1. JPA entities.
2. Entity relationships.
3. Master-status integration.
4. Master-data initialization.
5. Spring Data repositories.
6. DTOs.
7. Mapping layer.
8. Service layer.
9. Project authorization.
10. Document versioning.
11. Tika extraction integration.
12. Document normalization.
13. Chunking.
14. Embedding generation.
15. pgvector retrieval.
16. RAG service.
17. REST APIs.
18. Asynchronous processing.
19. Tests.

Follow existing package naming and architecture.

## Phase 4 — Verification

Run:

- Maven compilation.
- Relevant unit tests.
- Repository integration tests where possible.
- Existing project tests affected by the implementation.

Fix compilation errors and test failures introduced by the changes.

Do not modify unrelated application behavior to make tests pass.

---

# 21. Strict Restrictions

The following restrictions are mandatory:

- Do not create a new Spring Boot project.
- Do not replace the existing application architecture.
- Do not use Flyway.
- Do not use Liquibase.
- Do not create manual DDL migration files.
- Do not create manual SQL seed scripts.
- Use JPA Entity mapping for application tables.
- Do not use UUID primary keys.
- Use BIGSERIAL for new knowledge-base entity primary keys.
- Use BIGINT foreign keys where compatible with existing referenced entities.
- Do not introduce `organization_id`.
- Do not introduce `department_id`.
- Use `project_id` for project-based isolation.
- Reuse the existing Project entity.
- Do not create a new projects table.
- Do not modify existing primary-key types without explicit approval.
- Do not use Java Enum for `KnowledgeDocument.processingStatus`.
- Use the existing `StatusProcess` master table.
- Follow the existing `statusGeneral` implementation pattern.
- Do not hardcode master-status IDs.
- Do not create duplicate master-status records.
- Do not expose JPA entities directly through REST APIs.
- Do not retrieve unauthorized project data.
- Do not expose incomplete document-processing results through normal RAG retrieval.
- Do not change unrelated application modules.

---

# 22. Final Deliverables

When implementation is complete, provide a detailed implementation report.

Include:

## A. Architecture

- Summary of the implemented architecture.
- Components reused from the existing application.
- New components introduced.
- Document-processing workflow.

## B. Database and Entities

- List of new JPA entities.
- Table names.
- Primary-key mappings.
- Foreign-key mappings.
- Entity relationship overview.
- JSONB mapping details.
- pgvector mapping details.
- Index definitions.
- PostgreSQL-specific requirements not expressible through standard JPA annotations.

## C. Project Isolation

- How project authorization is validated.
- How `project_id` is applied to document retrieval.
- How `project_id` is applied to vector search.
- How unauthorized cross-project retrieval is prevented.

## D. StatusProcess

- Existing `statusGeneral` implementation used as reference.
- New or reused `StatusProcess` implementation.
- Master-data initialization mechanism.
- Duplicate-prevention mechanism.
- JPA relationship with KnowledgeDocument.
- Status transitions.
- How status IDs are resolved without hardcoding.

## E. Document Processing

- MinIO integration.
- Apache Tika integration.
- Normalization strategy.
- Chunking strategy.
- Embedding generation.
- Asynchronous processing.
- Retry and failure handling.

## F. RAG

- Vector search implementation.
- Project-scoped retrieval.
- Current document version filtering.
- Source citation and provenance handling.
- LLM integration.

## G. Testing

- Unit tests created.
- Integration tests created.
- Maven compilation result.
- Test execution result.
- Known limitations.

## H. Remaining Requirements

Clearly identify any functionality that requires PostgreSQL-specific schema operations outside standard JPA annotations.

Do not claim that the database schema is fully provisioned merely because the entities compile.

Clearly distinguish between:

- Implemented in source code.
- Verified through tests.
- Successfully applied to the database.
- Still requiring operational configuration.

---

# Final Instruction

Work directly in the existing repository.

Inspect first, design second, implement third, and verify last.

Prioritize consistency with the existing application's architecture over introducing new abstractions or dependencies.

Do not make assumptions about existing entities, table structures, master-status data, or primary-key types. Verify them from the actual source code before implementation.

If a critical incompatibility is discovered, stop the affected implementation and report the issue with a recommended solution instead of silently changing existing application structures.