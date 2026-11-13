# Claude Code Task: Optimize Document Embedding Pipeline and Jinfer CPU Inference Performance

## 1. Objective

Optimize the existing document-processing and embedding pipeline to resolve timeout issues when processing large PDF documents (approximately 19 MB).

The application uses:

| Component | Technology |
|---|---|
| Java | JDK 25 |
| Backend | Spring Boot 4.1.2 |
| AI Framework | Spring AI 2.0.1 |
| Inference Runtime | Qxotic AI Jinfer 0.3.1 |
| Embedding Model | Qwen3-Embedding-0.6B |
| Model Format | GGUF F16 |
| Inference Hardware | CPU-only |
| Document Extraction | Apache Tika Server |
| Document Storage | MinIO |
| Database | PostgreSQL 18 |
| Vector Database | pgvector |

The inference server is already deployed as a separate Docker application:

`https://inference.example.internal`

The existing inference server provides OpenAI-compatible APIs.

The current implementation works but experiences timeout issues during the embedding stage when processing large PDF documents.

Your task is to investigate the existing implementation, identify bottlenecks, and optimize it without unnecessarily redesigning or replacing existing components.

---

# 2. Existing Document Processing Pipeline

The current implementation is located in:

`KnowledgeProcessingService.process()`

The existing workflow is:

### Step 1: Begin Processing

- Start a short database transaction.
- Load the `KnowledgeDocument`.
- Load the latest processing job.
- Mark both as PROCESSING.
- Clear previous error information.
- Capture the MinIO storage key, content type, and document title.
- Support up to three processing attempts.

### Step 2: Download

- Download the original file from MinIO.
- Maximum upload size: 40 MB (41,943,040 bytes).
- Download the file outside any database transaction.

### Step 3: Extract

- Extract document content using Apache Tika Server.
- Convert extracted content to Markdown.
- Extract the document title.

### Step 4: Normalize

Convert extracted Markdown into ordered, structure-aware `NormalizedElement` objects.

Supported elements:

- Headings.
- Paragraphs.
- Tables.
- Parent-child relationships.
- Original sequence information.

### Step 5: Persist Document Elements

Use an independent transaction to:

- Delete previous chunks and elements.
- Persist the new `DocumentElement` records.

### Step 6: Chunk

Group normalized elements into token-bounded `ChunkDraft` objects.

Current configuration:

| Parameter | Value |
|---|---:|
| Target chunk size | 700 tokens |
| Chunk overlap | 100 tokens |
| Minimum chunk size | 150 tokens |
| Maximum chunk size | 1,000 tokens |

### Step 7: Embedding

Generate embeddings using `EmbeddingService.embed()`.

Current configuration:

| Parameter | Value |
|---|---:|
| Model | Previously BGE-M3; now Qwen3-Embedding-0.6B |
| Model format | GGUF F16 |
| Embedding dimensions | 1,024 |
| Batch size | 32 texts |
| Maximum characters per text | 8,000 |
| Retry attempts | 4 |
| Initial retry delay | 3,000 ms |
| Retry strategy | Linear backoff |

Remote embedding calls run outside database transactions.

### Step 8: Persist Chunks

Use an independent transaction to:

- Validate that every embedding contains exactly 1,024 dimensions.
- Persist `DocumentChunk` records.
- Persist pgvector embeddings.
- Persist `DocumentChunkElement` relationships.

### Step 9: Complete

- Mark the document COMPLETED.
- Mark the processing job COMPLETED.
- Set the processed document version as current.
- Set previous versions of the same logical document as non-current.

### Failure Handling

- Tika and embedding remote calls execute outside database transactions.
- Each persistence and status update uses its own `TransactionTemplate`.
- A failure does not roll back previously committed element records.
- On failure, persist FAILED status on both document and job.
- Store the error message, trimmed to a maximum of 4,000 characters.

---

# 3. Mandatory Repository Inspection

Before modifying code, inspect the existing repository.

Identify:

1. `KnowledgeProcessingService`.
2. `EmbeddingService`.
3. Embedding controller and HTTP client.
4. Current Jinfer integration.
5. `application.yml` and relevant configuration properties.
6. Document chunking implementation.
7. Token counting implementation.
8. Document processing job management.
9. Transaction boundaries.
10. Retry implementation.
11. MinIO integration.
12. Existing PostgreSQL and pgvector mappings.
13. Existing Dockerfile and Docker Compose configuration for `sky-inference`.
14. Existing Jinfer model-loading configuration.
15. Existing application metrics and logging.

