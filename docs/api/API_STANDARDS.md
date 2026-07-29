# DevFlow API Standards and Guidelines

> **Version:** 1.0.0  
> **Status:** Final / Architecture Review Board (ARB) Approved  
> **Classification:** Internal & Partner Engineering  

---

## 1. Purpose

This document establishes the official architectural standards for all HTTP REST APIs within **DevFlow**—an AI-First Engineering Intelligence & Delivery Platform. 

In a multi-client ecosystem comprising a Next.js web application, a local CLI tool, a VS Code extension, and third-party integrations, API consistency is paramount. Adhering to these standards ensures:
*   **Uniform Developer Experience (DX):** Developers (both internal and external) can interact with any Bounded Context (Identity, Project Management, AI Engine, Repository Intelligence) using predictable patterns and shared schemas.
*   **High Maintainability:** The Modular Monolith backend (Spring Boot 3.x, Spring Modulith) can evolve seamlessly. Boundary verification tools and automated testing can inspect compliance statically.
*   **Structured Backward Compatibility:** API versioning, sunset schedules, and client contracts are strictly managed, reducing integration failures.
*   **System Scalability:** By standardizing pagination, filtering, rate limiting, and idempotency, we safeguard database performance (PostgreSQL) and caching efficiency (Redis 7).

---

## 2. API Design Philosophy

DevFlow's API architectural choices are based on five primary design pillars:

```
                    ┌────────────────────────────────────────┐
                    │          REST-First Paradigm           │
                    └───────────────────┬────────────────────┘
                                        │
         ┌──────────────────────────────┼──────────────────────────────┐
         ▼                              ▼                              ▼
┌──────────────────┐          ┌──────────────────┐          ┌──────────────────┐
│Resource-Oriented │          │    Stateless     │          │   Predictable    │
│    Contracts     │          │  Communication   │          │    Contracts     │
└──────────────────┘          └──────────────────┘          └──────────────────┘
```

1.  **REST-First Paradigm:** We treat APIs as first-class products. Resource relationships and JSON contracts are designed, reviewed, and finalized before any backing controller or database schema is implemented.
2.  **Resource-Oriented APIs:** The API URL space is modeled as a hierarchy of resources rather than a set of procedure calls. Actions are mapped to standard HTTP verbs acting upon these nouns.
3.  **Stateless Communication:** Each request must carry all the identity, authentication context, and payload data required to process the operation. The backend holds no session state on behalf of specific API clients.
4.  **Predictable Contracts:** Request and response schemas must be consistent across Bounded Contexts. A successful response envelope from the AI Engine context looks structurally identical to one from Project Management.
5.  **Consistency over Convenience:** While a custom shorthand endpoint might save a few bytes of traffic, we prioritize long-term system predictability and automatic SDK generation (TypeScript, Go, Java) by strictly following standard REST paths.

---

## 3. Resource Naming Conventions

All URIs must follow strict naming rules to maintain cleanliness and readability across the platform.

### Plural Resources
Resource names in paths must always be **nouns** and must always be **plural**.
*   **Correct:** `/api/v1/projects`, `/api/v1/tasks`, `/api/v1/organizations`
*   **Incorrect:** `/api/v1/project`, `/api/v1/getTask`, `/api/v1/organization`

### Casing rules
*   **URIs (Paths):** Must be strictly lowercase and use **kebab-case** for multi-word resources.
    *   **Correct:** `/api/v1/chat-sessions`, `/api/v1/workspace-memberships`
    *   **Incorrect:** `/api/v1/chatSessions`, `/api/v1/workspace_memberships`
*   **Query Parameters:** Must use **camelCase**.
    *   **Correct:** `/api/v1/tasks?assigneeId=usr_123&includeArchived=true`
    *   **Incorrect:** `/api/v1/tasks?assignee_id=usr_123&include_archived=true`

### Collection vs. Member Endpoints
*   **Collection Endpoints:** Refer to a group of resources.
    *   `GET /api/v1/projects` — List projects.
    *   `POST /api/v1/projects` — Create a project.
*   **Member Endpoints:** Refer to a specific resource identified by its unique public ID.
    *   `GET /api/v1/projects/{projectId}` — Retrieve project details.
    *   `PATCH /api/v1/projects/{projectId}` — Update project attributes.
    *   `DELETE /api/v1/projects/{projectId}` — Archive/remove the project.

