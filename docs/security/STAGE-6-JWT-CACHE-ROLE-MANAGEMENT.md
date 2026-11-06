# Stage 6: JWT Optimization, Authorization Cache & Role Management API

> **Status:** Engineering Specification & Implementation Guide (Stage 6 Complete)  
> **Date:** 2026-11-07  
> **Author:** DevFlow Core Security & Platform Engineering  
> **Applies to:** `backend/app`, `backend/devflow-auth`, `docs/security/AUTHORIZATION_MODEL.md`, `docs/security/ADR-001-AUTHORIZATION-POLICY-RESOLUTION.md`  
> **Policy Source of Truth:** `docs/security/AUTHORIZATION_MODEL.md` (Status: ARB Approved) and `docs/security/ADR-001-AUTHORIZATION-POLICY-RESOLUTION.md` (*Status: Product-owner approved; ARB ratification pending*)  

---

## 1. Executive Summary & Stage 6 Objectives

Stage 6 completes the production operationalization of DevFlow's centralized multi-tenant authorization framework across three critical workstreams:

1. **Workstream A — JWT Claims Optimization:**
   - Minimizes access token payload overhead (payload < 1KB, typically ~250–350 bytes).
   - Preserves standard identity claims (`userId`, `username`, `email`) and introduces an optional active organization context hint (`orgId`).
   - Strictly omits fine-grained permissions and dynamic roles from JWTs to avoid token bloat and eliminate stale-privilege retention risks.
   - Treats JWT claims as non-authoritative identity and routing hints; persistent database/cache state remains the authoritative source of truth.
   - Enforces zero privilege escalation across organization switching and prevents bypass via stale tokens.

2. **Workstream B — Authorization Cache & Revocation Engine:**
   - Implements high-throughput, low-latency caching (`AuthorizationCacheService`) for resolved organization roles, project roles, and active membership status.
   - Backed by Redis (`RedisAuthorizationCacheService`) with Jackson serialization, configurable TTL (default 300 seconds), and a resilient in-memory TTL-bounded fallback.
   - Fail-closed operational policy: Redis connection degradation or cache miss falls back to authoritative database evaluation without ever granting permissive access.
   - Deterministic, tenant-isolated cache key namespacing (`devflow:auth:...`) preventing cross-tenant data collisions.
   - Targeted, surgical cache invalidation triggered on role assignment grants, revocations, expirations, and organization membership modifications.

3. **Workstream C — Role Management REST APIs:**
   - Production-ready REST endpoints for persistent organization and project role assignments under `/api/v1/organizations/{organizationId}/roles` and `/api/v1/organizations/{organizationId}/projects/{projectId}/roles`.
   - Reuses enterprise business logic and guardrails from `RoleAssignmentService`:
     - Method-security authorization requiring `ORGANIZATION_MANAGE_MEMBERS` or `PROJECT_MANAGE_MEMBERS`.
     - Owner and billing role assignments/revocations restricted exclusively to `ORGANIZATION_OWNER`.
     - Sole active owner protection (cannot revoke the last active owner of an organization).
     - Strict rejection of platform roles (`PLATFORM_ADMIN`, `PLATFORM_SUPPORT`, `PLATFORM_SECURITY`).
     - Validation that target assignees hold active membership in the enclosing organization.
     - Strict tenant boundary validation for project-scoped assignments.
     - Standard HTTP status code mapping (200, 201, 204, 400, 401, 403, 404, 409).

---

## 2. Workstream A — JWT Claims Optimization

### 2.1 Token Design & Claims Schema

DevFlow access tokens are compact, signed HMAC-SHA256 bearer tokens designed for rapid stateless verification by edge routers, API gateways, and the Spring Security filter chain.

#### Payload Structure

```json
{
  "sub": "user@example.com",
  "userId": "d3b07384-d113-46c1-a968-3e1ff54784ac",
  "username": "johndoe",
  "email": "user@example.com",
  "orgId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "iss": "devflow",
  "iat": 1730937600,
  "exp": 1730938500
}
```