Do not assume that the embedding implementation runs Jinfer directly inside the Knowledge Base application.

The current architecture uses a separate Docker inference server. Spring Boot communicates with that server through HTTP.

Clearly distinguish between:

- Knowledge Base application configuration.
- Inference server configuration.
- Jinfer runtime configuration.

Do not apply embedded Jinfer configuration properties to the HTTP client unless the actual implementation supports them.

Before making changes, provide a concise assessment of the existing implementation and identify the expected bottlenecks.

---

# 4. Optimize Embedding Batch Processing

The current batch size is 32 texts per request.

This is potentially too aggressive for a CPU-only inference server running Qwen3-Embedding-0.6B F16.

Implement configurable batch processing.

Initial recommended configuration:

```yaml
document-processing:
  embedding:
    batch-size: 4
    max-concurrent-requests: 1
    max-input-tokens: 1000
    timeout: 300s
    retry:
      max-attempts: 3
      initial-delay: 3s
      multiplier: 2
```

These values are initial benchmarking parameters, not permanent performance limits.

Requirements:

1. Start with batch size 4.
2. Support configurable batch size.
3. Allow benchmarking batch sizes 4, 8, 16, and 32.
4. Limit concurrent embedding requests to 1 initially.
5. Prevent CPU oversubscription.
6. Preserve the original ordering of chunks and embeddings.
7. Ensure every embedding is associated with the correct source chunk.
8. Avoid creating unnecessary intermediate copies of large collections.
9. Do not send all document chunks in one HTTP request.
10. Handle partial batch failures safely.

Use traditional Java loops for complex batching logic, branching, and side-effect-heavy processing.

Use Streams only for simple transformations.

## Batch Processing Example

If a document contains 1,200 chunks and the batch size is 4:

```text
Total chunks: 1,200
Batch size: 4
Total batches: 300
```

Each batch should be submitted independently and its results validated before being associated with the corresponding chunk records.

Use the existing embedding API contract.

If the server supports OpenAI-compatible embeddings, use an input array:

```json
{
  "model": "qwen3-embedding-0.6b",
  "input": [
    "First document chunk",
    "Second document chunk",
    "Third document chunk",
    "Fourth document chunk"
  ]
}
```

Do not assume the server's exact model identifier. Inspect the existing model configuration and `/v1/models` implementation.

---

# 5. Replace Character-Based Input Limits with Token-Based Validation

The current implementation limits each text to 8,000 characters.

This is not a reliable way to control model input size.

Character count does not correspond directly to token count.

Requirements:

1. Use the tokenizer compatible with Qwen3-Embedding-0.6B where practical.
2. Count actual tokens before submitting embedding requests.
3. Enforce a configurable maximum input token count.
4. Initially use 1,000 tokens per chunk.
5. Preserve meaningful document structure.
6. Avoid splitting sentences unnecessarily.
7. Never silently truncate meaningful document content.
8. Record token counts for each chunk and embedding request.

If the existing chunker already provides accurate token counts, reuse it rather than introducing another tokenizer unnecessarily.

If the exact model tokenizer is not available, document the approximation and its limitations.

Do not assume that the 32K model context window should be used for every embedding request.

The goal is predictable CPU inference latency, not maximizing the context window.

---

# 6. Jinfer CPU Runtime Optimization

The inference server uses:

- JDK 25.
- Jinfer 0.3.1.
- Qwen3-Embedding-0.6B F16.
- CPU-only execution.

Inspect the current Jinfer runtime and determine whether CPU acceleration is configured correctly.

## 6.1 Vector API

The existing Maven configuration includes:

```xml
<dependency>
    <groupId>com.qxotic</groupId>
    <artifactId>jam-vector</artifactId>
    <scope>runtime</scope>
</dependency>
```

Verify that the JVM is launched with:

```bash
--add-modules jdk.incubator.vector
--enable-native-access=ALL-UNNAMED
```

Inspect the actual Docker startup command.

Do not assume Maven plugin `jvmArguments` are automatically applied when launching an executable JAR through `java -jar`.

## 6.2 Native Acceleration

Investigate whether `jam-native` is available and compatible with Jinfer 0.3.1.

If compatible, benchmark it against the existing `jam-vector` implementation.

Potential dependency:

```xml
<dependency>
    <groupId>com.qxotic</groupId>
    <artifactId>jam-native</artifactId>
    <scope>runtime</scope>
</dependency>
```

Do not add this dependency blindly.

Verify:

- Artifact availability.
- Version compatibility.
- Native library availability.
- Docker operating system compatibility.
- Native library loading.
- Actual runtime backend selection.