### Action Endpoints (Verbs in URIs)
When an operation does not map cleanly to standard CRUD operations on a resource, an action endpoint is allowed. These must be appended to the resource member using a trailing verb.
*   **Correct:** `POST /api/v1/pull-requests/{prId}/merge`
*   **Incorrect:** `POST /api/v1/merge-pull-request/{prId}`
*   **Correct:** `POST /api/v1/chat-sessions/{sessionId}/summarize`
*   **Incorrect:** `POST /api/v1/summarize-chat-session/{sessionId}`

---

## 4. URI Standards

URIs are structured hierarchically to indicate logical relationships and ownership boundaries. 

### URI Structure Template
```
/api/{version}/{root-resource}/{root-id}/{sub-resource}/{sub-resource-id}
```

### Hierarchy Rules
1.  **Deep Nesting Limit:** Nesting must not exceed two levels (Resource -> Sub-resource). Deeper structures become unwieldy, inflate URI lengths, and tightly couple child lifecycle changes to parents.
    *   **Allowed (Depth = 1):** `/api/v1/projects/{projectId}`
    *   **Allowed (Depth = 2):** `/api/v1/projects/{projectId}/tasks`
    *   **Forbidden (Depth = 3):** `/api/v1/projects/{projectId}/tasks/{taskId}/comments`
    *   **Resolution for Depth 3+:** Promote the deep sub-resource to a top-level root resource, identifying the intermediate parents in query parameters or request bodies:
        *   `GET /api/v1/comments?taskId={taskId}`
        *   `GET /api/v1/tasks/{taskId}/comments` (Valid because `tasks` is now the root resource of the request).

### Multi-Tenancy Scopes
Since DevFlow is a multi-tenant platform, the `Organization` is the primary Bounded Context boundary.
*   For endpoints operating within the scope of a tenant: `/api/v1/organizations/{orgId}/projects`
*   For global user operations: `/api/v1/users/me` or `/api/v1/users/{userId}/workspace-memberships`

---

## 5. HTTP Method Standards

DevFlow maps CRUD and functional operations strictly to HTTP methods.

| Method | Safe | Idempotent | Usage |
| :--- | :--- | :--- | :--- |
| **GET** | Yes | Yes | Retrieve a resource representation. No side effects. |
| **POST** | No | No | Create a new resource, or execute an action/command (e.g., `/merge`). |
| **PUT** | No | Yes | Replace an entire resource. If the resource doesn't exist, returns 404. |
| **PATCH** | No | No* | Partially update an existing resource. (*Can be made idempotent, see Section 14). |
| **DELETE** | No | Yes | Remove or archive a resource. Subsequent deletes return 204 or 404. |
| **OPTIONS** | Yes | Yes | Retrieve supported HTTP methods and CORS policies for a URI. |
| **HEAD** | Yes | Yes | Retrieve resource metadata headers (like `Content-Length`) without the body. |

### Method Semantics
*   **GET:** Under no circumstances should a `GET` request modify server state.
*   **POST:** Used for operations that create resources or trigger non-idempotent business logic. Re-running a `POST` creates duplicates unless guarded by an idempotency key.
*   **PUT vs. PATCH:** DevFlow mandates **PATCH** for all partial updates. A client only provides the fields they want to change. **PUT** is reserved for complete replacement of a resource (requiring all mandatory fields in the body).

---

## 6. Request Standards

All incoming API request payloads must adhere to these standard configuration values and formats:

### Payload Encoding
*   **Content-Type:** `application/json`
*   **Encoding:** UTF-8 strictly enforced.
*   **Field Naming Casing:** Must use **camelCase**.
    *   **Correct:** `"taskPriority": "HIGH"`
    *   **Incorrect:** `"task_priority": "HIGH"`, `"TaskPriority": "HIGH"`

### Data Formats
*   **Identifiers (IDs):** Must use public-safe string formats. Database auto-increment integers are strictly forbidden in URIs and payloads. 
    *   Format: String-based prefixed identifiers (e.g., `org_01h8abc123...`) or standard UUIDv4/UUIDv7.
*   **Date/Time:** Must use **ISO-8601** formatting with UTC offset (`Z`).
    *   Format: `YYYY-MM-DDTHH:mm:ss.SSSZ`
    *   Example: `"createdAt": "2026-07-29T11:53:02.123Z"`