| Claim | Type | Description | Authoritative? |
| :--- | :--- | :--- | :--- |
| `sub` | `String` | Subject (principal email address) | Yes (Identity) |
| `userId` | `String` (UUID) | Unique user identifier | Yes (Identity) |
| `username` | `String` | Unique user handle | Informational |
| `email` | `String` | User verified email | Informational |
| `orgId` | `String` (UUID, optional) | Active organization context hint | **No (Routing/Context Hint Only)** |
| `iss` | `String` | Token issuer | Yes (Token Validity) |
| `iat` / `exp` | `Long` | Issued at and expiration timestamps | Yes (Token Lifecycle) |

### 2.2 Security Invariants & Anti-Escalation Safeguards

1. **Strict Omission of Permissions:** Fine-grained permissions (e.g., `ORGANIZATION_UPDATE`, `PROJECT_DELETE`) are never placed in JWTs. With 48 canonical permissions and up to 145 mappings, embedding permissions would cause massive token bloat, header overflow in proxies, and dangerous staleness windows where revoked permissions linger until token expiration.
2. **Strict Omission of Dynamic Roles:** User roles are not stored in access tokens. When a user's role is demoted or revoked, authorization decisions must reflect this change immediately. Placing roles in tokens would permit an attacker to use an existing token with elevated privileges.
3. **Non-Authoritative Tenant Hint:** The optional `orgId` claim provides an active tenant context for UI routing and default scoping. However, it is never trusted as proof of authorization. All resource requests evaluate tenant boundaries and persistent role assignments via `AuthApi` and the database/cache.
4. **Token Tampering Resistance:** Tokens with modified claims, altered user IDs, or invalid HMAC signatures are rejected by `JwtTokenProvider.validateToken()` with HTTP 401 Unauthorized before any controller execution.
5. **Payload Size Footprint:** Typical serialized JWT payload size is between 250 and 350 bytes (< 1KB), well below HTTP header limits.

---

## 3. Workstream B — Authorization Cache & Revocation Engine

### 3.1 Cache Architecture & Topology

To ensure sub-millisecond evaluation latency for high-frequency requests, DevFlow implements a multi-tier caching layer for authorization facts:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Spring Security Request                         │
│             (@PreAuthorize / OrganizationAuthorizationService)          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        Central AuthApi Engine                          │
│                      (AuthorizationEvaluator)                          │
└───────────┬───────────────────────┬───────────────────────┬────────────┘
            │                       │                       │
            ▼                       ▼                       ▼
┌───────────────────────┐ ┌───────────────────┐ ┌────────────────────────┐
│   DatabaseRoleResolver │ │DatabaseMembership-│ │DatabaseProjectAssign-  │
│                       │ │   Validator       │ │    mentValidator       │
└───────────┬───────────┘ └─────────┬─────────┘ └───────────┬────────────┘
            │                       │                       │
            └───────────────────────┼───────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                       AuthorizationCacheService                        │
│                (RedisAuthorizationCacheService)                        │
├────────────────────────────────────────────────────────────────────────┤
│  Primary: Redis Distributed Cache (devflow:auth:*)                     │
│  Fallback: Concurrent TTL-bounded In-Memory Cache (Fail-Closed)       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                         Cache Miss │ (Populate & Return)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   PostgreSQL Database (Authoritative)                  │
│       (organization_role_assignments, project_role_assignments,        │
│                         organization_members)                          │
└────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Cache Key Namespacing & Tenant Isolation

All keys are strongly typed, namespaced, and prefixed with the tenant `organizationId` to prevent cross-tenant collisions:

| Key Pattern | Data Stored | TTL | Invalidation Trigger |
| :--- | :--- | :--- | :--- |
| `devflow:auth:org:{orgId}:user:{userId}:roles` | `List<String>` (Canonical Role names) | 300s | Org role grant, revoke, expiry, member removal, member role change |
| `devflow:auth:org:{orgId}:proj:{projId}:user:{userId}:roles` | `List<String>` (Canonical Role names) | 300s | Project role grant, revoke, expiry, member removal |
| `devflow:auth:org:{orgId}:user:{userId}:membership` | `Boolean` (Active membership) | 300s | Member add, remove, role change |