If both native and Vector API implementations are available, benchmark the actual performance difference.

Do not claim acceleration without measurements.

## 6.3 CPU Thread Management

Inspect how Jinfer controls CPU thread usage.

If supported, expose CPU thread configuration through the inference server's configuration.

Do not invent unsupported Jinfer properties.

Benchmark appropriate thread counts based on the actual Docker CPU allocation.

Avoid creating multiple model instances or excessive concurrent inference requests without measuring memory consumption and CPU utilization.

---

# 7. Context Capacity Optimization

Inspect the actual Jinfer embedding model configuration.

If the server uses `JinferEmbeddingModel` directly, investigate:

`context-capacity`

Jinfer's embedding configuration supports context capacity to control the embedding batching window and maximum text length.

Test:

| Experiment | Context Capacity |
|---|---:|
| A | 2,048 |
| B | 4,096 |
| C | 8,192 |

Start with 2,048.

Measure:

- Throughput.
- Peak RAM.
- Inference latency.
- Failure rate.

Do not increase context capacity without verifying available memory.

IMPORTANT:

If the model runs in a separate HTTP inference server, modify the actual server-side Jinfer runtime configuration.

Do not assume that changing Spring AI client configuration in the Knowledge Base application changes the remote inference server.

---

# 8. Improve Retry and Timeout Handling

The current embedding retry policy uses four attempts with a fixed linear delay.

Improve it carefully.

Recommended initial retry policy:

```yaml
document-processing:
  embedding:
    retry:
      max-attempts: 3
      initial-delay: 3s
      multiplier: 2
      max-delay: 30s
```

Expected retry schedule:

```text
Attempt 1: Immediate
Attempt 2: 3 seconds
Attempt 3: 6 seconds
```

Add randomized jitter where appropriate.

## Error Classification

Implement different handling for:

| Error | Handling |
|---|---|
| HTTP 400 | Do not retry |
| HTTP 401 | Do not retry |
| HTTP 403 | Do not retry |
| HTTP 429 | Retry with backoff |
| HTTP 502 | Retry with backoff |
| HTTP 503 | Retry with backoff |
| HTTP 504 | Retry with backoff |
| Connection reset | Limited retry |
| Read timeout | Check request completion state before retry |
| Invalid vector dimensions | Do not retry automatically |

Important:

A client-side read timeout does not necessarily mean that the inference server stopped processing the request.

Blindly retrying timed-out requests can create duplicate workloads and increase CPU saturation.

## Idempotency

Implement batch-level request identification.

Each embedding batch should have a stable identifier.

Requirements:

- Prevent duplicate persistence.
- Avoid submitting the same batch concurrently.
- Track batch completion.
- Support safe retry.
- Preserve chunk ordering.

If the inference API does not support idempotency keys, implement the protection in the Knowledge Base processing service.

---

# 9. Add Embedding Performance Metrics

Instrument the embedding pipeline.

At minimum, collect:

- Total embedding requests.
- Successful embedding requests.
- Failed embedding requests.
- Total embedded texts.
- Total embedded tokens.
- Embedding batch size.
- Embedding duration.
- Queue size.
- Active embedding requests.
- Retry count.
- Timeout count.
- Model name.
- Model quantization.

Calculate:

```text
tokens_per_second =
    total_embedded_tokens / embedding_duration_seconds
```

For each batch, log:

```text
documentId
processingJobId
batchId
batchIndex
batchSize
totalInputTokens
maximumInputTokens
embeddingDurationMs
tokensPerSecond
retryCount
modelName
modelQuantization
```

Avoid logging complete document content or sensitive extracted text.

Expose appropriate metrics through the existing Spring Boot Actuator and Micrometer infrastructure if available.

Do not introduce a new monitoring platform unnecessarily.

---

# 10. Protect Existing Document Versions and Chunks

This is a critical data-integrity requirement.

The current implementation deletes previous chunks and elements before new embedding generation completes.

This creates a risk:

1. An existing document version is successfully indexed.
2. A reprocessing operation starts.
3. Existing chunks and elements are deleted.
4. New embedding generation fails.
5. The document becomes FAILED.
6. The previously usable document content is no longer available for RAG.

This behavior must be corrected.

## Recommended Staging Strategy

Implement a processing generation or staging mechanism.

A generation ID can be associated with the processing operation.

Suggested conceptual flow:

