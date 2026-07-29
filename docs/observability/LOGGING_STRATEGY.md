# DevFlow — Logging Strategy

> **Version:** 1.0.0
> **Status:** Final / Architecture Review Board (ARB) Approved
> **Author:** Principal Platform Architect
> **Date:** 2026-07-29
> **Classification:** Internal — Engineering & Security

---

## Table of Contents

1. [Purpose](#1-purpose)
2. [Logging Philosophy](#2-logging-philosophy)
3. [Logging Architecture](#3-logging-architecture)
4. [Log Levels](#4-log-levels)
5. [Structured Log Format](#5-structured-log-format)
6. [Correlation & Distributed Tracing](#6-correlation--distributed-tracing)
7. [Audit Logging](#7-audit-logging)
8. [Security Logging](#8-security-logging)
9. [Sensitive Data Policy](#9-sensitive-data-policy)
10. [Performance Logging](#10-performance-logging)
11. [Error Logging](#11-error-logging)
12. [Log Retention](#12-log-retention)
13. [Monitoring Integration](#13-monitoring-integration)
14. [Future Evolution](#14-future-evolution)
15. [Architectural Principles & Key Design Decisions](#15-architectural-principles--key-design-decisions)

---

## 1. Purpose

### 1.1 Why DevFlow Requires a Logging Architecture

Logging is the primary observability instrument available to engineers when a live system behaves unexpectedly. Unlike metrics — which tell you *that* something is wrong — logs tell you *what* happened, *where* it happened, *when* it happened, and *why*. In a distributed, multi-tenant platform like DevFlow, the cost of insufficient logging is measured in hours of blind incident investigation, unresolved support tickets, and security breaches that go undetected.

DevFlow's logging strategy exists to serve six distinct operational needs:

### 1.2 Strategic Goals

| Goal | What Logging Enables |
| :--- | :--- |
| **Debugging** | Engineers can trace the exact sequence of events leading to a defect — from the HTTP request entry point through module boundaries, database calls, and external dependency interactions — without requiring system access or reproduction. |
| **Production Operations** | Platform and infrastructure teams can monitor the health of the running system in real time, identify degradation before it becomes outage, and establish baselines that reveal anomalous behavior. |
| **Security** | Security events — authentication failures, authorization denials, privilege escalation attempts, cross-tenant access attempts — are captured with sufficient context for detection, investigation, and legal evidence. |
| **Compliance** | Enterprise customers in regulated industries (fintech, healthcare, legal) require demonstrable audit trails. Logging provides the timestamped, immutable record of who did what and when, satisfying SOC 2, ISO 27001, and GDPR accountability requirements. |
| **Auditability** | Every privileged action — role assignment, organization deletion, billing change, emergency access — is recorded in an append-only audit log that cannot be retroactively altered. |
| **Incident Investigation** | When a production incident occurs, correlation identifiers (`requestId`, `traceId`) link user-reported errors to the exact server-side log chain, reducing mean time to resolution (MTTR). |
| **Distributed Systems Observability** | DevFlow's Modular Monolith spans multiple internal Bounded Contexts. As operations cross module boundaries, log correlation enables reconstruction of the full causal chain of any request. |

---

## 2. Logging Philosophy

The following principles govern every logging decision in DevFlow. They are applied unconditionally — not as guidelines, but as architectural constraints.

### 2.1 Structured Logging

All log events are emitted as **structured data** — specifically JSON objects — rather than free-form text strings. Free-text logs require regular expression parsing to extract fields for indexing and analysis. Structured logs are machine-parseable by default: every field is a typed key-value pair that log aggregation systems can index, filter, aggregate, and alert on without any transformation.

> **The rule:** Every log event must be a valid JSON object. A log line that contains unstructured concatenated strings is a defect.

### 2.2 Machine-Readable Logs

Structured fields must use **consistent key names and consistent value formats** across all modules. If the `userId` field is a UUID string in the Auth module, it must be a UUID string in every other module. If the `duration` field represents milliseconds in one log event, it must represent milliseconds everywhere. Inconsistent field semantics prevent cross-module log correlation and analysis.

### 2.3 Human-Readable Messages

While the overall log event is machine-structured, the `message` field must be written for a human reader — specifically, a developer or operations engineer who encounters the log during an incident at 3 AM with no prior context. Messages must:
- Describe what happened in plain English.
- Name the relevant resource, operation, or module.
- Be actionable or self-explanatory without requiring the reader to decode field values.

### 2.4 Context-Rich Logs

A log event that lacks context is worthless during investigation. Every log event must carry the **minimum context required to answer the "who, what, where, when"** questions without additional lookups:
- **Who:** `userId`, `tenantId`
- **What:** `operation`, `message`, `module`
- **Where:** `service`, `path`
- **When:** `timestamp`
- **How it ended:** `level`, `duration`, `errorCode` (if applicable)

Logging a bare message like `"User not found"` with no user identifier, no module context, and no request ID is an antipattern.

### 2.5 Consistency

Every module (Auth, Project Management, AI Engine, Repository Intelligence, etc.) emits log events with the same field structure. There is no module-specific logging format. The canonical log schema defined in Section 5 is the single source of truth.

### 2.6 Minimal Duplication

The same event should not be logged multiple times at multiple layers unless each log provides distinct, non-redundant value. Logging the same error at the repository layer, the service layer, and the controller layer produces noise that hides real signals and inflates storage costs. The rule is: **log at the layer that has the most context, not at every layer.**

### 2.7 Privacy by Design

Logs are retained for months or years, often in environments with broader access than production databases. Personal and sensitive data written to logs becomes a persistent privacy liability. The logging architecture enforces a **sensitive data blocklist** (detailed in Section 9) that prevents passwords, tokens, keys, and PII from ever reaching log storage.

### 2.8 Security First

Logs are a security instrument. They must be treated with the same rigor as production data:
- Log storage must be write-once, append-only — preventing retroactive modification.
- Log access must be access-controlled — not everyone with production database access should have log query access.
- Logs must not contain data that would be useful to an attacker (internal hostnames, infrastructure topology, error message details that reveal implementation).

---

## 3. Logging Architecture

### 3.1 End-to-End Logging Pipeline

```
+------------------------------------------------------------------+
|                     APPLICATION LAYER                            |
|                                                                  |
|  +----------+  +---------+  +----------+  +---------+           |
|  |  Auth    |  | Project |  |    AI    |  |  Repo   |  ...      |
|  |  Module  |  |  Mgmt   |  |  Engine  |  |  Intel  |           |
|  +----+-----+  +----+----+  +----+-----+  +----+----+           |
|       |              |           |              |                |
+-------|--------------|-----------|--------------|----------------+
        |              |           |              |
        +----+---------+-----------+--------------+
             |
             v
+------------------------------------------------------------------+
|                       LOGGER LAYER                               |
|                                                                  |
|  Structured logger (module-aware, context-injecting)            |
|                                                                  |
|  Responsibilities:                                               |
|  - Attach standard context fields (traceId, requestId,           |
|    tenantId, userId, module)                                     |
|  - Apply sensitive data redaction (blocklist enforcement)        |
|  - Apply log level filters per environment                       |
|  - Serialize to JSON                                             |
+---------------------------+--------------------------------------+
                            |
                            v
+------------------------------------------------------------------+
|                  STRUCTURED LOG EVENT                            |
|                                                                  |
|  {                                                               |
|    "timestamp": "2026-07-29T11:53:02.347Z",                     |
|    "level": "INFO",                                              |
|    "service": "devflow-backend",                                 |
|    "module": "project-management",                               |
|    "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",               |
|    "requestId": "req_01h9abc123def456",                          |
|    "tenantId": "org_acme",                                       |
|    "userId": "usr_abc123",                                       |
|    "operation": "CreateProject",                                 |
|    "message": "Project created successfully",                    |
|    "duration": 142                                               |
|  }                                                               |
+---------------------------+--------------------------------------+
                            |
                            v
+------------------------------------------------------------------+
|                   LOG AGGREGATION LAYER                          |
|                      (Grafana Alloy)                             |
|                                                                  |
|  Responsibilities:                                               |
|  - Collect log streams from all application instances            |
|  - Add infrastructure-level labels (host, region, pod)          |
|  - Apply log routing (application logs vs. audit logs)          |
|  - Forward to storage layer                                      |
+---------------------------+--------------------------------------+
                            |
             +--------------+--------------+
             |                             |
             v                             v
+---------------------+     +-------------------------------+
|   APPLICATION LOG   |     |        AUDIT LOG              |
|      STORAGE        |     |         STORAGE               |
|   (Grafana Loki)    |     |  (Append-only, tamper-proof)  |
|                     |     |                               |
|  Retention: 90 days |     |  Retention: 2 years minimum  |
+----------+----------+     +---------------+---------------+
           |                                |
           +----------------+---------------+
                            |
                            v
+------------------------------------------------------------------+
|                  SEARCH & ANALYTICS LAYER                        |
|                    (Grafana / LogQL)                             |
|                                                                  |
|  - Full-text and field-based log search                          |
|  - Cross-service log correlation by traceId / requestId         |
|  - Dashboard integration (log volume, error rates, latency)     |
|  - Ad-hoc investigation queries                                  |
+---------------------------+--------------------------------------+
                            |
                            v
+------------------------------------------------------------------+
|                      ALERTING LAYER                              |
|              (Grafana Alerting / PagerDuty)                      |
|                                                                  |
|  - Error rate thresholds on ERROR/FATAL log patterns             |
|  - Security event alerts (repeated AUTH_FAILED, AUTHZ_DENIED)   |
|  - Performance degradation alerts (slow query rates)             |
|  - Anomaly detection (spike in 5xx error volume)                 |
+------------------------------------------------------------------+
```

### 3.2 Layer Responsibilities

| Layer | Responsibility | Owner |
| :--- | :--- | :--- |
| **Application Modules** | Emit log events at the appropriate level with full context. Apply no filtering or serialization — that is the logger's job. | Engineering teams per module |
| **Logger** | Inject context fields (traceId, requestId, tenantId, userId) from the active Security Context; apply sensitive data redaction; serialize to JSON; route to the aggregation pipeline. | Shared Kernel / cross-cutting concern |
| **Aggregation (Grafana Alloy)** | Collect, label, route, and batch log streams from all running application instances. Separate application logs from audit logs. | Platform/DevOps |
| **Storage (Grafana Loki)** | Persist log streams with label-based indexing for efficient log queries. Application and audit logs are stored in separate streams with distinct retention policies. | Platform/DevOps |
| **Search & Analytics** | Provide operators and engineers with query interfaces to investigate incidents, analyze error distributions, and monitor log-derived health signals. | All teams |
| **Alerting** | Translate log-derived signals (error rate spikes, security patterns) into actionable notifications delivered to on-call engineers and security teams. | Platform/Security |

---

## 4. Log Levels

Log levels represent the **severity and operational significance** of an event. They are not cosmetic — they are routing signals. Level-based filtering determines what reaches storage in each environment, and level-based alerting determines what wakes up an on-call engineer.

### 4.1 Level Definitions

| Level | Severity | When to Use | Production Action |
| :--- | :--- | :--- | :--- |
| `TRACE` | Lowest | Extremely fine-grained diagnostic information: individual method entry/exit, loop iterations, decision branch paths. Used temporarily during active debugging of a specific defect. | **Never emitted.** Filtered out at the logger layer in all persistent environments. |
| `DEBUG` | Low | Diagnostic information useful for understanding system behavior during development and testing: request payload summaries, resolved permission sets, cache hit/miss decisions, internal state transitions. | **Not emitted in production.** Emitted in development and staging environments. |
| `INFO` | Informational | Normal, expected business and operational events that confirm the system is working as designed: request completion, resource created, background job started/completed, external API call succeeded, module lifecycle events (startup, shutdown). | **Emitted and stored.** Forms the operational heartbeat of the running system. |
| `WARN` | Warning | Something unexpected or potentially problematic occurred, but the system handled it gracefully and the operation completed successfully: degraded mode activated, retry succeeded after initial failure, approaching a resource limit (e.g., 80% of subscription seat limit), a deprecated API version was called, a configuration value is using a non-recommended default. | **Emitted, stored, and visible on dashboards.** May trigger low-priority alerts when volume is anomalous. |
| `ERROR` | Error | An operation failed in a way that affected a specific request or task. The system recovered (the error was handled), but the client received an error response or a background job failed: unhandled exception on a request, external API call failed after all retries, database query failed, AI inference timed out. | **Emitted, stored, and actively monitored.** Elevated error rates trigger on-call alerts. |
| `FATAL` | Highest | **Not used as an application log level in DevFlow.** True fatal conditions (JVM crash, out of memory, unrecoverable startup failure) are captured by the runtime environment and infrastructure monitoring — not by the application logger. Application code that encounters an unrecoverable state logs at `ERROR` and terminates its operation gracefully; it does not assume the entire process must halt. | N/A |

### 4.2 Level Usage Rules

```
Is this event part of normal, successful system operation?
  |
  +-- Yes --> Is it a major business/lifecycle event (request completed,
  |            resource created, job finished)?
  |             |
  |             +-- Yes --> INFO
  |             +-- No  --> (too granular for production) --> DEBUG
  |
  +-- No  --> Did the system recover and complete the operation anyway?
                |
                +-- Yes --> Is it a significant degradation signal?
                |             |
                |             +-- Yes --> WARN
                |             +-- No  --> DEBUG
                |
                +-- No  --> ERROR
```

### 4.3 Environment-Level Filtering

| Environment | Minimum Level Emitted | Rationale |
| :--- | :--- | :--- |
| **Local Development** | `DEBUG` | Developers need full diagnostic output |
| **Staging / QA** | `DEBUG` | Test engineers need debug context for test failures |
| **Production** | `INFO` | DEBUG and TRACE add noise and storage cost with no production value |
| **Production (Security Alert)** | `INFO` | No change — security events are at INFO and above by design |

---

## 5. Structured Log Format

### 5.1 Canonical Log Event

Every log event emitted anywhere in DevFlow conforms to this field schema. No module may omit mandatory fields or introduce field names that conflict with this schema.

```json
{
  "timestamp": "2026-07-29T11:53:02.347Z",
  "level": "ERROR",
  "service": "devflow-backend",
  "module": "repository-intelligence",
  "tenantId": "org_acme_engineering",
  "userId": "usr_01h9abc123def456",
  "requestId": "req_01h9abc123def456",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "operation": "SyncRepository",
  "message": "Repository sync failed after 3 retry attempts. GitHub API returned 403 Forbidden.",
  "errorCode": "EXTERNAL_PROVIDER_FORBIDDEN",
  "duration": 8421,
  "metadata": {
    "repositoryId": "repo_abc123",
    "provider": "GITHUB",
    "retryCount": 3,
    "httpStatus": 403
  }
}
```

### 5.2 Field Reference

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `timestamp` | ISO-8601 UTC string | **Always** | The moment the log event was generated, in UTC with millisecond precision. Never local time; never epoch milliseconds. Consistent format enables cross-service chronological ordering. |
| `level` | enum string | **Always** | One of `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`. Drives log routing, storage filtering, and alerting rule evaluation. |
| `service` | string | **Always** | The name of the deployable unit that emitted the event. For the current Modular Monolith: `"devflow-backend"`. As services are extracted in future phases, this becomes the microservice name (e.g., `"devflow-ai-engine"`). |
| `module` | string | **Always** | The Bounded Context or module within the service (e.g., `"project-management"`, `"auth"`, `"ai-engine"`, `"repository-intelligence"`). This is the primary routing label for log search and dashboard segmentation per domain. |
| `tenantId` | string (UUID) | **Always when authenticated** | The `organizationId` of the active tenant from the Security Context. Null/absent only for unauthenticated public endpoints. Critical for multi-tenant log isolation — support engineers must be able to filter all log events for a specific organization. |
| `userId` | string (UUID) | **Always when authenticated** | The UUID of the authenticated user. Null/absent for system-initiated background jobs. Combined with `tenantId`, this uniquely identifies the principal responsible for triggering the logged event. |
| `requestId` | string | **Always for HTTP requests** | The unique identifier for the HTTP request, mirroring the `X-Request-ID` header. Links this log event to the corresponding API error response (defined in the Error Response Format document). Null/absent for background job log events not originating from an HTTP request. |
| `traceId` | string | **Always** | The W3C Trace Context trace ID, propagated through all module boundaries, background tasks, and external dependency calls originating from a single root request or triggered event. The primary cross-module correlation key. |
| `spanId` | string | **Always** | The W3C Trace Context span ID for the specific unit of work represented by this log event. Combined with `traceId`, enables reconstruction of the full causal tree in the distributed trace viewer. |
| `operation` | string | **Always** | A stable, human-readable identifier for the operation being logged (e.g., `"CreateProject"`, `"SyncRepository"`, `"ExecuteAiReview"`, `"RefreshToken"`). Follows `PascalCase`. Used for operation-level dashboards and performance analysis. Stable across releases — changing an operation name is a breaking change for dashboards. |
| `message` | string | **Always** | A plain-English description of what happened. Written for a developer reading the log during an incident. Must be specific, actionable, and free of raw exception messages or internal system details. |
| `errorCode` | string | **On error events** | The machine-readable error code from the Error Response Format taxonomy (e.g., `VALIDATION_FAILED`, `EXTERNAL_PROVIDER_FORBIDDEN`). Absent on non-error log events. Enables log-based error rate dashboards broken down by error type. |
| `duration` | integer (milliseconds) | **On completed operations** | The elapsed time in milliseconds from the start to the completion of the logged operation. Populated on `INFO` completion events and `ERROR` failure events. The primary source for performance dashboards and SLO tracking. |
| `metadata` | object | **Contextual** | A free-form nested object for operation-specific contextual fields that do not belong in the standard schema (e.g., `repositoryId`, `provider`, `retryCount`, `cycleState`). All keys within `metadata` follow `camelCase`. Sensitive fields are redacted before they reach this object. |

### 5.3 System Context Fields (Infrastructure-Added)

The following fields are **not emitted by application code** — they are attached by the log aggregation layer (Grafana Alloy) from the hosting environment:

| Field | Source | Description |
| :--- | :--- | :--- |
| `host` | OS hostname | The name of the host or container instance that produced the log |
| `region` | Cloud provider metadata | The deployment region (e.g., `ap-south-1`, `eu-west-1`) |
| `pod` | Kubernetes pod name | The pod identifier in Kubernetes deployments |
| `environment` | Deployment label | One of `production`, `staging`, `development` |

---

## 6. Correlation & Distributed Tracing

### 6.1 The Correlation Problem in a Modular System

DevFlow's Modular Monolith processes requests across multiple Bounded Contexts in a single operation. A project creation request may:
1. Enter the Security Filter Chain (Auth module).
2. Proceed to the Project Management module.
3. Emit a `ProjectCreatedEvent` consumed by the Notification module.
4. Emit a second event consumed by the Workflow Automation module.
5. Trigger an async AI enrichment job in the AI Engine module.

If each of these steps logs independently with no shared identifier, there is no way to reconstruct the full picture of what happened in response to one user's action. Correlation is what transforms isolated log events into a coherent operational narrative.

### 6.2 Correlation Identifier Model

```
+---------------------------------------------------------------------+
|              CORRELATION IDENTIFIER HIERARCHY                       |
|                                                                     |
|  SINGLE HTTP REQUEST                                                |
|                                                                     |
|  requestId: req_01h9abc           <- Identifies this exact request  |
|  traceId:   4bf92f3577b34da6a    <- Root trace shared by ALL ops   |
|  spanId:    00f067aa0ba902b7     <- This specific unit of work      |
|                                                                     |
|  HTTP Request -> Auth Filter -> PM Module -> Notification Module    |
|       |               |              |               |              |
|   [spanId: A]    [spanId: B]    [spanId: C]     [spanId: D]         |
|       |               |              |               |              |
|   traceId: 4bf...  traceId: 4bf...  traceId: 4bf...  traceId: 4bf..|
|                                                                     |
|  Same traceId links all spans across all modules.                   |
|  spanId changes per unit of work.                                   |
|  requestId is only present on the entry HTTP request span.          |
+---------------------------------------------------------------------+
```

### 6.3 Request ID

- **Scope:** Single HTTP request-response cycle.
- **Origin:** Generated by the client or by the API gateway if absent; echoed in the response `X-Request-ID` header and in the error response body.
- **Log use:** Links a specific API error response (and its `requestId`) to the exact server log entry for that request. Enables a support engineer to find the full log context from a user-reported error in seconds.
- **Propagation:** Present only in log events generated during synchronous HTTP request processing. Not propagated to asynchronous downstream events.

### 6.4 Trace ID

- **Scope:** The full causal chain of a request, including all synchronous and asynchronous downstream effects.
- **Origin:** Generated by the API gateway at the entry point of every request (W3C Trace Context `traceparent` format).
- **Log use:** The primary cross-module correlation key. Querying logs by `traceId` returns every log event from every module that participated in processing the original request — including async event consumers that ran minutes later.
- **Propagation:** Injected into the thread context at the API entry point and propagated through all module calls, Spring `ApplicationEventPublisher` events, and background task executors.

### 6.5 Span ID

- **Scope:** A single unit of work within the trace (a method invocation, a database query, an external API call).
- **Origin:** Generated by the tracing instrumentation for each new unit of work.
- **Log use:** Used in the distributed trace viewer to reconstruct the exact sequence and nesting of operations within a trace. Each log event's `spanId` maps it to a node in the trace tree.

### 6.6 Background Jobs and Async Operations

Background jobs (repository sync, AI enrichment, analytics aggregation) are not initiated by a direct HTTP request from the client — they are triggered by domain events or scheduled tasks. Correlation for background jobs follows this model:

```
HTTP Request                    Domain Event                  Background Job
(traceId: T1)
    |
    +-- Emits event -----------> EventPublisher
    |   (carries traceId: T1)        |
    |                                +-- Consumed by Job
    |                                    (inherits traceId: T1)
    |                                    (generates new spanId)
    |                                    |
    |                                    +-- Logs carry traceId: T1
```

A background job that is triggered by a domain event inherits the `traceId` of the originating event. This means that even if the repository sync runs 30 minutes after the project creation request that triggered it, its log events share the same `traceId` and can be correlated back to the original user action.

For **scheduled jobs** with no originating HTTP request (e.g., nightly analytics aggregation), a new synthetic `traceId` is generated at job start and shared across all log events produced by that job execution.

---

## 7. Audit Logging

### 7.1 Audit Logs vs. Application Logs

Audit logs and application logs share the same structured format but serve fundamentally different purposes and are subject to different architectural constraints:

| Dimension | Application Logs | Audit Logs |
| :--- | :--- | :--- |
| **Purpose** | Operational debugging, performance monitoring, incident investigation | Compliance evidence, accountability, security forensics |
| **Mutability** | May be pruned or rotated after retention period | **Immutable and append-only.** Never deleted before the minimum retention period. |
| **Audience** | Engineering, platform operations | Compliance, security, legal, auditors |
| **Content** | System behavior, errors, performance | Who performed what privileged action, on what resource, at what time |
| **Volume** | High (every request, every operation) | Low (privileged events only) |
| **Retention** | 90 days (application); 180 days (security) | Minimum 2 years |
| **Access control** | Engineering and ops teams | Separate access tier — compliance and security only |
| **Storage** | Grafana Loki (application stream) | Grafana Loki (dedicated audit stream) + cold archive |

### 7.2 Events That Require Audit Logging

Audit log events are a strict superset of the security events described in Section 8. Every privileged, consequential, or compliance-relevant action must produce an audit log entry:

| Domain | Event | Audit Fields |
| :--- | :--- | :--- |
| **Authentication** | Successful login | userId, tenantId, method (password/Google/GitHub), IP, userAgent, timestamp |
| **Authentication** | Failed login | emailAttempted, IP, userAgent, failureReason, timestamp |
| **Authentication** | Password reset completed | userId, IP, timestamp |
| **Authentication** | Token revoked | userId, deviceSessionId, revokedBy, reason, timestamp |
| **Authorization** | Permission denied | userId, tenantId, operation, resourceType, resourceId, denialReason, timestamp |
| **Authorization** | Cross-tenant access attempt | userId, jwtOrgId, resourceOrgId, resourceId, timestamp |
| **Roles & Access** | Role assigned | adminUserId, targetUserId, tenantId, roleGranted, timestamp |
| **Roles & Access** | Role changed | adminUserId, targetUserId, tenantId, oldRole, newRole, timestamp |
| **Roles & Access** | Member removed from organization | adminUserId, removedUserId, tenantId, timestamp |
| **Roles & Access** | Invitation revoked | adminUserId, inviteeEmail, tenantId, timestamp |
| **Organization** | Organization created | ownerUserId, orgId, orgName, timestamp |
| **Organization** | Organization deleted | ownerUserId, orgId, timestamp |
| **Organization** | Ownership transferred | fromUserId, toUserId, orgId, timestamp |
| **Project** | Project deleted | userId, orgId, projectId, projectName, timestamp |
| **Project** | Project archived | userId, orgId, projectId, timestamp |
| **Repository** | Repository connected | userId, orgId, repositoryId, provider, timestamp |
| **Repository** | Repository disconnected | userId, orgId, repositoryId, timestamp |
| **Billing** | Subscription plan changed | userId, orgId, oldPlan, newPlan, timestamp |
| **Billing** | Payment method updated | userId, orgId, timestamp (no card data) |
| **AI Actions** | AI review triggered on PR | userId, orgId, prId, repositoryId, timestamp |
| **AI Actions** | AI configuration changed | adminUserId, orgId, settingChanged, oldValue, newValue, timestamp |
| **Administrative** | Emergency access initiated | platformAdminId, orgId, approvedBy, reason, expiresAt, timestamp |
| **Administrative** | Emergency access actions taken | platformAdminId, orgId, actionsPerformed[], timestamp |

### 7.3 Audit Log Integrity Guarantees

- **Write-once:** Audit log entries are written as append-only records. No update or delete operation exists on audit log entries.
- **Real-time shipping:** Audit events are shipped to the dedicated Loki audit stream synchronously — within the same transaction boundary where possible, or via an outbox pattern to guarantee eventual delivery.
- **Tamper detection:** Audit logs are structured such that any retroactive modification would be detectable (sequential IDs, hash chaining in the cold archive tier).
- **Separate access control:** The audit log stream requires a distinct access credential from the application log stream. An engineer who can query application logs cannot automatically query audit logs.

---

## 8. Security Logging

### 8.1 Security Log Stream

Security events are a subset of application logs that carry heightened significance. They are emitted at `WARN` or `ERROR` level and are tagged with a `"category": "SECURITY"` metadata field that enables the security log stream to be queried, monitored, and alerted on independently.

### 8.2 Security Event Taxonomy

| Event | Level | Code | Trigger |
| :--- | :--- | :--- | :--- |
| **Failed login** | `WARN` | `AUTH_LOGIN_FAILED` | Invalid credentials presented |
| **Account locked** | `WARN` | `AUTH_ACCOUNT_LOCKED` | Consecutive failed login threshold exceeded |
| **Token expired** | `INFO` | `AUTH_TOKEN_EXPIRED` | JWT validated but past expiry |
| **Token signature invalid** | `WARN` | `AUTH_TOKEN_INVALID_SIGNATURE` | JWT presented with invalid RS256 signature |
| **Token revoked (blocklist hit)** | `WARN` | `AUTH_TOKEN_REVOKED` | JWT's `jti` found in Redis revocation registry |
| **Refresh token reuse (theft signal)** | `ERROR` | `AUTH_REFRESH_TOKEN_REUSE` | A consumed refresh token was presented again — full session family revoked |
| **Permission denied** | `WARN` | `AUTHZ_FORBIDDEN` | Authenticated user lacks required permission |
| **Cross-tenant access attempt** | `ERROR` | `AUTHZ_CROSS_TENANT` | JWT `orgId` does not match resource `organizationId` |
| **Privilege escalation attempt** | `ERROR` | `AUTHZ_ESCALATION_ATTEMPT` | User attempted to grant a role they do not hold |
| **Rate limit exceeded** | `WARN` | `RATE_LIMIT_EXCEEDED` | Client exceeded request quota for an endpoint |
| **Brute-force pattern detected** | `ERROR` | `SECURITY_BRUTE_FORCE` | Anomalous repeated failed login pattern from an IP |
| **Suspicious geographic login** | `WARN` | `SECURITY_GEO_ANOMALY` | Login from a new country or dramatically different geolocation |
| **Emergency access activated** | `ERROR` | `SECURITY_EMERGENCY_ACCESS` | Platform admin emergency access initiated |
| **API abuse pattern** | `ERROR` | `SECURITY_API_ABUSE` | Automated API abuse pattern detected (scraping, enumeration) |

### 8.3 Security Alerting Rules

Security log events drive real-time alerting in addition to storage:

| Pattern | Alert Threshold | Severity |
| :--- | :--- | :--- |
| `AUTH_LOGIN_FAILED` from single IP | 10 failures in 5 minutes | HIGH |
| `AUTHZ_CROSS_TENANT` any occurrence | 1 event | CRITICAL |
| `AUTH_REFRESH_TOKEN_REUSE` any occurrence | 1 event | HIGH |
| `SECURITY_BRUTE_FORCE` any occurrence | 1 event | HIGH |
| `AUTHZ_ESCALATION_ATTEMPT` any occurrence | 1 event | CRITICAL |
| `SECURITY_EMERGENCY_ACCESS` any occurrence | 1 event | CRITICAL |
| `AUTH_ACCOUNT_LOCKED` rate | 20 accounts in 10 minutes | HIGH |

---

## 9. Sensitive Data Policy

### 9.1 The Non-Negotiable Blocklist

The following categories of data **must never appear in any log event at any log level in any environment** — including local development, staging, and production. Violation of this policy creates a persistent privacy and security liability regardless of where the log is stored.

| Category | Examples | Why Prohibited |
| :--- | :--- | :--- |
| **Authentication credentials** | Passwords, PINs, security questions | Direct account compromise if logs are accessed |
| **Authentication tokens** | JWT access tokens, refresh tokens, email verification tokens, password reset tokens | Token theft enables session hijacking |
| **API keys & secrets** | DevFlow PATs, OAuth client secrets, GitHub tokens, LLM provider API keys | Service account compromise |
| **OAuth artifacts** | OAuth authorization codes, `code_verifier` values, state parameters | Authorization code interception |
| **Cryptographic material** | Private signing keys, symmetric encryption keys, TLS private keys | Full platform cryptographic compromise |
| **Payment data** | Credit card numbers, CVVs, bank account numbers | PCI-DSS violation |
| **Government identifiers** | National ID numbers, tax identifiers, passport numbers | Identity fraud enablement |
| **Full email addresses in error paths** | `"User 'alice@company.com' not found"` | User enumeration; PII exposure |
| **AI prompt content** | User-submitted prompts containing proprietary code or business logic | Confidential IP exposure; GDPR data minimization |
| **Repository contents** | Code snippets, file contents from user repositories | Confidential IP exposure |
| **Internal infrastructure** | Database connection strings, internal hostnames, IP addresses | Attack surface exposure |

### 9.2 Redaction Rules

When a value in the `metadata` or operation context would normally contain a sensitive field, the following redaction rules apply:

| Rule | Example |
| :--- | :--- |
| **Full omission** | Password fields are never written to the log object at all |
| **Prefix truncation** | Tokens are logged as the first 8 characters followed by `...REDACTED` (e.g., `"eyJhbGci...REDACTED"`) — enough to confirm a token was present without exposing its value |
| **Email masking** | Email addresses are masked as `al***@company.com` in non-audit contexts |
| **Field replacement** | Sensitive fields are replaced with a placeholder: `"[REDACTED]"` |

### 9.3 Redaction Enforcement

Redaction is not the responsibility of individual developers writing log statements. It is enforced **systematically at the Logger layer** through:
- A registered blocklist of field names that are automatically redacted whenever they appear as keys in the metadata object (e.g., any field named `password`, `token`, `secret`, `key`, `authorization`).
- A log sanitizer that scans string values for patterns matching JWTs (three Base64 segments separated by periods), credit card numbers, and email addresses in non-approved positions.

---

## 10. Performance Logging

### 10.1 Purpose

Performance logs provide the data required to measure, track, and improve system performance over time. They enable the identification of slow operations before they breach SLOs, detection of performance regressions introduced by new code, and evidence for capacity planning decisions.

### 10.2 Performance Event Categories

#### Slow API Requests

Every HTTP request emits an `INFO` completion log event with a `duration` field. Requests that exceed the platform's defined latency thresholds emit an additional `WARN` performance event:

| Endpoint Category | Warning Threshold | Critical Threshold |
| :--- | :--- | :--- |
| Standard REST endpoints | > 500ms | > 2,000ms |
| AI inference endpoints | > 5,000ms | > 15,000ms |
| Repository sync operations | > 30,000ms | > 120,000ms |
| Analytics aggregation | > 10,000ms | > 60,000ms |

#### Slow Database Queries

All database queries exceeding a defined threshold emit a `WARN` event including the query operation name, the affected module, the `traceId`, and the execution duration. Raw SQL is never included in the log event. The log event identifies the operation (e.g., `"ListProjectTasks"`) that produced the slow query, not the SQL text.

#### AI Inference Performance

AI Engine operations log the following performance-relevant fields on each inference call:
- `promptTokenCount`: Number of tokens in the assembled prompt
- `completionTokenCount`: Number of tokens in the model's response
- `inferenceLatency`: Time from prompt submission to first token received (TTFB)
- `totalDuration`: Full round-trip time including response streaming

This data feeds AI cost monitoring dashboards and informs prompt optimization efforts.

#### Background Job Performance

Every background job (repository sync, analytics calculation, AI enrichment) logs:
- A start event at `INFO` level when the job begins
- A completion event at `INFO` level with total `duration` on success
- An `ERROR` event with failure context if the job fails
- Intermediate progress events at `DEBUG` level for long-running jobs

#### Cache Performance

Cache interactions log hit/miss outcomes to enable cache effectiveness monitoring:
- Cache hits: `DEBUG` (high volume; not production-critical)
- Cache misses: `DEBUG`
- Cache invalidation events: `INFO`
- Sustained low cache hit rate detection: `WARN` (a derived alert, not a per-miss log)

#### External API Latency

Every call to an external dependency (GitHub API, LLM provider, email provider) logs:
- The external provider (abstracted, not the raw hostname): e.g., `"provider": "GITHUB"`, `"provider": "LLM_PROVIDER"`
- The operation attempted: e.g., `"operation": "FetchPullRequests"`
- The outcome: success, error, timeout
- The `duration`
- The HTTP status code received (if applicable)

---

## 11. Error Logging

### 11.1 Expected vs. Unexpected Errors

The error logging strategy distinguishes between two fundamentally different classes of errors, because they require different investigation approaches and different alerting behavior:

| Class | Nature | Log Level | `details` | Stack Trace | Alerting |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Expected errors** | Anticipated failure cases: validation failures, business rule violations, resource not found, permission denied | `INFO` or `WARN` | Full error context | Never | Rate-based (high volume of specific error type) |
| **Unexpected errors** | Unhandled exceptions, programming defects, infrastructure failures, contract violations | `ERROR` | Full exception chain | Yes (server-side only) | Immediate (any occurrence above threshold) |

### 11.2 Expected Error Logging

Expected errors (validation failures, authentication errors, authorization denials, resource not found, business rule violations) are **not anomalies** — they are a normal part of the system's operation. Their occurrence at expected rates is a signal of correct behavior. They must be logged at `INFO` or `WARN` level with the error code and relevant context, but they must not trigger on-call alerts on their own.

Example: A `VALIDATION_FAILED` event on the task creation endpoint is expected whenever a client submits an incomplete form. Logging it at `ERROR` would pollute the error rate dashboards and create alert fatigue.

### 11.3 Unexpected Error Logging

Unexpected errors represent defects, infrastructure failures, or contract violations. Every unexpected error must be logged at `ERROR` level with:
- The full exception chain (including cause chains) — **server-side only, never in the API response**
- The full request context at time of failure
- The `traceId` and `requestId`
- The module and operation where the error originated

### 11.4 Stack Trace Policy

Stack traces are:
- **Logged server-side** in full for `ERROR` level events, attached as a structured `exception` field.
- **Never included in API responses** (covered in the Error Response Format document).
- **Logged at `DEBUG` level only** for expected error types where the stack trace provides no additional diagnostic value beyond the error message.

### 11.5 Retry Logging

When an operation is retried after a transient failure, each retry attempt is logged with:
- The attempt number (`attempt: 2 of 3`)
- The failure reason for the previous attempt
- The backoff duration before the next attempt
- The final outcome (success or exhausted retries)

Retry sequences use the same `traceId` as the original operation so the full retry history is visible in a single trace query.

### 11.6 Root Cause Preservation

The logging system preserves the original cause chain of nested exceptions. When an infrastructure failure causes a business operation to fail, the log event captures both:
- The operation-level error (e.g., `"Repository sync failed"`)
- The root cause chain leading to it (e.g., `"PostgreSQL connection timeout after 5000ms"`)

This allows operators to distinguish between "the repository sync failed because of a bug in the sync logic" and "the repository sync failed because the database was temporarily unavailable."

---

## 12. Log Retention

### 12.1 Retention Policy by Log Stream

| Stream | Retention Period | Archive | Rationale |
| :--- | :--- | :--- | :--- |
| **Application logs** | 90 days hot storage | Optional cold archive | Sufficient for debugging recent incidents and performance analysis |
| **Security event logs** | 180 days hot storage | 1 year cold archive | Security incidents may surface weeks after the triggering event |
| **Audit logs** | 2 years hot storage | 5 years cold archive | SOC 2 Type II, ISO 27001, and contractual enterprise audit requirements |
| **Performance logs** | 90 days hot storage | None | Performance baselines rarely require data older than 90 days |
| **Debug logs (staging)** | 14 days | None | Short-lived; development debugging only |

### 12.2 Compliance Considerations

Enterprise customers in regulated industries (fintech, healthcare, legal) may require extended audit log retention beyond the platform defaults. The logging architecture supports per-organization retention policy overrides in the cold archive tier, allowing audit logs for specific organizations to be retained for up to 7 years.

Retention policies must comply with the following constraints:
- **GDPR (Right to Erasure):** When a user account is deleted at their request, their `userId` may appear in audit logs. The architecture distinguishes between retention for compliance purposes (audit log integrity) and personal data processing. Legal hold overrides GDPR erasure for audit logs where legally required; otherwise, personal identifiers in expired log entries are masked in the cold archive tier on a configurable schedule.
- **Data residency:** Log storage regions are configurable at the organization level for enterprise customers with regional data residency requirements.

### 12.3 Archival

Logs past their hot storage retention period are:
1. Compressed and moved to cold storage (object storage — S3-compatible).
2. Indexed by `tenantId` and time range for targeted retrieval.
3. Not directly queryable from Grafana — retrieval requires a manual archival query process with access control.

### 12.4 Deletion Policy

- Application and performance logs are automatically deleted after their hot storage retention period unless placed under a legal hold.
- Audit logs are never deleted before the minimum retention period under any circumstances. After the retention period, deletion requires a documented compliance review and approval.
- Security event logs follow the security log retention policy and may be extended by a security incident hold.

---

## 13. Monitoring Integration

### 13.1 The Observability Triad

Logs are one of three pillars of the observability model. They provide the **narrative** of what happened, while metrics provide the **quantitative signals** and traces provide the **structural map** of execution:

```
+-------------------------------------------------------------------+
|                     OBSERVABILITY TRIAD                           |
|                                                                   |
|  +------------------+  +------------------+  +----------------+  |
|  |      LOGS        |  |     METRICS      |  |    TRACES      |  |
|  |                  |  |                  |  |                |  |
|  | Detailed event   |  | Quantified       |  | Causal map of  |  |
|  | narrative        |  | system state     |  | request        |  |
|  |                  |  |                  |  | execution      |  |
|  | "What happened   |  | "How many        |  | "Which path    |  |
|  |  and why?"       |  |  times and how   |  |  did it take?" |  |
|  |                  |  |  fast?"          |  |                |  |
|  | Source: App code |  | Source: Micrometer|  | Source: OTel  |  |
|  | Storage: Loki    |  | Storage: Prometheus|  | Storage: Tempo|  |
|  | Query: LogQL     |  | Query: PromQL    |  | Query: TraceQL |  |
|  +--------+---------+  +--------+---------+  +-------+--------+  |
|           |                     |                    |           |
|           +---------------------+--------------------+           |
|                                 |                                |
|                                 v                                |
|                    +------------------------+                    |
|                    |    GRAFANA DASHBOARDS  |                    |
|                    |    & ALERTING          |                    |
|                    +------------------------+                    |
+-------------------------------------------------------------------+
```

### 13.2 Why Logs Alone Are Insufficient

| Limitation | Why It Matters | What Compensates |
| :--- | :--- | :--- |
| **High storage volume** | Logging every event at full fidelity is expensive at scale | Metrics aggregate patterns cheaply (e.g., request rate per second without logging every request) |
| **No time-series analysis** | Log queries are event-based, not time-series — calculating "average latency over time" from logs is expensive | Metrics provide efficient time-series computation (Prometheus counters, histograms, gauges) |
| **No structural call map** | Logs show individual events but not the parent-child relationship of nested calls | Distributed traces provide the exact execution tree |
| **Alert latency** | Log-based alerts require log ingestion, indexing, and query evaluation — introducing latency | Metric-based alerts on counters and gauges fire within seconds |
| **No saturation view** | Logs describe what happened, not how close the system is to capacity | Metrics provide resource utilization (CPU, memory, connection pool saturation) |

### 13.3 Log-Derived Metrics

Logs feed several Prometheus metric derivations via the Grafana Alloy pipeline:
- **Error rate:** Count of `level=ERROR` log events per minute, grouped by `module` and `operation`
- **Business error rate:** Count of specific `errorCode` values per minute (e.g., `VALIDATION_FAILED` rate)
- **Security event rate:** Count of `category=SECURITY` log events grouped by security event code
- **Slow operation rate:** Count of log events exceeding latency thresholds, grouped by `operation`

### 13.4 Alerting Integration

Log-derived alerts are configured in Grafana Alerting and route to PagerDuty for on-call notification:

| Alert Condition | Source | Routing |
| :--- | :--- | :--- |
| Error rate > 5% for 2 consecutive minutes | Log-derived metric | On-call engineer (P2) |
| Any `AUTHZ_CROSS_TENANT` event | Direct log alert | Security team (P1) |
| Any `AUTH_REFRESH_TOKEN_REUSE` event | Direct log alert | Security team (P1) |
| Any `SECURITY_EMERGENCY_ACCESS` event | Direct log alert | Security lead (P0) |
| AI inference error rate > 10% | Log-derived metric | On-call engineer (P2) |
| Sustained slow query rate > 20% | Log-derived metric | On-call engineer (P3) |

### 13.5 Incident Response Workflow

```
Alert fires
    |
    v
On-call engineer receives PagerDuty notification
    |
    +-- Contains: alert condition, time, affected module
    |
    v
Engineer opens Grafana dashboard
    |
    +-- Metrics dashboard: identifies magnitude of impact
    |   (error rate, affected users, affected tenants)
    |
    +-- Log search: queries by operation + time range
    |   Returns individual log events for the error pattern
    |
    +-- Picks any log event, copies traceId
    |
    +-- Trace viewer: reconstructs full execution chain
    |   for the failing operation across all modules
    |
    +-- Root cause identified from trace + log detail
    |
    v
Remediation action taken
    |
    v
Post-incident: audit log queried for impact assessment
    (Which tenants were affected? Which users? What operations?)
```

---

## 14. Future Evolution

### 14.1 OpenTelemetry (OTel) Full Adoption

DevFlow's logging architecture is designed for full OpenTelemetry adoption. The current structured log format uses W3C Trace Context identifiers (`traceId`, `spanId`) — the same identifiers OTel uses. Migration to OTel-native log emission would be a **non-breaking infrastructure change**: the log fields are compatible, and the aggregation pipeline (Grafana Alloy) already supports the OTel protocol.

OTel adoption enables:
- Vendor-neutral log, metric, and trace export from a single instrumentation layer.
- Automatic context propagation across library boundaries without manual injection.
- Standardized semantic conventions for log field names across all services.

### 14.2 SIEM Integration

Security Information and Event Management (SIEM) platforms (Splunk, Microsoft Sentinel, Elastic SIEM) require a structured, real-time feed of security-relevant log events. DevFlow's architecture supports SIEM integration through:
- A dedicated security log stream (category=SECURITY) that can be forwarded from Grafana Alloy to any SIEM platform via standard protocols (Syslog, Kafka, HTTP).
- Machine-readable `code` values in security log events enabling SIEM correlation rules without natural language parsing.
- Immutable audit log records that can be fed into a SIEM for compliance reporting without risk of data tampering.

### 14.3 AI-Assisted Log Analysis

As DevFlow's platform matures, the high volume of structured logs becomes a dataset for AI-assisted analysis:
- **Anomaly detection:** ML models trained on baseline log patterns can identify anomalous error rate spikes, unusual access patterns, or novel failure modes without manual threshold configuration.
- **Root cause clustering:** Similar `errorCode` patterns across multiple tenants can be automatically clustered to identify platform-wide issues before they surface in individual support tickets.
- **Predictive alerting:** Trends in slow query rates, cache miss rates, or external API latency can predict outages before they occur.

The structured, consistent log format established in this document is the prerequisite for any AI-assisted analysis — unstructured logs cannot be reliably fed into ML pipelines.

### 14.4 Centralized Logging at Scale

As DevFlow scales from a Modular Monolith toward potential microservice extraction, the logging architecture scales without restructuring:
- The `service` field in the log event already distinguishes the deployment unit.
- The `module` field maintains the Bounded Context label even after extraction.
- The `traceId` propagation model works across network boundaries, not just within a single JVM.
- The Grafana Alloy aggregation layer scales horizontally to collect from any number of service instances.

### 14.5 Cloud-Native Deployment Logging

In Kubernetes deployments, the logging pipeline adapts:
- Container stdout JSON logs are collected by Grafana Alloy DaemonSet agents.
- Infrastructure-level fields (`pod`, `node`, `namespace`) are automatically attached by the aggregation layer.
- Log-based autoscaling triggers can be configured (e.g., scale up when sustained high error rate suggests overload).

### 14.6 Log-Based Compliance Reporting

Future compliance features will allow enterprise customers to:
- Download audit logs for a specified time period in CSV or JSON format.
- Configure automated compliance reports (e.g., weekly access change summaries).
- Set up custom retention overrides for their organization's regulatory jurisdiction.
- Receive automated alerts for specific audit events (e.g., role changes, data exports).

---

## 15. Architectural Principles & Key Design Decisions

| # | Principle | Rationale |
| :--- | :--- | :--- |
| **1** | **All log events are structured JSON — no exceptions.** Every log statement produces a valid, parseable JSON object. Free-text concatenated log lines are a defect. | Enables machine-based log analysis, alerting, and indexing without per-module parsing logic. A single LogQL query can filter by any field across all modules. |
| **2** | **Context fields are injected by the Logger layer, not by application code.** `traceId`, `requestId`, `tenantId`, and `userId` are attached automatically from the Security Context. | Prevents inconsistent context injection across modules and ensures no log event is ever missing its correlation identifiers. |
| **3** | **`INFO` is the production steady-state level.** `DEBUG` and `TRACE` are never emitted in production under normal conditions. | Eliminates log noise that obscures real signals, reduces storage cost, and improves query performance on production log streams. |
| **4** | **Expected errors and unexpected errors have different log levels.** Validation failures and permission denials log at `INFO`/`WARN`; unhandled exceptions log at `ERROR`. | Prevents expected business behavior from polluting error rate dashboards, while ensuring real defects trigger immediate attention. |
| **5** | **Sensitive data is blocked at the Logger layer, not by developer discipline.** A registered blocklist and pattern scanner prevent tokens, passwords, and keys from reaching log storage. | Security is enforced structurally, not culturally. Developer mistakes are caught before they create a persistent security liability. |
| **6** | **Audit logs and application logs are separate streams with separate access controls.** | Allows engineering teams to query operational logs freely while restricting audit log access to compliance and security roles, as required by SOC 2 and ISO 27001. |
| **7** | **Every background job inherits the `traceId` of its originating event.** A repository sync triggered by a project creation request shares the same `traceId` as the HTTP request that created the project. | Enables full causal chain reconstruction even for operations that execute minutes or hours after the originating user action. |
| **8** | **The `operation` field is a stable, versioned identifier.** Operation names follow `PascalCase` and are treated as a contract — they do not change across releases without a documented migration. | Dashboards and alerting rules built on `operation` values do not break when code is refactored, as long as the operation identifier is stable. |
| **9** | **Logs, metrics, and traces are complementary — not redundant.** Logs provide narrative detail; metrics provide aggregated quantification; traces provide structural execution maps. All three are required for complete observability. | No single pillar is sufficient for all investigation scenarios. The three pillars answer different questions and compensate for each other's limitations. |
| **10** | **The log format is forward-compatible with OpenTelemetry.** W3C Trace Context identifiers and structured field naming align with OTel semantic conventions. | Enables future OTel adoption as a non-breaking infrastructure change, without requiring any modifications to application-level log statements. |

---

*This document is the official logging architecture specification for DevFlow. Changes to the canonical log event schema, sensitive data blocklist, or retention policies require review and approval from the Architecture Review Board (ARB) and the Security Review function.*
