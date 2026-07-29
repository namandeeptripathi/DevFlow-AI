# DevFlow — Error Response Format

> **Version:** 1.0.0
> **Status:** Final / Architecture Review Board (ARB) Approved
> **Author:** Principal API Architect
> **Date:** 2026-07-29
> **Classification:** Internal & Partner Engineering

---

## Table of Contents

1. [Purpose](#1-purpose)
2. [Design Principles](#2-design-principles)
3. [Standard Error Structure](#3-standard-error-structure)
4. [Error Categories](#4-error-categories)
5. [HTTP Status Code Mapping](#5-http-status-code-mapping)
6. [Validation Error Format](#6-validation-error-format)
7. [Business Errors](#7-business-errors)
8. [Security Considerations](#8-security-considerations)
9. [Correlation & Traceability](#9-correlation--traceability)
10. [Internationalization](#10-internationalization)
11. [Versioning](#11-versioning)
12. [Future Evolution](#12-future-evolution)
13. [Architectural Principles & Key Design Decisions](#13-architectural-principles--key-design-decisions)

---

## 1. Purpose

### 1.1 The Cost of Inconsistent Error Responses

In a platform with multiple client surfaces — a Next.js web application, a CLI tool, a VS Code Extension, and future mobile applications — every client must be able to parse, display, and react to API errors. If each Bounded Context (Project Management, AI Engine, Repository Intelligence, Knowledge Base) returns errors in its own format, every client must implement a different error-parsing strategy per domain. This creates compounding costs:

- Client developers write bespoke error-handling code for each module.
- Error display logic diverges across client surfaces.
- Debugging production incidents requires knowledge of which module produced a given error and what format it uses.
- Automated monitoring and alerting systems cannot reliably extract error context.
- SDK generation tools cannot produce generic error-handling utilities.

A single, canonical error response architecture eliminates all of these costs by establishing a contract that every module honors unconditionally.

### 1.2 Strategic Goals

| Goal | What Standardization Achieves |
| :--- | :--- |
| **Developer Experience** | API consumers — whether internal teams or third-party integrators — can build a single error-handling utility and reuse it across the entire platform. Predictable error shapes reduce integration friction dramatically. |
| **Client Consistency** | Every client surface (web, CLI, extension, mobile) renders errors using the same parser. A change in one module's error behavior does not break another client's error display logic. |
| **Debugging & Incident Investigation** | Every error response carries a correlation identifier linking it to a specific server-side trace. Support engineers and developers can jump from a user-reported error directly to the distributed trace without guesswork. |
| **Monitoring & Alerting** | Error categories and machine-readable codes are structured fields that observability pipelines (Grafana, Prometheus) can group, aggregate, and alert on without parsing free-text messages. |
| **API Evolution** | When new error scenarios emerge (new business rules, new validation constraints, new external dependencies), they slot into the existing error structure without requiring clients to update their error-handling logic. |

---

## 2. Design Principles

### 2.1 Consistency

Every error response produced anywhere in DevFlow — regardless of which Bounded Context, which HTTP method, or which error category — must conform to the same JSON structure. There are no module-specific error formats, no raw string responses on failure, and no empty bodies paired with non-2xx status codes.

**The envelope is the contract.** Clients are permitted to write code that assumes the error structure without first checking which module produced the response.

### 2.2 Predictability

Error responses must be predictable both in **structure** (same fields, same types, same nesting) and in **semantics** (the same category of problem always produces the same error `code` family). A developer who has handled a `VALIDATION_FAILED` error from the Project Management module will immediately recognize and correctly handle a `VALIDATION_FAILED` error from the AI Engine module.

### 2.3 Human-Readable Messages

The `message` field must contain a grammatically correct, plain-English explanation of what went wrong. It must be written for a developer — not a system log. It must be actionable where possible:

- **Poor:** `"Error 422"`
- **Poor:** `"constraint_violation_email_not_null"`
- **Good:** `"The email address field is required and cannot be blank."`
- **Good:** `"The project key 'DEVF' is already in use within this organization. Choose a different key."`

### 2.4 Machine-Readable Codes

The `code` field contains an immutable, uppercase, underscore-delimited string constant. Unlike `message`, it never changes with localization, product copy revisions, or wording improvements. Client applications switch on `code` to route error handling, display localized messages, or trigger specific recovery flows.

- `code` is a contract. Once published, a code value must not be renamed or removed within a major API version.
- `message` is documentation. It can be improved without breaking clients.

### 2.5 Security — No Information Leakage

Error responses must never expose internal implementation details to API consumers:

- No stack traces, class names, or package paths.
- No SQL error messages, constraint names, or table identifiers.
- No internal service hostnames, port numbers, or infrastructure topology.
- No database primary key values that differ from the public resource identifier.
- No LLM provider error messages passed through verbatim to clients.

Every internal error detail is logged server-side with full context. The client receives only what it needs to understand the nature of the failure — not how the system is built.

### 2.6 Extensibility

The error structure must be able to accommodate new fields as the platform evolves, without invalidating existing clients. New optional fields are added at the root level or within the `details` array. Existing fields are never removed or renamed within a major version. Clients must be written to ignore unrecognized fields (Postel's Law on the consumer side).

### 2.7 Backward Compatibility

The error format is a versioned contract tied to the API version (`/api/v1/`). Changes to the error structure that add optional fields are non-breaking and may be introduced without a version bump. Changes that remove fields, rename fields, or alter the semantics of existing fields require a major version increment, following the same versioning policy defined in the API Standards document.

---

## 3. Standard Error Structure

### 3.1 Canonical Error Envelope

All error responses are wrapped in a top-level `error` object. The HTTP response body never contains a raw error string, a raw exception message, or an unstructured JSON object.

```json
{
  "error": {
    "timestamp": "2026-07-29T11:53:02.347Z",
    "status": 422,
    "error": "Unprocessable Entity",
    "code": "VALIDATION_FAILED",
    "message": "One or more fields failed validation. Review the details array for specific field-level errors.",
    "path": "/api/v1/projects",
    "requestId": "req_01h9abc123def456",
    "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
    "details": [
      {
        "field": "name",
        "rejectedValue": "",
        "code": "FIELD_REQUIRED",
        "message": "Project name is required and cannot be blank."
      },
      {
        "field": "key",
        "rejectedValue": "devf 42",
        "code": "FIELD_INVALID_FORMAT",
        "message": "Project key must contain only uppercase letters and digits with no spaces."
      }
    ]
  }
}
```

### 3.2 Field Reference

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `timestamp` | `string` (ISO-8601 UTC) | Always | The moment the error was generated on the server, in UTC. Format: `YYYY-MM-DDTHH:mm:ss.SSSZ`. Used to correlate the error with server logs and trace data. |
| `status` | `integer` | Always | The HTTP status code as an integer, mirrored inside the body for consumers that parse only the response body (e.g., some HTTP client libraries that do not expose the status line separately). |
| `error` | `string` | Always | The standard HTTP reason phrase corresponding to the status code (e.g., `"Bad Request"`, `"Unprocessable Entity"`, `"Not Found"`). Human-readable; not intended for programmatic switching. |
| `code` | `string` | Always | A machine-readable, immutable, uppercase, underscore-delimited error code specific to DevFlow's domain (e.g., `VALIDATION_FAILED`, `PROJECT_NOT_FOUND`, `RATE_LIMIT_EXCEEDED`). This is the primary field for programmatic error handling and client-side localization lookups. |
| `message` | `string` | Always | A human-readable, English-language description of the error intended for developers. Actionable where possible. Must never contain sensitive internal system information. |
| `path` | `string` | Always | The URI path of the request that produced the error (e.g., `/api/v1/projects`). Does not include the query string. Useful for log correlation when the request ID is not yet known. |
| `requestId` | `string` | Always | A unique identifier for the specific HTTP request, echoing the `X-Request-ID` header sent by the client (or generated by the gateway if absent). Enables exact request lookup in logs and traces. |
| `traceId` | `string` | Always | The W3C Trace Context `traceparent` trace ID component, propagated through all internal module calls and background tasks. Links the client-visible error to the complete distributed trace in the observability stack. |
| `details` | `array` | Conditionally | An array of granular error objects providing field-level or item-level specifics. Present for validation errors and certain business rule violations where multiple discrete failures occurred. Empty array (`[]`) or omitted for single-failure errors. |

### 3.3 Detail Object Schema

Each object within the `details` array follows this structure:

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `field` | `string` | Conditionally | The dot-notation path of the field that failed (e.g., `"email"`, `"address.postalCode"`, `"tasks[2].title"`). Absent for global (non-field-specific) errors. |
| `rejectedValue` | `any` | Conditionally | The value that was submitted and failed validation. Omitted when the value itself is sensitive (e.g., passwords, tokens) or when no value was submitted (e.g., a missing required field). |
| `code` | `string` | Always | A machine-readable code identifying the specific constraint that was violated (e.g., `FIELD_REQUIRED`, `FIELD_TOO_LONG`, `FIELD_INVALID_FORMAT`, `FIELD_DUPLICATE`). |
| `message` | `string` | Always | A human-readable explanation of why this specific field failed, providing enough context to correct the error. |

---

## 4. Error Categories

DevFlow groups all error scenarios into ten canonical categories. The category determines the HTTP status code family, the `code` naming convention, and the expected handling strategy.

```
+-------------------------------------------------------------------+
|                    ERROR CATEGORY TAXONOMY                        |
|                                                                   |
|   CLIENT ERRORS (4xx)                                             |
|   +------------------+  +------------------+  +---------------+  |
|   | Validation       |  | Authentication   |  | Authorization |  |
|   | Errors           |  | Errors           |  | Errors        |  |
|   | 400 / 422        |  | 401              |  | 403           |  |
|   +------------------+  +------------------+  +---------------+  |
|                                                                   |
|   +------------------+  +------------------+  +---------------+  |
|   | Resource         |  | Business Rule    |  | Conflict      |  |
|   | Errors           |  | Errors           |  | Errors        |  |
|   | 404 / 410        |  | 422              |  | 409           |  |
|   +------------------+  +------------------+  +---------------+  |
|                                                                   |
|   +------------------+                                            |
|   | Rate Limiting    |                                            |
|   | 429              |                                            |
|   +------------------+                                            |
|                                                                   |
|   SERVER ERRORS (5xx)                                             |
|   +------------------+  +------------------+  +---------------+  |
|   | External Service |  | Infrastructure   |  | Unexpected    |  |
|   | Errors           |  | Errors           |  | Errors        |  |
|   | 502 / 503 / 504  |  | 503              |  | 500           |  |
|   +------------------+  +------------------+  +---------------+  |
+-------------------------------------------------------------------+
```

### 4.1 Validation Errors

Occur when the structure or format of the request payload or query parameters fails syntactic or constraint validation — before any business logic is executed.

- **Trigger:** Missing required fields, type mismatches, string length violations, regex pattern failures, enum value violations, date format errors.
- **Status:** `422 Unprocessable Entity` (payload structure valid, content invalid) or `400 Bad Request` (payload syntax unparseable).
- **Code prefix:** `VALIDATION_*`
- **Key characteristic:** Multiple field-level failures are collected and returned together in a single response. The client must not need to submit again to discover subsequent validation errors.

### 4.2 Authentication Errors

Occur when the request cannot be associated with a verified identity.

- **Trigger:** Missing `Authorization` header, expired JWT, invalid JWT signature, revoked token, malformed token format.
- **Status:** `401 Unauthorized`
- **Code prefix:** `AUTH_*`
- **Key characteristic:** The `details` array is always empty. Detailed authentication failure reasons are logged server-side but never returned to the client (information leakage prevention).

### 4.3 Authorization Errors

Occur when the authenticated principal does not hold the required permissions for the requested operation or resource.

- **Trigger:** Insufficient role, missing permission grant, owner-only operation attempted by non-owner.
- **Status:** `403 Forbidden`; or `404 Not Found` when the resource exists in another tenant (prevents resource enumeration — see Security section).
- **Code prefix:** `AUTHZ_*`
- **Key characteristic:** The response reveals that access was denied but never reveals the reason (e.g., "you lack the `project.delete` permission"). The `message` is intentionally generic.

### 4.4 Resource Errors

Occur when the requested resource does not exist, has been permanently removed, or cannot be located within the current tenant context.

- **Trigger:** Invalid resource ID in the URI, resource deleted, resource archived and no longer accessible at the original URI.
- **Status:** `404 Not Found` (resource not found or cross-tenant — see Security section), `410 Gone` (permanently deleted).
- **Code prefix:** `RESOURCE_*`
- **Key characteristic:** Resource errors never confirm the existence of resources in other tenants.

### 4.5 Business Rule Errors

Occur when the request is structurally valid and the principal is authorized, but the operation violates a domain invariant or business policy.

- **Trigger:** Attempting to add a task to a closed sprint, attempting to connect an already-connected repository, submitting a review on a merged pull request, exceeding a subscription seat limit.
- **Status:** `422 Unprocessable Entity`
- **Code prefix:** Domain-specific (e.g., `PROJECT_*`, `CYCLE_*`, `REPO_*`, `SUBSCRIPTION_*`)
- **Key characteristic:** These errors are distinct from validation errors because they pass structural validation but fail business logic. The distinction is important for client UX — a validation error suggests a form input fix; a business rule error suggests a workflow or state issue.

### 4.6 Conflict Errors

Occur when the request conflicts with the current state of the server — typically due to concurrent write conflicts or uniqueness violations.

- **Trigger:** Duplicate resource creation (same project key, same email), optimistic lock failure (`If-Match` ETag mismatch), concurrent state transition conflict.
- **Status:** `409 Conflict`
- **Code prefix:** `CONFLICT_*`
- **Key characteristic:** A conflict error implies the client should inspect current server state before retrying.

### 4.7 Rate Limiting Errors

Occur when the client has exceeded its request quota within the current window.

- **Trigger:** Per-tenant rate limit exceeded, per-IP rate limit exceeded (for unauthenticated endpoints), AI inference token budget exhausted.
- **Status:** `429 Too Many Requests`
- **Code prefix:** `RATE_LIMIT_*`
- **Key characteristic:** Always accompanied by `Retry-After` and `X-RateLimit-*` response headers (defined in API Standards). The `details` array may include the specific limit that was exceeded.

### 4.8 External Service Errors

Occur when a downstream dependency (LLM provider, Git hosting API, transactional email provider) is unavailable or returns an error.

- **Trigger:** GitHub API timeout, OpenAI rate limit, SendGrid delivery failure, Stripe API unavailability.
- **Status:** `502 Bad Gateway` (upstream returned an error), `503 Service Unavailable` (upstream unreachable), `504 Gateway Timeout` (upstream timed out).
- **Code prefix:** `EXTERNAL_*`
- **Key characteristic:** The raw error from the external service is **never** included in the response. Only a sanitized, provider-agnostic message is returned. The `message` acknowledges the dependency but does not name the specific provider.

### 4.9 Infrastructure Errors

Occur when a core infrastructure component (database, Redis, internal message bus) is unavailable.

- **Trigger:** Database connection pool exhausted, Redis failover in progress, storage backend unreachable.
- **Status:** `503 Service Unavailable`
- **Code prefix:** `INFRA_*`
- **Key characteristic:** Like external service errors, infrastructure details are never exposed. The response carries a `Retry-After` header where a retry is expected to succeed.

### 4.10 Unexpected Errors

A catch-all category for unhandled exceptions and programming errors that were not anticipated at design time.

- **Trigger:** Any unhandled exception that reaches the global error boundary.
- **Status:** `500 Internal Server Error`
- **Code prefix:** `INTERNAL_*`
- **Key characteristic:** The only code used is `INTERNAL_SERVER_ERROR`. The `details` array is always empty. The full exception chain, stack trace, and context are logged server-side with `CRITICAL` severity. The client receives only the `requestId` and `traceId` to enable support investigation.

---

## 5. HTTP Status Code Mapping

### 5.1 Status Code Reference

| Status | Reason Phrase | DevFlow Usage Rule |
| :--- | :--- | :--- |
| **400** | Bad Request | The request payload is syntactically unparseable (malformed JSON, wrong `Content-Type`, missing body where required). Returned before any field validation occurs. |
| **401** | Unauthorized | The request lacks valid authentication credentials. The `Authorization` header is missing, the token is expired, the signature is invalid, or the token has been revoked. The client must re-authenticate. |
| **403** | Forbidden | The authenticated principal does not have permission to perform the requested operation on the identified resource within the active tenant. The resource exists but access is denied. |
| **404** | Not Found | The resource does not exist within the current tenant context. Also used intentionally when a resource exists in a different tenant (to prevent cross-tenant enumeration). Also returned for unsupported URI paths. |
| **405** | Method Not Allowed | The HTTP method used is not supported on the target URI (e.g., `DELETE` on a read-only endpoint). Must include an `Allow` header listing supported methods. |
| **409** | Conflict | The request conflicts with the current resource state. Typically indicates a uniqueness violation, an optimistic locking conflict, or a concurrent modification. |
| **410** | Gone | The resource existed but has been permanently deleted. Unlike `404`, `410` signals that the resource will not return and any cached references should be invalidated. |
| **412** | Precondition Failed | The server does not meet a precondition asserted by the client in conditional request headers (`If-Match`, `If-Unmodified-Since`). Used for optimistic concurrency control. |
| **415** | Unsupported Media Type | The client submitted a request body with a `Content-Type` that the server does not support (e.g., `text/xml` when `application/json` is required). |
| **422** | Unprocessable Entity | The request payload is syntactically valid JSON but fails semantic validation (field constraints) or violates a business invariant. The primary status for both validation errors and business rule errors. |
| **429** | Too Many Requests | The client has exceeded its rate limit. Must be accompanied by `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset` headers. |
| **500** | Internal Server Error | An unexpected error occurred on the server. The error is logged with full context. The client should not retry without contacting support unless specifically advised by the `Retry-After` header. |
| **502** | Bad Gateway | The server, acting as a gateway, received an invalid response from an upstream dependency (e.g., an LLM provider returned a malformed response). |
| **503** | Service Unavailable | The server is temporarily unable to handle the request. May be due to overload, maintenance, infrastructure degradation, or upstream dependency outage. Always accompanied by `Retry-After`. |
| **504** | Gateway Timeout | An upstream dependency did not respond within the acceptable timeout window (e.g., an LLM inference call exceeded the maximum allowed duration). |

### 5.2 Status Code Decision Tree

```
Is the request syntactically valid? (valid JSON, correct Content-Type)
  |
  +-- No --> 400 Bad Request
  |
  +-- Yes --> Is the principal authenticated?
                |
                +-- No --> 401 Unauthorized
                |
                +-- Yes --> Is the principal authorized for this resource?
                              |
                              +-- No (and resource exists in THIS tenant) --> 403 Forbidden
                              +-- No (resource may exist in ANOTHER tenant) --> 404 Not Found
                              |
                              +-- Yes --> Does the resource exist?
                                           |
                                           +-- No --> 404 Not Found
                                           +-- Permanently deleted --> 410 Gone
                                           |
                                           +-- Yes --> Does the request conflict with current state?
                                                        |
                                                        +-- Uniqueness / concurrency --> 409 Conflict
                                                        |
                                                        +-- Field validation failure --> 422 Unprocessable Entity
                                                        +-- Business rule failure --> 422 Unprocessable Entity
                                                        |
                                                        +-- No conflicts --> Execute operation
                                                                               |
                                                                               +-- External dep error --> 502/503/504
                                                                               +-- Infrastructure error --> 503
                                                                               +-- Unexpected error --> 500
                                                                               +-- Success --> 200/201/202/204
```

---

## 6. Validation Error Format

### 6.1 Design Intent

Validation errors represent syntactic and constraint failures on request input. The architectural priority is to **collect and return all validation failures in a single response** — never to fail on the first error and force the client to resubmit repeatedly to discover subsequent failures. This is the "collect-all" validation strategy.

### 6.2 Field-Level Errors

A field-level error is anchored to a specific named field in the request payload. The `field` value in the detail object uses **dot notation** for nested objects and **bracket notation** for collection items.

```json
{
  "error": {
    "timestamp": "2026-07-29T11:53:02.347Z",
    "status": 422,
    "error": "Unprocessable Entity",
    "code": "VALIDATION_FAILED",
    "message": "One or more fields failed validation.",
    "path": "/api/v1/organizations/org_abc/projects",
    "requestId": "req_01h9abc123def456",
    "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
    "details": [
      {
        "field": "name",
        "rejectedValue": "",
        "code": "FIELD_REQUIRED",
        "message": "Project name is required and cannot be blank."
      },
      {
        "field": "key",
        "rejectedValue": "devf 42",
        "code": "FIELD_INVALID_FORMAT",
        "message": "Project key must be 2–6 uppercase letters with no spaces or special characters."
      },
      {
        "field": "settings.defaultCycleDuration",
        "rejectedValue": -1,
        "code": "FIELD_OUT_OF_RANGE",
        "message": "Default cycle duration must be a positive integer representing days."
      }
    ]
  }
}
```

### 6.3 Global Errors

A global error applies to the request as a whole — not to a specific field. It is represented in the `details` array with no `field` value.

```json
{
  "details": [
    {
      "code": "PAYLOAD_TOO_LARGE",
      "message": "The submitted document content exceeds the maximum allowed size of 10MB."
    }
  ]
}
```

### 6.4 Nested Object Validation

Errors within nested objects use dot-notation paths to precisely identify the failing field:

```json
{
  "details": [
    {
      "field": "address.postalCode",
      "rejectedValue": "ABCDE",
      "code": "FIELD_INVALID_FORMAT",
      "message": "Postal code must be a valid 6-digit Indian PIN code."
    },
    {
      "field": "contact.email",
      "rejectedValue": "not-an-email",
      "code": "FIELD_INVALID_FORMAT",
      "message": "Must be a valid email address."
    }
  ]
}
```

### 6.5 Collection Item Validation

When validating an array of items, each failing item is identified by its zero-based array index using bracket notation:

```json
{
  "details": [
    {
      "field": "tasks[0].title",
      "rejectedValue": "",
      "code": "FIELD_REQUIRED",
      "message": "Task title at index 0 is required."
    },
    {
      "field": "tasks[2].priority",
      "rejectedValue": "SUPER_URGENT",
      "code": "FIELD_INVALID_ENUM",
      "message": "Priority at index 2 must be one of: LOW, MEDIUM, HIGH, URGENT."
    }
  ]
}
```

### 6.6 Standard Field-Level Error Codes

| Code | Meaning |
| :--- | :--- |
| `FIELD_REQUIRED` | Field is mandatory but was absent or null |
| `FIELD_INVALID_FORMAT` | Field value does not match the expected pattern or format |
| `FIELD_TOO_SHORT` | String length is below the minimum |
| `FIELD_TOO_LONG` | String length exceeds the maximum |
| `FIELD_OUT_OF_RANGE` | Numeric value is below minimum or above maximum |
| `FIELD_INVALID_ENUM` | Value is not a member of the allowed enumeration |
| `FIELD_INVALID_TYPE` | Value type does not match the expected type |
| `FIELD_DUPLICATE` | Value conflicts with an existing unique field within the same request body |
| `FIELD_INVALID_DATE` | Date string is not parseable as a valid ISO-8601 date |
| `FIELD_FUTURE_DATE_REQUIRED` | Date must be in the future but was in the past |
| `FIELD_PAST_DATE_REQUIRED` | Date must be in the past but was in the future |
| `FIELD_MUTUALLY_EXCLUSIVE` | Two fields were both provided but are mutually exclusive |
| `FIELD_DEPENDENT_REQUIRED` | A field is required only when another field is present |

---

## 7. Business Errors

### 7.1 Distinction from Validation Errors

Business errors are fundamentally different from validation errors in their **nature** and their **implications for the client**:

| Dimension | Validation Error | Business Error |
| :--- | :--- | :--- |
| **When detected** | Before business logic executes | During business logic execution |
| **Nature of failure** | Input fails structural or format constraints | Input is structurally valid but violates a domain rule |
| **Client implication** | Fix the request payload and resubmit | Understand the current system state; may require workflow changes, not just input changes |
| **Example** | `email` field is not a valid email format | Email address already registered to another account (`CONFLICT_EMAIL_DUPLICATE`) |
| **Example** | `cycleId` is not a UUID | The specified cycle is in `CLOSED` state and cannot accept new tasks (`CYCLE_CLOSED`) |

### 7.2 Business Error Structure

Business errors use the same standard error envelope but with a domain-specific `code` value and no `details` array (since business errors are typically singular):

```json
{
  "error": {
    "timestamp": "2026-07-29T11:53:02.347Z",
    "status": 422,
    "error": "Unprocessable Entity",
    "code": "CYCLE_CLOSED",
    "message": "The sprint 'Sprint 12' is in a closed state and no longer accepts new task assignments. Reopen the sprint or assign the task to an active sprint.",
    "path": "/api/v1/tasks/tsk_01h9abc/cycles",
    "requestId": "req_01h9abc123def456",
    "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
    "details": []
  }
}
```

### 7.3 Business Error Code Registry

The following are canonical examples of domain-specific business error codes across DevFlow's Bounded Contexts:

| Code | HTTP Status | Domain | When Used |
| :--- | :--- | :--- | :--- |
| `PROJECT_ARCHIVED` | 422 | Project Management | A write operation was attempted on an archived project |
| `PROJECT_KEY_DUPLICATE` | 409 | Project Management | A project key already exists within the organization |
| `CYCLE_CLOSED` | 422 | Project Management | A task assignment was attempted on a closed sprint |
| `EPIC_COMPLETED` | 422 | Project Management | A task was linked to a completed epic |
| `TASK_KEY_IMMUTABLE` | 422 | Project Management | An attempt was made to change a task's immutable key |
| `REPO_ALREADY_CONNECTED` | 409 | Repository Intelligence | The repository is already registered within the organization |
| `REPO_SYNC_IN_PROGRESS` | 409 | Repository Intelligence | A sync was triggered while one is already running |
| `REPO_INTEGRATION_MISSING` | 422 | Repository Intelligence | No valid OAuth connection exists for the Git provider |
| `AI_CONTEXT_LIMIT_EXCEEDED` | 422 | AI Engine | The assembled prompt context exceeds the model's token limit |
| `AI_REVIEW_ALREADY_PENDING` | 409 | AI Engine | An AI review is already in progress for this pull request |
| `KB_DOCUMENT_LOCKED` | 409 | Knowledge Base | Another user is editing the document simultaneously |
| `ORGANIZATION_SUSPENDED` | 403 | IAM | The organization's subscription is suspended |
| `SUBSCRIPTION_SEAT_LIMIT` | 422 | Billing | Inviting a new member would exceed the seat limit |
| `INVITATION_EXPIRED` | 410 | IAM | The invitation link has passed its 72-hour expiry window |
| `AUTOMATION_RULE_LOOP` | 422 | Workflow Automation | The automation rule would create a trigger loop |

### 7.4 Why Business Errors Must Be Explicit

Business errors must carry explicit, domain-specific `code` values rather than generic codes like `OPERATION_FAILED`. This allows:
- **Client-side routing:** The VS Code Extension can intercept `CYCLE_CLOSED` and surface a specific "Sprint closed — reassign to active sprint?" prompt.
- **Localization:** SDKs can translate `SUBSCRIPTION_SEAT_LIMIT` into a localized upgrade prompt in the UI.
- **Monitoring:** Dashboards can track the rate of `AI_CONTEXT_LIMIT_EXCEEDED` errors as an indicator that prompts need optimization.

---

## 8. Security Considerations

### 8.1 Information Disclosure Prevention

The error response is an **attack surface**. An overly informative error response can reveal:
- Internal architecture (class names, package structures, ORM queries).
- Database structure (table names, column names, constraint names).
- Tenant data (confirming the existence of resources in other organizations).
- User enumeration data (confirming that a specific email address is registered).
- Infrastructure topology (service hostnames, port numbers, deployment regions).

All of these must be eliminated from error responses. The internal logging system receives the full exception context. The client receives only the categorized, sanitized version.

### 8.2 Authentication Failures

Authentication error responses (`401`) must be deliberately minimal:

- **Allowed:** `"code": "AUTH_TOKEN_EXPIRED"`, `"message": "Your session has expired. Please sign in again."`
- **Forbidden:** Revealing which specific claim was invalid, which key was used for verification, or what the decoded (but signature-failed) payload contained.

The `details` array is always empty for authentication errors.

### 8.3 Authorization Failures

Authorization error responses (`403`) must not reveal the permission required:

- **Allowed:** `"code": "AUTHZ_FORBIDDEN"`, `"message": "You do not have permission to perform this action."`
- **Forbidden:** `"message": "This action requires the project.delete permission which your ORGANIZATION_MEMBER role does not include."`

The latter is a disclosure of the internal permission model and the user's role assignments, which could be exploited to identify escalation targets.

### 8.4 Tenant Isolation — Resource Enumeration Prevention

When a resource exists in the system but belongs to a different tenant, the API returns `404 Not Found` — not `403 Forbidden`. This is a deliberate architectural decision:

- `403` confirms the resource exists but is inaccessible.
- `404` reveals nothing about the resource's existence across the platform.

An attacker who discovers a resource UUID from one account cannot use the API to confirm whether that resource exists in other accounts.

For the same reason, the `/forgot-password` endpoint always returns `200 OK` regardless of whether the email address is registered, preventing user enumeration through the password reset flow.

### 8.5 Stack Trace Protection

Stack traces are **categorically prohibited** in API responses. Any unhandled exception that reaches the global error boundary produces a `500 Internal Server Error` with only the standard envelope, `INTERNAL_SERVER_ERROR` code, a generic message, and the `requestId`/`traceId` for support lookup.

The full stack trace, exception chain, and request context are logged asynchronously with `CRITICAL` severity.

### 8.6 Sensitive Data Masking

The `rejectedValue` field in validation error details must mask sensitive inputs:
- Passwords, tokens, API keys: always omitted.
- Credit card numbers, bank account numbers: masked (e.g., `"****-****-****-4242"`).
- National identification numbers: omitted or truncated.
- Fields whose content could be used for enumeration (e.g., confirming an email exists): omitted.

---

## 9. Correlation & Traceability

### 9.1 The Three Identifiers

DevFlow error responses carry three distinct identifiers, each serving a different traceability purpose:

```
+---------------------------------------------------------------------+
|                  CORRELATION IDENTIFIER HIERARCHY                   |
|                                                                     |
|   CLIENT                    GATEWAY              BACKEND TRACE      |
|                                                                     |
|   X-Request-ID              Gateway echoes       W3C Trace Context  |
|   (generated by client      or generates         (generated at      |
|    for each request)        if absent             gateway entry)    |
|          |                       |                     |           |
|          v                       v                     v           |
|   +-------------+        +-------------+       +-------------+     |
|   | requestId   |        | requestId   |       | traceId     |     |
|   | (in error   |        | (in response|       | (in error   |     |
|   |  body)      |        |  headers)   |       |  body)      |     |
|   +-------------+        +-------------+       +-------------+     |
|                                                                     |
|   Purpose: Link          Purpose: Client-      Purpose: Link to    |
|   error body to          server round-trip     distributed trace   |
|   server log entry       identification        across all modules  |
+---------------------------------------------------------------------+
```

### 9.2 Request ID (`requestId`)

- **Source:** The client-generated `X-Request-ID` header value, or a gateway-generated UUID if the header is absent.
- **Scope:** Identifies a single HTTP request-response round trip.
- **Use:** A user reporting an error can quote the `requestId` to a support engineer, who uses it to locate the exact server log entry for that request — including the full exception, the authenticated user's identity, the tenant, and the timing.
- **Format:** Prefixed UUID string (e.g., `req_01h9abc123def456`).

### 9.3 Trace ID (`traceId`)

- **Source:** The W3C `traceparent` header's trace ID component, generated at the API gateway entry point and propagated through all internal module calls, background tasks, and external dependency calls.
- **Scope:** Identifies the entire causal chain of a request — from the initial HTTP entry to every internal module invocation, every database query, every Redis operation, and every external API call.
- **Use:** The `traceId` is used in Grafana Tempo or Jaeger to reconstruct the full distributed trace. An error in the AI Engine that was triggered by a project creation request in Project Management shares the same `traceId` as the original HTTP request.
- **Format:** 32-character hexadecimal string per W3C Trace Context specification.

### 9.4 Correlation ID

The terms `requestId` and `traceId` together form the **correlation context** for any error. They serve different investigation modes:

| Identifier | Investigation Question | Tool |
| :--- | :--- | :--- |
| `requestId` | "What exactly happened for this specific client request?" | Structured log search by `requestId` |
| `traceId` | "What did the entire system do in response to this client request, including all internal calls?" | Distributed trace viewer (Grafana Tempo, Jaeger) |

### 9.5 Client Support Workflow

```
 User sees error in UI
        |
        | "Error code: CYCLE_CLOSED
        |  Request ID: req_01h9abc123def456"
        |
        v
 User reports to support team
        |
        v
 Support engineer searches logs by requestId
        |
        +-> Finds log entry with full context:
            - Authenticated user: alice@example.com
            - Organization: org_acme
            - Requested operation: PATCH /api/v1/tasks/tsk_abc/cycles
            - Cycle state at time of request: CLOSED
            - Exception (if any): none — expected business error
        |
        +-> Follows traceId to distributed trace:
            - PM module: permission check passed
            - PM service: cycle state validation returned CLOSED
            - Response: 422 CYCLE_CLOSED
```

---

## 10. Internationalization

### 10.1 The API's Responsibility Boundary

DevFlow's REST APIs are **not responsible for message translation**. The API returns:
- Machine-readable `code` values (always English, immutable).
- Human-readable `message` values (English, developer-oriented).

The responsibility for rendering error messages in the user's preferred language belongs to the **client application** (web, mobile, CLI), not the API. This design decision stems from four architectural realities:

1. **APIs serve multiple clients.** A web app, a VS Code extension, and a CLI tool may have different localization frameworks. Centralizing translation in the API couples the API to presentation-layer concerns.
2. **Messages evolve independently.** UX copy improves over time without API changes. If message strings were canonical API outputs, every copy revision would require an API release.
3. **Codes are stable.** Client applications build translation tables keyed on `code`. The `code` `CYCLE_CLOSED` maps to the appropriate localized string in the client's resource bundle. This mapping can be updated in a frontend release without touching the backend.
4. **Developer-facing messages are English.** The `message` field is documentation for the developer integrating the API. Developer tooling (API explorers, logs, CLI output) universally expects English.

### 10.2 Localization Architecture Pattern

```
 API Response                          Client Application
+-------------------+                 +----------------------------------+
| "code":           |                 |  error_codes.en.json:            |
|   "CYCLE_CLOSED"  |  -------->      |    "CYCLE_CLOSED": "The sprint   |
|                   |                 |    is closed. Assign to an       |
| "message":        |                 |    active sprint."               |
|   "The sprint     |                 |                                  |
|   'Sprint 12' is  |                 |  error_codes.de.json:            |
|   in a closed ... |                 |    "CYCLE_CLOSED": "Der Sprint   |
+-------------------+                 |    ist geschlossen..."           |
                                      +----------------------------------+
                                      Client selects locale string by code.
                                      API message is used as fallback in
                                      developer tools and logs only.
```

### 10.3 Accept-Language Header

The `Accept-Language` header sent by the client is **not used by DevFlow APIs** to select error message language. It may be used in future for localized validation constraint messages (e.g., validating phone number formats appropriate for a specific locale), but the `message` field in error responses remains English in all cases. Localized user-facing messages are the client's responsibility, always derived from the `code`.

### 10.4 Future Considerations

Should DevFlow introduce a server-side localization layer in a future phase (e.g., for compliance with local-language regulatory requirements), the mechanism will be an **additive field** in the error structure (e.g., `"localizedMessage": "..."`) populated when the `Accept-Language` header matches a supported locale. The existing `message` field remains unchanged and English-only, preserving full backward compatibility.

---

## 11. Versioning

### 11.1 The Error Format as a Versioned Contract

The error response structure is part of the `/api/v1/` contract. It is versioned alongside the API and follows the same backward compatibility rules defined in the API Standards document.

### 11.2 Non-Breaking Changes (No Version Bump Required)

The following changes to the error format are non-breaking and may be introduced without incrementing the API major version:

| Change | Reason Non-Breaking |
| :--- | :--- |
| Adding a new optional root-level field to the `error` object | Clients following Postel's Law ignore unknown fields |
| Adding a new field to detail objects | Same — ignored by existing clients |
| Introducing a new `code` value for a new error scenario | Clients must have a generic fallback handler for unknown codes |
| Improving the wording of a `message` string | Clients must not parse `message` content programmatically |
| Adding a new HTTP status code for a new operation | No existing endpoint changes status code |

### 11.3 Breaking Changes (Require Major Version Bump)

The following changes are breaking and require a new API major version (`/api/v2/`):

| Change | Reason Breaking |
| :--- | :--- |
| Renaming an existing `code` value | Clients switch on `code`; renaming breaks routing logic |
| Removing a field from the `error` envelope | Clients may require the field |
| Changing the type of an existing field | Parsers fail on unexpected types |
| Restructuring the `details` array format | Existing clients cannot parse the new structure |
| Changing the semantics of an existing `code` | Clients react to `code` values in specific ways; changed semantics break assumptions |

### 11.4 Deprecating Error Codes

When an error `code` must be retired (e.g., because a feature is removed), the deprecation timeline mirrors the API deprecation policy:
1. The old `code` is maintained alongside the new equivalent for a minimum of 6 months.
2. During the transition period, both the old and new codes may appear in responses (a `Deprecation` header indicates the transition is in progress).
3. Documentation is updated with a migration note linking the old code to its replacement.
4. After the sunset period, the old code is retired in the next major API version.

---

## 12. Future Evolution

### 12.1 RFC 9457 — Problem Details for HTTP APIs

[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) defines a standard JSON format for machine-readable problem details in HTTP responses. It introduces standardized fields: `type`, `title`, `status`, `detail`, and `instance`.

DevFlow's current error format was designed with RFC 9457 alignment as a future migration target:

| DevFlow Field | RFC 9457 Equivalent | Notes |
| :--- | :--- | :--- |
| `code` | `type` (as a URI) | Migration path: `code` becomes `type: "https://api.devflow.ai/errors/VALIDATION_FAILED"` |
| `message` | `detail` | Direct mapping |
| `status` | `status` | Already aligned |
| `path` | `instance` | Direct mapping |
| `details` | Extension member | RFC 9457 supports extension members |

A future migration to RFC 9457 would be a **non-breaking additive change** — new fields (`type`, `title`, `instance`) are added to the envelope alongside existing fields. Existing fields are retained for backward compatibility within the major version.

### 12.2 GraphQL

Should DevFlow introduce a GraphQL layer in the future, error handling follows the GraphQL specification's `errors` array format. However, the underlying error `code` taxonomy established in this document will be reused within GraphQL error extensions:

```json
{
  "errors": [
    {
      "message": "The sprint is closed and cannot accept new tasks.",
      "extensions": {
        "code": "CYCLE_CLOSED",
        "requestId": "req_01h9abc123def456",
        "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
      }
    }
  ]
}
```

The same machine-readable codes, the same traceability identifiers, and the same security principles apply across REST and GraphQL surfaces.

### 12.3 gRPC

For future gRPC service interfaces (most likely for the AI Engine internal services), error handling will use gRPC's `Status` and `google.rpc.Status` with `google.rpc.ErrorInfo` details. The DevFlow error `code` taxonomy will be embedded in the `ErrorInfo.reason` field, maintaining consistency across protocols.

### 12.4 Event-Driven Error Signaling

For asynchronous operations (long-running repository syncs, AI review jobs, background analytics calculations), error events published to the internal event bus and delivered via WebSocket STOMP channels will adopt the same error payload structure — specifically the `code`, `message`, `traceId`, and `requestId` fields — so that client-side event handlers use identical error-handling logic regardless of whether the response arrived synchronously (HTTP) or asynchronously (WebSocket event).

### 12.5 API Gateway Standardization

As DevFlow potentially evolves from a Modular Monolith to extracted microservices, an API Gateway layer (Spring Cloud Gateway) will be introduced. The gateway is the ideal enforcement point for error format standardization:
- Gateway-level errors (routing failures, TLS termination errors, authentication validation) are normalized into the DevFlow error envelope before reaching the client.
- Upstream microservice errors are intercepted and, if not already in the standard format, normalized before forwarding.
- The canonical error envelope becomes the **gateway-enforced contract** — internal services may use any error representation they choose internally, as long as the gateway normalizes the output.

---

## 13. Architectural Principles & Key Design Decisions

| # | Principle | Rationale |
| :--- | :--- | :--- |
| **1** | **One error envelope for the entire platform.** All Bounded Contexts (Project Management, AI Engine, Repository Intelligence, Knowledge Base, etc.) produce the same root JSON structure for errors. | Eliminates per-module parsing logic in every client application and enables generic error-handling utilities in all SDKs. |
| **2** | **`code` is immutable; `message` is documentation.** The machine-readable `code` is the stable contract. The human-readable `message` may be improved or reworded at any time. | Clients switch on `code`, not on `message`. This separation allows UX copy improvements without breaking integrations. |
| **3** | **Collect all validation failures in a single response.** The validation error model never fails on the first error and forces re-submission. | Dramatically reduces the number of form-submission round trips for multi-field validation, improving developer and end-user experience. |
| **4** | **Business errors are distinct from validation errors.** Business rule violations produce domain-specific codes (`CYCLE_CLOSED`, `REPO_ALREADY_CONNECTED`), not generic validation codes. | Enables clients to route business errors to specific UX flows (e.g., "Sprint closed — open a new sprint?") rather than treating them as simple input correction tasks. |
| **5** | **`404` is used for cross-tenant resource access, not `403`.** A resource that exists in another tenant returns `404 Not Found` — identical to a resource that does not exist at all. | Prevents cross-tenant resource ID enumeration attacks where an adversary probes IDs to confirm the existence of resources in other organizations. |
| **6** | **Internal details never escape the error boundary.** Stack traces, SQL errors, class names, infrastructure hostnames, and LLM provider errors are logged server-side but never included in API responses. | Protects the internal architecture from reconnaissance. Errors are classified before leaving the backend; only the classification reaches the client. |
| **7** | **Every error carries `requestId` and `traceId`.** These two identifiers are mandatory even on `500` errors where no other context is safe to expose. | Enables a support engineer to locate the full internal context of any client-reported error in under 60 seconds, without requiring the user to reproduce the issue. |
| **8** | **`message` is always English; localization is the client's responsibility.** The API never translates error messages. Clients build translation tables keyed on `code`. | Decouples API stability from client presentation logic. UX copy can be updated in a frontend release without an API deployment. |
| **9** | **The error format is forward-compatible with RFC 9457.** New fields align with Problem Details for HTTP APIs naming conventions, enabling a non-breaking future migration. | Positions DevFlow's error model to adopt the emerging industry standard without a breaking API change. |
| **10** | **The error `code` taxonomy is reused across all protocols.** REST, GraphQL, gRPC, and async WebSocket event errors all reference the same `code` values. | A single error code registry is the source of truth. Client SDK error-handling utilities work identically regardless of the protocol through which the error was delivered. |

---

*This document is the official error response format specification for DevFlow. Changes to the canonical error envelope or error code taxonomy require review and approval from the Architecture Review Board (ARB).*