### 3.3 Targeted Invalidation Matrix

Stale privileges represent a severe security risk. Cache entries are invalidated deterministically upon mutation events:

| Domain Event | Method Trigger | Invalidation Target |
| :--- | :--- | :--- |
| **Grant Org Role** | `RoleAssignmentService.assignOrganizationRole` | `devflow:auth:org:{orgId}:user:{targetUserId}:roles` |
| **Revoke Org Role** | `RoleAssignmentService.revokeOrganizationRoleAssignment` | `devflow:auth:org:{orgId}:user:{targetUserId}:roles` |
| **Grant Project Role** | `RoleAssignmentService.assignProjectRole` | `devflow:auth:org:{orgId}:proj:{projId}:user:{targetUserId}:roles` |
| **Revoke Project Role** | `RoleAssignmentService.revokeProjectRoleAssignment` | `devflow:auth:org:{orgId}:proj:{projId}:user:{targetUserId}:roles` |
| **Add Member** | `OrganizationMembershipService.addMember` | `devflow:auth:org:{orgId}:user:{userId}:membership` |
| **Remove Member** | `OrganizationMembershipService.removeMember` | `devflow:auth:org:{orgId}:user:{userId}:membership`<br>`devflow:auth:org:{orgId}:user:{userId}:roles`<br>`devflow:auth:org:{orgId}:proj:*:user:{userId}:roles` |
| **Change Member Role** | `OrganizationMembershipService.changeMemberRole` | `devflow:auth:org:{orgId}:user:{userId}:roles`<br>`devflow:auth:org:{orgId}:user:{userId}:membership` |

### 3.4 Resilience & Fail-Closed Fallback

- If Redis is unavailable or times out, `RedisAuthorizationCacheService` automatically routes reads and writes to a concurrent local in-memory fallback cache with TTL expiration.
- If both the cache and database fail, requests fail closed (HTTP 403 Forbidden / Access Denied).
- No unhandled Redis exception is allowed to bubble up or permit unauthorized access.

---

## 4. Workstream C — Role Management REST API

### 4.1 Organization Role Management API

Base path: `/api/v1/organizations/{organizationId}/roles`

#### 1. List Organization Role Assignments
- **Method:** `GET`
- **Path:** `/api/v1/organizations/{organizationId}/roles`
- **Query Params:** `activeOnly` (boolean, default: `true`)
- **Required Permission:** `ORGANIZATION_MANAGE_MEMBERS`
- **Response:** `200 OK` with `List<RoleAssignmentResponse>`

#### 2. Grant Organization Role
- **Method:** `POST`
- **Path:** `/api/v1/organizations/{organizationId}/roles`
- **Body:**
  ```json
  {
    "userId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "roleName": "ORGANIZATION_ADMIN",
    "expiresAt": "2026-12-31T23:59:59Z"
  }
  ```
- **Required Permission:** `ORGANIZATION_MANAGE_MEMBERS`
- **Response:** `201 Created` with `RoleAssignmentResponse`

#### 3. Revoke Organization Role Assignment
- **Method:** `DELETE`
- **Path:** `/api/v1/organizations/{organizationId}/roles/{assignmentId}`
- **Body (optional):**
  ```json
  {
    "revocationReason": "Role reassignment"
  }
  ```
- **Required Permission:** `ORGANIZATION_MANAGE_MEMBERS`
- **Response:** `204 No Content`

### 4.2 Project Role Management API

Base path: `/api/v1/organizations/{organizationId}/projects/{projectId}/roles`

#### 1. List Project Role Assignments
- **Method:** `GET`
- **Path:** `/api/v1/organizations/{organizationId}/projects/{projectId}/roles`
- **Query Params:** `activeOnly` (boolean, default: `true`)
- **Required Permission:** `PROJECT_MANAGE_MEMBERS`
- **Response:** `200 OK` with `List<RoleAssignmentResponse>`