```text
Existing active generation
        |
        v
New processing generation
        |
        v
Extract and normalize
        |
        v
Persist staged elements
        |
        v
Generate embeddings
        |
        v
Persist staged chunks
        |
        v
Validate all vectors and chunks
        |
        v
Atomic activation transaction
        |
        +--> Activate new generation
        |
        +--> Deactivate previous generation
```

The existing active generation must remain available to RAG until the new generation is fully processed.

Requirements:

1. Never delete active chunks before new embedding generation succeeds.
2. Persist new document elements separately from the active generation.
3. Persist new chunks separately from the active generation.
4. Validate all embeddings before activation.
5. Activate the new generation only after successful completion.
6. Deactivate the previous generation in the same database transaction.
7. Keep failed generations available for diagnosis or cleanup.
8. Ensure RAG retrieves only the active successful generation.

Consider two implementation options:

- Add a `processing_run_id` or generation identifier to document elements and chunks.
- Create a separate `KnowledgeDocument` version for each processing run.

Choose the simplest solution consistent with the existing document-versioning design.

Do not introduce a redundant entity if the existing versioning model already provides the necessary isolation.

## Atomic Activation

The final activation must happen in one transaction:

1. Validate that all expected chunks exist.
2. Validate all embedding dimensions.
3. Mark the new generation active.
4. Mark the previous generation inactive.
5. Update `KnowledgeDocument.isCurrent` where appropriate.
6. Mark the document COMPLETED.
7. Mark the processing job COMPLETED.

If any operation fails, the previous active generation must remain usable.

---

# 11. Document Processing Transaction Boundaries

Preserve the current architecture where remote calls run outside database transactions.

The following operations must remain outside database transactions:

- MinIO download.
- Apache Tika extraction.
- Remote embedding requests.

Database transactions should remain short and focused.

Review the current transaction boundaries to ensure:

- No transaction remains open during long-running inference.
- Element persistence is independent.
- Chunk persistence is independent.
- Status updates are independent.
- Final activation is atomic.

Do not wrap the entire document processing pipeline in one large transaction.

---

# 12. Asynchronous Processing

Ensure the document upload API does not wait for the complete extraction and embedding pipeline.

The upload endpoint should:

1. Validate the file.
2. Validate project authorization.
3. Store the original file in MinIO.
4. Create the document record.
5. Create the processing job.
6. Return HTTP 202 Accepted.

The background worker should perform the actual processing.

Use the existing asynchronous processing infrastructure.

Do not introduce a new message broker or queue unless necessary.

Ensure:

- Processing jobs are durable.
- Failed jobs can be retried.
- Duplicate processing is prevented.
- Worker concurrency is configurable.
- CPU-intensive inference does not overwhelm the server.
- Processing status is updated correctly.

---

# 13. Benchmarking Requirements

Implement a repeatable embedding benchmark.

Use the same PDF and extracted chunks for each experiment.

Benchmark the following configurations:

| Test | Model | Batch Size | Context Capacity |
|---|---|---:|---:|
| A | F16 | 4 | 2,048 |
| B | F16 | 8 | 2,048 |
| C | F16 | 16 | 2,048 |
| D | F16 | 32 | 2,048 |
| E | F16 | 4 | 4,096 |
| F | F16 | 8 | 4,096 |
| G | Q8_0 | 8 | 2,048 |

Q8_0 is a benchmark alternative, not a mandatory replacement for F16.

Compare:

- Total processing duration.
- Average batch duration.
- Maximum batch duration.
- Tokens per second.
- Peak memory usage.
- CPU utilization.
- Timeout count.
- Retry count.
- Semantic retrieval quality.

Do not compare only total request duration.

Use the same input texts and model settings wherever possible.

For quantization comparison, evaluate retrieval quality using a representative set of Indonesian and English questions and known relevant document passages.

Do not assume that F16 and Q8_0 produce identical embeddings.

---

# 14. Recommended Configuration Properties

Expose configurable application properties.

Example:

```yaml
document-processing:
  chunking:
    target-tokens: 700
    overlap-tokens: 100
    minimum-tokens: 150
    maximum-tokens: 1000

  embedding:
    batch-size: 4
    max-input-tokens: 1000
    max-concurrent-requests: 1
    timeout: 300s

    retry:
      max-attempts: 3
      initial-delay: 3s
      multiplier: 2
      max-delay: 30s

    benchmark:
      enabled: false
```

These are application-level configuration properties.

Do not present them as native Jinfer properties unless the existing Jinfer implementation explicitly supports them.

Validate configuration values at startup.

Use appropriate configuration property classes and Bean Validation annotations if consistent with the existing project.

---

# 15. Testing Requirements

Write tests following existing project conventions.