*   **Booleans:** Must be native JSON boolean types, not integers or strings.
    *   **Correct:** `"isArchived": true`
    *   **Incorrect:** `"isArchived": "true"`, `"isArchived": 1`
*   **Null Handling:**
    *   Optional fields that are omitted must not be present in the request body (preferred) or can be passed as `null`.
    *   To clear/unset a value, the client must explicitly pass `null`.

---

## 7. Response Standards

To ensure predictable parsing by frontends, SDKs, and mobile clients, all API responses must utilize a unified response envelope. Under no circumstances should raw JSON arrays or arbitrary root keys be returned.

### Successful Response Envelope (Single Item)
```json
{
  "data": {
    "id": "tsk_01h8def456abc",
    "taskKey": "DEVF-42",
    "title": "Implement LLM Context Extraction Flow",
    "status": "IN_PROGRESS",
    "priority": "HIGH",
    "estimation": 5,
    "createdAt": "2026-07-29T06:00:00Z",
    "updatedAt": "2026-07-29T11:53:02Z"
  }
}
```

### Successful Response Envelope (Collection / List)
When returning collections, the array is wrapped in a `data` block, and a sibling `pagination` block is populated:
```json
{
  "data": [
    {
      "id": "tsk_01h8def456abc",
      "taskKey": "DEVF-42",
      "title": "Implement LLM Context Extraction Flow",
      "status": "IN_PROGRESS"
    },
    {
      "id": "tsk_01h8def456xyz",
      "taskKey": "DEVF-43",
      "title": "Set up OpenTelemetry Tracing",
      "status": "TODO"
    }
  ],
  "pagination": {
    "page": 1,
    "limit": 20,
    "totalItems": 42,
    "totalPages": 3
  }
}
```

*Note: For cursor-based pagination, the structure of the pagination metadata changes slightly (see Section 11).*

---

## 8. Error Response Standards

When an error occurs, the server must respond with a standard JSON envelope. It must distinguish between **machine-readable** identifiers (for client code routing) and **human-readable** descriptions (for developer logging or direct UI display).

### Error Envelope Schema
```json
{
  "error": {
    "code": "MACHINE_READABLE_ENUM_STRING",
    "message": "Human-readable summary of the error.",
    "details": [
      {
        "field": "Optional field name where validation failed",
        "issue": "Specific explanation of the failure on this field"
      }
    ],
    "requestId": "correlation-id-or-trace-context",
    "timestamp": "2026-07-29T11:53:02Z"
  }
}
```

### Error Design Principles
1.  **Never leak stack traces:** System exceptions, SQL errors, or internal Java class names (e.g., `NullPointerException`, `PSQLException`) must never escape the API Gateway/Controller layer. They are logged internally with high severity, but the user receives a generic `INTERNAL_SERVER_ERROR`.
2.  **Explicit Request Context:** The `requestId` (matching the `X-Correlation-ID` or W3C Trace header) must be returned in every error envelope, enabling developers to map user complaints directly to server trace logs.

---

## 9. Validation Standards

API-level input validation is split into two categories: **Syntax/Field Validation** and **Business Invariant Validation**.