#### 2. Grant Project Role
- **Method:** `POST`
- **Path:** `/api/v1/organizations/{organizationId}/projects/{projectId}/roles`
- **Body:**
  ```json
  {
    "userId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "roleName": "PROJECT_CONTRIBUTOR",
    "expiresAt": "2026-12-31T23:59:59Z"
  }
  ```
- **Required Permission:** `PROJECT_MANAGE_MEMBERS`
- **Response:** `201 Created` with `RoleAssignmentResponse`

#### 3. Revoke Project Role Assignment
- **Method:** `DELETE`
- **Path:** `/api/v1/organizations/{organizationId}/projects/{projectId}/roles/{assignmentId}`
- **Body (optional):**
  ```json
  {
    "revocationReason": "Removed from project"
  }
  ```
- **Required Permission:** `PROJECT_MANAGE_MEMBERS`
- **Response:** `204 No Content`

### 4.3 Security Guardrails & Invariants

| Guardrail | Enforcement Rule | HTTP Status |
| :--- | :--- | :--- |
| **Unauthenticated Request** | Missing / invalid JWT token | `401 Unauthorized` |
| **Insufficient Privileges** | Caller lacks `ORGANIZATION_MANAGE_MEMBERS` or `PROJECT_MANAGE_MEMBERS` | `403 Forbidden` |
| **Billing Role Isolation** | Only `ORGANIZATION_OWNER` can grant/revoke `ORGANIZATION_BILLING` | `403 Forbidden` |
| **Owner Role Isolation** | Only `ORGANIZATION_OWNER` can grant/revoke `ORGANIZATION_OWNER` | `403 Forbidden` |
| **Sole Owner Protection** | Cannot revoke or remove the last remaining active owner | `403 Forbidden` |
| **Platform Role Denial** | `PLATFORM_ADMIN`, `PLATFORM_SUPPORT`, `PLATFORM_SECURITY` cannot be granted via org/project APIs | `400 Bad Request` |
| **Scope Mismatch** | Project role granted at org level or org role granted at project level | `400 Bad Request` |
| **Tenant Boundary** | Target user must be an active member of the organization | `400 Bad Request` |
| **Cross-Tenant Project** | Project assignments cannot span across different organizations | `400 Bad Request` |
| **Duplicate Assignment** | Target user already holds the active role in scope | `409 Conflict` |
| **Missing Assignment** | Assignment ID not found in target scope | `404 Not Found` |

---

## 5. Verification & Test Suite Summary

The Stage 6 deliverables were verified through extensive automated test suites:

| Test Class | Test Count | Focus Area | Status |
| :--- | :---: | :--- | :---: |
| `JwtClaimsOptimizationTest` | 7 | Compact payload (<1KB), claims content, org context, signature verification, non-authoritative behavior | **PASSED** |
| `AuthorizationCacheIntegrationTest` | 7 | Cache hit/miss, targeted invalidation on grant/revoke/member change, cross-tenant isolation, resilience | **PASSED** |
| `OrganizationRoleControllerIntegrationTest` | 11 | List, grant, revoke, 401 unauth, 403 developer, 403 cross-tenant, 403 billing/owner isolation, sole owner protection, 400 platform rejection, 409 duplicate | **PASSED** |
| `ProjectRoleControllerIntegrationTest` | 8 | List, grant, revoke, 403 outsider, 400 scope mismatch, 400 platform role, 409 duplicate, 404 not found | **PASSED** |
| `SecurityConfigurationTest` | 13 | Security filter chain, public/protected endpoints, CORS preflight, stateless session | **PASSED** |
| **Full Regression Suite** | **641** | All application tests across auth, security, role persistence, workspaces, teams, profiles, and API slices | **PASSED** |

---

## 6. Forward Compatibility & Stage 7 Transition

With Stage 6 complete:
- Centralized authorization is fully production-integrated, cached, and manageable via REST APIs.
- Next stages can leverage the Role Management APIs and cache invalidation framework for fine-grained resource sharing, external SAML/OIDC group syncing, and enterprise audit streaming.
