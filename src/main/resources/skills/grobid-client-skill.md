# Skill: Implement Production-Grade GROBID Client for Spring Boot

## 1. Goal

Implement a production-oriented GROBID client layer for an existing Java Spring Boot RAG project.

This skill ONLY covers communication with the external GROBID Docker service.

It must support:

- Calling GROBID `POST /api/processFulltextDocument`
- Sending PDF as `multipart/form-data`
- Receiving TEI XML as `String`
- Configuration through `application.yml`
- Timeout handling
- HTTP status classification
- GROBID error response classification
- Retryable vs non-retryable exception distinction
- GROBID liveness/readiness checks
- Structured logging
- Clear abstraction boundary for upper ingestion services

This skill MUST NOT implement:

- TEI XML parsing into Java academic document objects
- Chunking
- Embedding
- pgvector
- Kafka consumption logic
- MinIO persistence logic
- RAG retrieval or LLM logic

The output of this layer is TEI XML only.

---

## 2. Context

The existing project is a Java/Spring Boot RAG system.

Relevant architecture:

```text
PDF uploaded to MinIO
        ↓
Kafka ingestion consumer
        ↓
GrobidClient
        ↓
GROBID Docker service
        ↓
TEI XML String
        ↓
GrobidDocumentParser   // NOT part of this skill
```

GROBID is already started through Docker and is reachable through HTTP, typically:

```text
http://localhost:8070
```

Do not start or manage Docker from Java code.

---

## 3. Required Package Structure

Prefer the following structure unless the existing project has a strong established package convention:

```text
infrastructure/
└── grobid/
    ├── GrobidClient.java
    ├── GrobidHttpClient.java
    ├── GrobidProperties.java
    ├── GrobidResponse.java
    ├── GrobidHealth.java
    └── exception/
        ├── GrobidException.java
        ├── GrobidTimeoutException.java
        ├── GrobidUnavailableException.java
        ├── GrobidProcessingException.java
        └── GrobidInvalidResponseException.java
```

If the project already has an `infrastructure/client` or `client` convention, adapt the package path but preserve the same responsibility boundaries.

---

## 4. Responsibility Boundary

### `GrobidClient`

Defines the capabilities exposed to upper layers.

Recommended API:

```java
public interface GrobidClient {

    GrobidResponse processFulltext(byte[] pdfBytes, String fileName);

    boolean isAlive();

    GrobidHealth health();
}
```

Do not expose HTTP-client-specific classes in this interface.

---

### `GrobidHttpClient`

Concrete implementation responsible for HTTP communication only.

Responsibilities:

- Build multipart request
- Send PDF as form field `input`
- Call `/api/processFulltextDocument`
- Receive XML response
- Measure request duration
- Validate response body
- Map HTTP/GROBID failures to domain-specific exceptions
- Implement limited retry behavior where appropriate
- Call `/api/isalive`
- Call `/api/health`
- Log failures and latency

It MUST NOT:

- Parse TEI into domain objects
- Store TEI in MinIO
- Update document status in PostgreSQL
- Produce Kafka messages

---

### `GrobidProperties`

Use Spring configuration binding, e.g. `@ConfigurationProperties`.

Suggested configuration:

```yaml
grobid:
  base-url: http://localhost:8070
  connect-timeout: 3s
  read-timeout: 120s
  max-retries: 3
  retry-backoff: 5s
```

Suggested fields:

```java
String baseUrl;
Duration connectTimeout;
Duration readTimeout;
int maxRetries;
Duration retryBackoff;
```

Validate invalid configuration at startup where reasonable.

---

### `GrobidResponse`

Successful fulltext-processing response.

Recommended form:

```java
public record GrobidResponse(
        String teiXml,
        long durationMs
) {}
```

The core response data is still TEI XML as `String`.

Do not deserialize TEI XML here.

---

### `GrobidHealth`

Represent readiness/engine-pool health returned by `/api/health`.

Do not over-model undocumented fields. Keep only fields actually returned by the running GROBID version or model unknown fields safely.

Where available, preserve values such as:

```text
active
idle
max
```

The client should tolerate additional fields in future GROBID versions.

---

## 5. HTTP Endpoint Behavior