### 1. Syntax & Field Validation (HTTP 422 Unprocessable Entity)
Validates structural limits (e.g., mandatory fields, string length, regex format matching). 
If one or more fields fail, the system gathers all violations and returns them in a single error payload so the client can show inline form validation messages.

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "One or more fields failed structural validation constraints.",
    "details": [
      {
        "field": "email",
        "issue": "Must be a valid email address."
      },
      {
        "field": "title",
        "issue": "Title cannot be blank and must be between 5 and 255 characters."
      }
    ],
    "requestId": "trace-904caf17-17fe-4472",
    "timestamp": "2026-07-29T11:53:02Z"
  }
}
```

### 2. Business Invariant Validation (HTTP 409 Conflict or 422 Unprocessable Entity)
Validates logical business constraints, state machine transitions, or uniqueness.
*   **Duplicate Resource:** E.g., creating a workspace with a slug that is already taken.
    *   Status Code: `409 Conflict`
    *   Error Code: `ORGANIZATION_SLUG_DUPLICATE`
*   **Invalid State Transition:** E.g., attempting to transition a Task to a Sprint that is already closed.
    *   Status Code: `422 Unprocessable Entity`
    *   Error Code: `INVALID_CYCLE_STATE`

### Localization Readiness
Error `message` and `issue` fields must be formatted using localized strings on the server matching the `Accept-Language` header (defaulting to English `en-US`). The `code` remains an immutable uppercase string constant (e.g., `EMAIL_ALREADY_EXISTS`) so client applications can handle custom translations locally.

---

## 10. HTTP Status Code Standards

DevFlow APIs utilize standard HTTP status codes to communicate result states.

### 2xx Success Status Codes
*   **200 OK:** Request was successful and the response contains the requested payload.
*   **201 Created:** Request was successful, a new resource was created, and its representation is returned. Must include a `Location` header pointing to the new resource.
*   **202 Accepted:** Request has been accepted for processing, but the processing is not yet complete (used for asynchronous, long-running operations).
*   **204 No Content:** Request was successful but there is no payload to return (commonly used for `DELETE` operations or status updates without bodies).

### 4xx Client Error Status Codes
*   **400 Bad Request:** General syntax error in query parameters or JSON body (e.g., malformed JSON syntax).
*   **401 Unauthorized:** Missing or invalid credentials (authentication token failed).
*   **403 Forbidden:** Authenticated user does not possess the permissions/roles required to perform the action on the resource.
*   **404 Not Found:** The resource specified in the URI does not exist. (Note: To protect tenant boundaries, return 404 instead of 403 if a user requests a resource belonging to another organization they have no access to).
*   **409 Conflict:** The request conflicts with current server state (e.g., unique key violation, concurrent write conflict).
*   **410 Gone:** The resource was deleted permanently and will not be available again.
*   **412 Precondition Failed:** The server does not meet one of the preconditions specified in client headers (used in optimistic locking with `If-Match` / `ETag`).
*   **415 Unsupported Media Type:** The client sent a payload format not supported by the server (e.g., `text/xml` instead of `application/json`).
*   **422 Unprocessable Entity:** The payload syntax is correct, but it contains validation errors or invalid business values.
*   **429 Too Many Requests:** The client has exceeded their rate limit.

### 5xx Server Error Status Codes
*   **500 Internal Server Error:** An unexpected error occurred on the server.
*   **502 Bad Gateway:** The gateway received an invalid response from an upstream microservices/modules layer.
*   **503 Service Unavailable:** The server is temporarily unable to handle the request due to maintenance, database exhaustion, or rate limiting from a third-party API provider (e.g., LLM rate limit exhaustion).
*   **504 Gateway Timeout:** The upstream server took too long to complete (e.g., background LLM inference thread timeout).

---

## 11. Pagination Standards

To prevent database memory overload, collection queries must never return unbounded lists. We use two strategies based on resource semantics:

### Strategy Comparison
| Metric | Offset Pagination | Cursor Pagination (Default) |
| :--- | :--- | :--- |
| **UX Style** | Traditional Page Grid | Infinite Scroll / Feed |
| **Best Used For** | Structured data lists (e.g., Project settings, User profiles). | Continuous or real-time lists (e.g., Activity feeds, Commits list, Chat messages). |
| **Performance** | O(N) database cost (degrades on deep offsets). | O(1) database cost using indexed values. |
| **Drift Risk** | High (items skipped or duplicated if data updates concurrently). | Low (the cursor refers to a fixed point in the sequence). |

### 1. Offset Pagination Structure
*   **Query Parameters:** `page` (1-indexed, default: 1), `limit` (default: 20, max: 100).
*   **Response Pagination Object:**
    ```json
    "pagination": {
      "page": 2,
      "limit": 20,
      "totalItems": 105,
      "totalPages": 6
    }
    ```

### 2. Cursor Pagination Structure
*   **Query Parameters:** `cursor` (opaque base64 string containing sorting value/ID), `limit` (default: 20, max: 100).
*   **Response Pagination Object:**
    ```json
    "pagination": {
      "nextCursor": "eyJsYXN0SWQiOiJ0c2tfNDJkMWYiLCJzb3J0VmFsdWUiOiIyMDI2LTA3LTI5VDEwOjMwOjAwWiJ9",
      "hasMore": true,
      "limit": 20
    }
    ```
    *If there are no further items, `nextCursor` must return `null` and `hasMore` must be `false`.*

---

## 12. Filtering & Sorting

Filtering, sorting, and searching are controlled through query parameters.

### Filtering Conventions
*   **Exact Matches:** Use field names directly.
    *   `GET /api/v1/tasks?status=IN_PROGRESS&priority=HIGH`
*   **Comparison Operators:** Use suffixes separated by an underscore.
    *   `_gt` (greater than), `_gte` (greater than or equal to), `_lt` (less than), `_lte` (less than or equal to).
    *   `GET /api/v1/tasks?estimation_gt=3&createdAt_gte=2026-07-01T00:00:00Z`
*   **In-List Filtering:** Use comma-separated values.
    *   `GET /api/v1/tasks?status=TODO,IN_PROGRESS,REVIEW`

### Sorting Conventions
*   **Parameter:** `sort`
*   **Format:** Comma-separated list of field names. Sorting direction is specified by a suffix: `,asc` or `,desc`. If no direction is specified, default to `asc`.
    *   Single field: `GET /api/v1/tasks?sort=createdAt,desc`
    *   Multiple fields: `GET /api/v1/tasks?sort=priority,desc,createdAt,asc`

### Global Search
*   **Parameter:** `q`
*   Used to execute full-text or semantic search across resource text fields.
    *   `GET /api/v1/documents?q=OAuth+Setup`

---

## 13. Authentication & Headers

API communication relies on standard HTTP headers to manage authentication, tracking, and compliance.

### Required Header Standards
*   **`Authorization: Bearer <JWT>`**
    *   Contains the signed JSON Web Token validating the user identity, organization memberships, and roles. Mandatory for all endpoints except public authentication routes (`/api/v1/auth/login`, `/api/v1/auth/register`).
*   **`Content-Type: application/json`**
    *   Mandatory for all write operations (`POST`, `PUT`, `PATCH`) except multipart file uploads.
*   **`Accept: application/json`**
    *   Required to enforce consistent payload structure negotiations.
*   **`X-Correlation-ID`**
    *   A unique UUID generated by the client or Gateway for every single HTTP invocation. It must be propagated by the backend through thread context, Modulith modules, and background tasks (W3C trace context) to ensure end-to-end trace mapping.
*   **`Idempotency-Key`**
    *   A unique client-generated string (typically UUIDv4) ensuring safe retries for mutation endpoints (see Section 14).

---

## 14. Idempotency

Idempotency guarantees that an API request can be executed multiple times without unintended side effects.

```
 Client                          API Gateway                    Redis Cache
   │                                  │                              │
   │─── 1. POST /api/v1/tasks ───────>│                              │
   │    Idempotency-Key: key_abc      │                              │
   │                                  │─── 2. Query key_abc ────────>│
   │                                  │    [Key Not Found]           │
   │                                  │<── 3. Null response ─────────│
   │                                  │                              │
   │                                  │─── 4. Acquire Lock & Process─│
   │                                  │    (Save result to Redis)    │
   │                                  │                              │
   │                                  │─── 5. Write response cache ─>│
   │                                  │                              │
   │<── 6. 201 Created Response ──────│                              │
   │                                  │                              │
   │                                  │                              │
   │─── 7. RETRY: Same request ──────>│                              │
   │    Idempotency-Key: key_abc      │                              │
   │                                  │─── 8. Query key_abc ────────>│
   │                                  │<── 9. Return cached response─│
   │<── 10. 201 Created Response ─────│                              │