Use Mockito for service-layer unit tests.

Do not use `@SpringBootTest` for ordinary unit tests.

Test the following scenarios:

## Batch Processing

- Empty chunk list.
- One chunk.
- Multiple chunks.
- Partial final batch.
- Batch ordering.
- Batch failure.
- Invalid embedding response.
- Wrong vector dimensions.

## Retry Handling

- Successful first attempt.
- Successful retry.
- Maximum retry exceeded.
- Non-retryable HTTP error.
- Read timeout.
- Connection reset.
- Duplicate batch prevention.

## Transaction Integrity

- Successful staged processing.
- Failed embedding.
- Failed chunk persistence.
- Failed activation.
- Previous active generation remains usable after failure.
- Atomic version activation.

## Performance and Configuration

- Configurable batch size.
- Maximum input token enforcement.
- Maximum concurrency enforcement.
- Metrics collection.
- Timeout configuration.

Use PostgreSQL integration tests where required for transaction and pgvector behavior.

Do not assume H2 supports PostgreSQL-specific functionality.

---

# 16. Implementation Workflow

Follow this workflow.

## Phase 1: Inspection

Inspect the actual source code and report:

- Current embedding implementation.
- Actual model identifier.
- Current request and response contracts.
- Existing batching implementation.
- Current concurrency behavior.
- Current retry implementation.
- Current timeout configuration.
- Jinfer runtime configuration.
- Existing Docker CPU and memory limits.
- Existing transaction boundaries.
- Existing document versioning and active-chunk behavior.

Do not make changes before understanding the existing implementation.

## Phase 2: Optimization Design

Prepare a concise implementation plan covering:

1. Batch processing changes.
2. Token-based validation.
3. CPU runtime optimization.
4. Timeout and retry changes.
5. Metrics and observability.
6. Staging and atomic activation.
7. Benchmark strategy.

Identify changes required in the Knowledge Base application separately from changes required in the inference server.

## Phase 3: Implementation

Implement the changes incrementally.

Do not rebuild unrelated modules.

Preserve existing API compatibility unless a change is explicitly required.

Do not replace existing dependencies without justification.

## Phase 4: Verification

Run:

- Maven compilation.
- Relevant unit tests.
- Repository integration tests where appropriate.
- Embedding endpoint tests.
- Batch processing tests.
- Transaction integrity tests.
- Docker startup verification.
- Actual PDF processing benchmark.

Report all test results accurately.

Do not claim performance improvements without measured results.

---

# 17. Strict Restrictions

- Do not create a new Spring Boot project.
- Do not replace the existing architecture.
- Do not change the LFM chat inference implementation unnecessarily.
- Do not replace Qwen3-Embedding-0.6B without benchmark evidence.
- Do not perform fine-tuning as a proposed solution to inference timeout.
- Do not assume F16 is the cause without measurements.
- Do not hardcode batch size.
- Do not use character count as the sole input-length control.
- Do not send an entire PDF or all document chunks in a single embedding request.
- Do not allow unlimited concurrent CPU inference requests.
- Do not retry read timeouts blindly.
- Do not hold database transactions open during remote calls.
- Do not delete previously active chunks before successful replacement.
- Do not change unrelated database entities or project authorization logic.
- Do not introduce Flyway or Liquibase.
- Do not introduce unnecessary dependencies.
- Do not invent unsupported Jinfer configuration properties.

---

# 18. Final Deliverables

At completion, provide:

1. Root cause analysis of the embedding timeout.
2. Changes made to `KnowledgeProcessingService`.
3. Changes made to `EmbeddingService`.
4. Changes made to the inference server, if necessary.
5. Final batch processing implementation.
6. Token counting implementation.
7. Final retry and timeout configuration.
8. CPU acceleration configuration.
9. Staging and atomic activation implementation.
10. Metrics and logging implementation.
11. Benchmark results.
12. Unit and integration test results.
13. Docker configuration changes, if any.
14. Remaining performance bottlenecks.
15. Recommended production configuration.

Clearly distinguish between:

- Changes implemented.
- Changes verified.
- Benchmark results actually measured.
- Recommendations that still require testing.

## Final Instruction

The primary goal is to improve the reliability and throughput of the existing CPU-based Qwen3-Embedding-0.6B F16 inference pipeline.

Prioritize controlled batching, CPU runtime optimization, request management, and transaction safety.

Do not immediately change model quantization or perform fine-tuning.

First identify the actual bottleneck, then implement the smallest effective changes and validate them through repeatable benchmarks.

Work directly in the existing repositories and preserve all existing business functionality.