### Fulltext

Use:

```text
POST /api/processFulltextDocument
```

Request:

```text
Content-Type: multipart/form-data
form field: input
value: PDF binary
```

Response on success:

```text
Content-Type: application/xml
body: TEI XML
```

Return the XML as a Java `String` inside `GrobidResponse`.

Do not convert to JSON or Markdown in this layer.

---

### Liveness

Use:

```text
GET /api/isalive
```

Map successful response to `boolean`.

This should be lightweight.

---

### Readiness / Pool Health

Use:

```text
GET /api/health
```

Deserialize the JSON safely into `GrobidHealth` or a small response DTO.

This method is primarily for diagnostics/Actuator integration and should not be required before every PDF request.

DO NOT call `/api/health` before each fulltext request.

---

## 6. HTTP Status Handling

Classify responses explicitly.

### 2xx success

#### `200`

- Response body must contain non-blank XML
- Return `GrobidResponse`

#### `204`

Interpret as processing produced no usable content.

Throw a non-retryable processing/invalid-response exception.

Do not retry blindly.

---

### `400`

Treat as invalid request/input.

Non-retryable.

Throw `GrobidProcessingException` or another clearly non-retryable subtype.

---

### `503`

Usually indicates GROBID engine/parser pool exhaustion or temporary unavailability.

Retryable.

Throw `GrobidUnavailableException` when retry is exhausted.

Backoff should be configurable; default around 5 seconds is acceptable.

Avoid infinite retry.

---

### `500`

Do NOT classify all `500` responses the same.

Inspect the response body/error payload when available.

Recognize GROBID-style error categories where possible, including examples such as:

```text
BAD_INPUT_DATA
NO_BLOCKS
TOO_MANY_BLOCKS
TOO_MANY_TOKENS
TIMEOUT
TAGGING_ERROR
PARSING_ERROR
PDFALTO_CONVERSION_FAILURE
GENERAL
```

Suggested policy:

#### Usually non-retryable

```text
BAD_INPUT_DATA
NO_BLOCKS
TOO_MANY_BLOCKS
TOO_MANY_TOKENS
PARSING_ERROR
PDFALTO_CONVERSION_FAILURE
```

#### Potentially retryable

```text
TIMEOUT
GENERAL
```

`TAGGING_ERROR` may be treated conservatively; retry a small number of times at most, then fail.

Do not hard-code behavior without preserving the original error code/message in the thrown exception.

---

## 7. Exception Hierarchy

Base exception:

```java
public class GrobidException extends RuntimeException {
    ...
}
```

Recommended subclasses:

### `GrobidTimeoutException`

Use for:

- connect timeout
- read timeout
- GROBID `TIMEOUT` processing failure

Mark as retryable at the client/retry-policy layer.

---

### `GrobidUnavailableException`

Use for:

- connection refused
- DNS/network unreachable
- HTTP 503
- service temporarily unavailable

Retryable.

---

### `GrobidProcessingException`

Use when GROBID received the document but could not process it due to document/content problems.

Examples:

```text
BAD_INPUT_DATA
NO_BLOCKS
TOO_MANY_TOKENS
PARSING_ERROR
```

Usually non-retryable.

Recommended fields:

```java
String grobidErrorCode;
Integer httpStatus;
boolean retryable;
```

---

### `GrobidInvalidResponseException`

Use when:

- `200` but body is null/blank
- body is not plausibly XML
- response encoding/content is invalid

Usually non-retryable unless there is evidence of transient corruption.

---

## 8. Retry Policy

Retries MUST be limited and only apply to transient failures.

Retry examples:

```text
503
connection refused
connect timeout
read timeout
GROBID TIMEOUT
```

Do not retry obvious permanent document failures.

Recommended defaults:

```text
maxRetries = 3
backoff = 5s
```

If the project already uses Spring Retry, Resilience4j, or another standard retry mechanism, reuse it instead of inventing a custom retry framework.

If no retry library is present, keep retry logic small and explicit.

Avoid duplicated retry layers. If Kafka already retries the whole ingestion message, define which failures are retried locally by `GrobidClient` and which bubble up to Kafka retry.

Recommended division:

```text
GrobidClient:
- short transient HTTP retry
- e.g. 2-3 attempts

Kafka ingestion retry:
- coarse-grained job retry after client retries are exhausted
```

Never create unbounded nested retries.

---

## 9. Concurrency

GROBID Docker has its own internal concurrency/parser pool.

The client MUST NOT create an uncontrolled thread pool internally.

Concurrency should primarily be controlled by the Kafka consumer / ingestion worker.

`GrobidHttpClient` should remain thread-safe and reusable as a Spring singleton.

Do not serialize all requests with `synchronized`.

Do not create a new HTTP client per request.

Reuse a configured HTTP client with connection pooling where supported.

---

## 10. Logging

Use structured, useful logs.

For successful calls, avoid logging the full TEI XML.

Recommended fields:

```text
operation=processFulltext
fileName
pdfSize
httpStatus
durationMs
attempt
```

On failure, also include:

```text
grobidErrorCode
retryable
exceptionType
```

If upper layers place these values in MDC, preserve:

```text
traceId
documentId
ingestionTaskId
```

NEVER log raw PDF bytes.

Avoid logging the entire TEI XML because it can be very large.

---

## 11. Security and Input Validation

Before sending:

- Reject null/empty PDF bytes
- Require a non-blank filename
- Ensure filename ends with `.pdf` where this client is PDF-specific

Do not trust original filenames for filesystem paths.

This client sends in-memory bytes; storage naming belongs to MinIO/storage layer, not this layer.

---

## 12. HTTP Client Choice

Use the HTTP technology already established in the project if one exists.

Preferred options in Spring Boot:

```text
RestClient
WebClient
Java HttpClient
```

Do not introduce Feign solely for this one client unless the project already standardizes on OpenFeign.

The implementation must support multipart upload and configurable timeouts.

If using synchronous ingestion code, a synchronous HTTP client is acceptable and simpler.

---

## 13. Testing Requirements

### Unit tests

Test status/error mapping without requiring a real GROBID container.

Cover at minimum:

```text
200 + valid XML → success
200 + empty body → GrobidInvalidResponseException
204 → non-retryable failure
400 → non-retryable processing failure
503 → retryable/unavailable
500 + BAD_INPUT_DATA → non-retryable
500 + TIMEOUT → retryable timeout
connection timeout → GrobidTimeoutException
connection refused → GrobidUnavailableException
```

Use the project's existing mock HTTP server approach if available.

---

### Integration test

If GROBID Docker is available in local/dev integration testing:

- Send one real test PDF
- Assert returned TEI is non-empty
- Assert it contains a TEI root element
- Do NOT assert exact full XML because model/version output may change

Example assertions:

```text
response.teiXml() is not blank
response.teiXml() contains "<TEI"
```

Namespace/prefix differences must be tolerated.

---

## 14. Acceptance Criteria

The implementation is complete when upper-layer code can do this:

```java
GrobidResponse response = grobidClient.processFulltext(pdfBytes, fileName);
String teiXml = response.teiXml();
```

and the client provides:

- clean configuration
- reusable thread-safe HTTP client
- multipart PDF request
- TEI XML string response
- timeout handling
- explicit HTTP status handling
- retryable/non-retryable exception classification
- bounded retries
- `/api/isalive`
- `/api/health`
- useful logs
- unit tests
- at least one real-container integration test if feasible

The upper ingestion service must not need to understand low-level HTTP exceptions.

---

## 15. Important Non-Goals

Do NOT implement these in this skill:

```text
TEI parsing
AcademicDocument
PaperCleaner
SectionAwareChunker
Embedding
pgvector
MinIO upload of TEI
Kafka producer/consumer logic
parse_quality
RAG
```

Those belong to separate components.

The boundary is strictly:

```text
PDF bytes
   ↓
GrobidClient
   ↓
GROBID HTTP service
   ↓
TEI XML String
```

---

## 16. Implementation Principle

Prefer small, explicit code over unnecessary framework abstraction.

The final design should make the next stage straightforward:

```text
GrobidClient
      ↓
GrobidResponse.teiXml()
      ↓
GrobidDocumentParser
      ↓
AcademicDocument
```

Do not merge `GrobidClient` and `GrobidDocumentParser` into one class.