```

### Safety vs. Idempotency
*   **Safe Methods:** `GET`, `HEAD`, `OPTIONS` are safe by definition and do not alter state.
*   **Idempotent Methods:** `PUT`, `DELETE` are idempotent. Running `DELETE /api/v1/tasks/1` multiple times results in the task being deleted. The first return is `204 No Content` (or `200`), subsequent calls may return `404 Not Found` (but the state remains unchanged).
*   **Non-Idempotent Methods:** `POST` and `PATCH` are non-idempotent by default. Repeating a `POST /api/v1/tasks` will create duplicate tasks.

### Idempotency Enforcement Mechanism
1.  **Mandatory Headers:** All state-modifying requests (`POST`, `PATCH`) targetting critical paths (e.g., creating tasks, initiating AI chat completions, creating payments) **must** submit an `Idempotency-Key` header.
2.  **Implementation Rules:**
    *   The backend (via Spring Interceptors) checks the `Idempotency-Key` in **Redis 7** before routing the request.
    *   If the key exists and the process is complete, the cached response is returned directly without hit-testing the DB or service layer.
    *   If the key exists but the process is still running, the server returns `409 Conflict` (or `425 Too Early`), preventing concurrent execution.
    *   If the key does not exist, the server locks the key, processes the request, caches the result in Redis with a **24-hour Time-To-Live (TTL)**, and releases the lock.
3.  **Error States:** If a request fails validation (400, 422), do **not** cache the error payload under the Idempotency Key. Clients must fix validation issues and send a new key.

---

## 15. Rate Limiting

DevFlow protects its servers, databases, and third-party AI provider budgets from abuse, DDoS attacks, and API loops via a token-bucket rate limiter stored in Redis.

### Architectural Policy
1.  **Tier-based Limits:** Rate limits are calculated based on the authentication token (Tenant organization tier: Developer, Team, Enterprise).
2.  **IP fallback:** For anonymous endpoints (e.g., login, password reset), rate limiting falls back to tracking the client's IP address.
3.  **HTTP Headers:** Every API response must include standard tracking headers:
    *   `X-RateLimit-Limit`: Maximum requests allowed in the window.
    *   `X-RateLimit-Remaining`: Number of requests left in the current window.
    *   `X-RateLimit-Reset`: Unix timestamp representing when the current window resets.
    *   `Retry-After`: (Only on HTTP 429) Number of seconds the client must wait before retrying.

### Rate Limit Exhaustion Example (HTTP 429)
```json
{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "You have exceeded your request quota. Please wait before retrying.",
    "details": [],
    "requestId": "trace-904caf17-17fe-4472",
    "timestamp": "2026-07-29T11:53:02Z"
  }
}
```

---

## 16. File Upload Standards

Uploading binary files (e.g., repository snapshots, document attachments) must avoid exhausting backend JVM memory buffers.

### Upload Flow Rules
1.  **Multipart Uploads (Size < 10MB):**
    *   Client sends a `multipart/form-data` request.
    *   The payload is processed in chunks using streaming input streams (e.g., Spring's `MultipartFile`) directly to object storage (AWS S3, Google Cloud Storage, or MinIO). The JVM must never buffer files entirely in memory.
2.  **Presigned Upload URLs (Size >= 10MB - Mandated for large files):**
    *   Instead of hitting the DevFlow backend directly, the client requests an upload authorization.
    *   `POST /api/v1/documents/upload-urls` with metadata (file name, file size, content type).
    *   Server validates authorization and returns a temporary presigned AWS S3 / Google Cloud Storage URL.
    *   The client uploads the file directly to object storage via a binary `PUT` request.
    *   Once complete, the client notifies the server via a lightweight callback: `POST /api/v1/documents/confirm-upload` to trigger asynchronous vector database indexing and extraction.

---

## 17. Long-Running Operations (LRO)

Operations that cannot be resolved in under **3 seconds** (e.g., cloning a massive repository, executing an complex AI codebase review, generating developer analytics snapshots) must not block the HTTP execution pool. They must be handled asynchronously.

### LRO Execution Flow
1.  **Initial Request:** The client initiates the operation.
    *   `POST /api/v1/repositories/{repoId}/sync`
2.  **Immediate Status Response:** The server returns `202 Accepted` immediately with:
    *   `Location` header: Path to the operation tracking resource.
        *   `Location: /api/v1/operations/op_01h8abc123xyz`
    *   Payload containing the initial operation representation.
3.  **Tracking Resource Schema:**
    ```json
    {
      "data": {
        "id": "op_01h8abc123xyz",
        "type": "REPOSITORY_SYNC",
        "status": "PROCESSING",
        "progress": 45,
        "resultUrl": null,
        "error": null,
        "createdAt": "2026-07-29T11:50:00Z",
        "updatedAt": "2026-07-29T11:52:00Z"
      }
    }
    ```
4.  **Client Polling:** The client polls `GET /api/v1/operations/op_01h8abc123xyz` (or subscribes to the STOMP WebSocket channel for the operation) until the status reaches a terminal state.
5.  **Completion State:** Once finished, the operation returns:
    *   `status`: `"COMPLETED"`
    *   `resultUrl`: Path to the created resource (e.g., `/api/v1/repositories/{repoId}/commits`).
6.  **Failure State:** If the operation fails:
    *   `status`: `"FAILED"`
    *   `error`: Standard error payload embedded within the operation status.

---

## 18. API Versioning

DevFlow utilizes **URI-based API Versioning** for all external public endpoints.

```
/api/v1/projects
```

### Architectural Justification
1.  **Caching Simplicity:** Forward proxies, reverse proxies, and CDN edges cache API requests based on URL paths. Header-based versioning requires complex `Vary` header configurations which can result in cache poisoning or stale cache serving.
2.  **SDK & Documentation Generation:** Standard API generation tools (OpenAPI Generator) work natively with URI versioning, creating isolated SDK namespaces (`v1`, `v2`) for clients automatically.
3.  **Gateway Routing:** Spring Cloud Gateway and Modulith routes can inspect the path context prefix (`/api/v1/**` vs `/api/v2/**`) to hot-route requests without parsing HTTP headers.

### Compatibility Guarantees
*   Breaking changes (e.g., removing fields, renaming fields, altering URL structures) require a version bump (e.g., `/api/v2/`).
*   Non-breaking changes (e.g., adding optional fields, exposing new sub-resources) do **not** require a version bump and are introduced in the active version (as long as clients follow Postel's Law and ignore unrecognized fields).

---

## 19. Deprecation Policy

When an API version or specific endpoint must be retired, DevFlow enforces a strict deprecation timeline.

### Deprecation Announcement
Deprecating an endpoint requires setting standard HTTP headers on all matching responses:
*   **`Deprecation: true`**
    *   Signals that the endpoint is deprecated. Can carry date parameters if appropriate.
*   **`Sunset: Wed, 29 Jul 2026 12:00:00 GMT`**
    *   Identifies the date when the endpoint will be turned off permanently.
*   **`Link: <https://docs.devflow.ai/migrations/v1-to-v2>; rel="successor-version"`**
    *   Links to migration documentation.

### Grace Periods
*   **External APIs:** Minimum **6 months** transition period between the sunset announcement and the actual server shutdown.
*   **Internal Module-to-Module APIs:** Minimum **1 sprint (2 weeks)** transition window.

---

## 20. API Architectural Rules

To prevent code decay and protect the core domain models, the following architectural constraints are strictly enforced:

1.  **Thin Controllers, Rich Services:** Controllers (Spring MVC `@RestController`) must contain zero business logic. Their responsibility is strictly payload parsing, HTTP status resolution, request validation, and mapping DTOs. All business validation and execution must reside in domain services.
2.  **No Database Entity Exposure:** Database entities (JPA/Hibernate entities) must never be returned directly in API endpoints. APIs must strictly consume and return designated DTO (Data Transfer Object) records. We use **MapStruct** for compilation-time mapping to prevent performance issues and lazy loading serialization errors.
3.  **ID Masking:** Database sequential primary keys (e.g., auto-increment `BIGINT`) must **never** be exposed in JSON responses or URIs. All public resources must be identified by a safe string key (such as UUIDv7 or prefixed slug identifier).
4.  **No Leaking Internal Details:** Exception messages returned to clients must be clean. Class names, database table names, SQL constraints, and microservice internal network hostnames must never appear in error payloads.
5.  **OpenAPI Compliance:** Every active endpoint must possess valid Javadoc annotations and/or Springdoc OpenAPI annotations. The OpenAPI specification file must be updated in sync with code modifications.
6.  **Explicit Bounded Context Headers:** Where operations cross Bounded Contexts, public module APIs must define interface boundaries. Controller classes must exist within their respective module packages (`com.devflow.modules.{context}.api.controller`).

---

## 21. Future Evolution

These REST design guidelines are built to support DevFlow's long-term technology roadmaps:

*   **SDK Generation:** By enforcing clean REST conventions and OpenAPI specifications, we can execute automated CI/CD runs to compile NPM, Maven, and Go client SDKs, improving developer velocity.
*   **Coexistence with gRPC & GraphQL:**
    *   *gRPC:* As the product scales and internal modular monlith segments are extracted into microservices, we will introduce gRPC for high-throughput, low-latency inter-service communication. The public REST gateway will map JSON requests to backend gRPC services using a REST-to-gRPC gateway (e.g., Envoy or Spring Cloud Gateway mapping).
    *   *GraphQL:* For complex frontend data graphs where clients require arbitrary field selection to prevent over-fetching, we can introduce a GraphQL layer. The GraphQL schema resolver will delegate operations directly to the same underlying Bounded Context services that the REST controllers use, preserving business rules.
