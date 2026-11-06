# Stage 5: Application Integration & Spring Security Architecture

> **Status:** Engineering Specification & Implementation Guide (Stage 5 Complete)  
> **Date:** 2026-11-06  
> **Author:** DevFlow Core Security & Platform Engineering  
> **Applies to:** `backend/app`, `backend/devflow-auth`, `docs/security/AUTHORIZATION_MODEL.md`, `docs/security/ADR-001-AUTHORIZATION-POLICY-RESOLUTION.md`  
> **Policy Source of Truth:** `docs/security/AUTHORIZATION_MODEL.md` (Status: ARB Approved) and `docs/security/ADR-001-AUTHORIZATION-POLICY-RESOLUTION.md` (*Status: Product-owner approved; ARB ratification pending*)  

---

## 1. Overview & Architectural Principles

Stage 5 bridges the centralized, framework-independent authorization library (`backend/devflow-auth`) and relational persistence adapters (Stage 4 / 4.1) into the operational DevFlow Spring Boot application (`backend/app`).

The centralized authorization engine (`AuthApi` / `AuthorizationEvaluator`) is established as the sole authoritative enforcement mechanism for application operations, retiring fragmented, manual role and membership checks while strictly preserving existing business rules, multi-tenant boundaries, and API contracts.

```
┌────────────────────────────────────────────────────────────────────────┐
│                        backend/devflow-auth                            │
│  (Pure Java Library — Zero Spring, JPA, Web, or SQL Dependencies)      │
│                                                                        │
│  • Canonical Permissions (48) & Roles (10)                             │
│  • PermissionRegistry & RolePermissionMapping (Authoritative Matrix)   │
│  • AuthorizationEvaluator (Central Engine)                             │
│  • SPI Ports: RoleResolver, MembershipValidator,                       │
│               ProjectAssignmentValidator, TenantBoundaryValidator,     │
│               ResourceOwnershipValidator, ResourceStateValidator,      │
│               PlatformSupportValidator, EmergencyAccessValidator,      │
│               AuthorizationAuditRecorder                               │
└───────────────────────────────────▲────────────────────────────────────┘
                                    │ Implements SPI ports
                                    │ (backend/app -> devflow-auth ONLY)
┌───────────────────────────────────┴────────────────────────────────────┐
│                            backend/app                                 │
│          (Spring Boot 3, Spring Security 6, Spring Data JPA)           │
│                                                                        │
│  • AuthorizationConfiguration: Engine & Adapter Bean Wiring            │
│  • SPI Adapters:                                                       │
│      - DatabaseRoleResolver (Stage 4/4.1)                              │
│      - DatabaseMembershipValidator (Stage 4)                           │
│      - DatabaseProjectAssignmentValidator (Stage 4)                    │
│      - DatabaseTenantBoundaryValidator (Tenant scoping & resolution)   │
│      - DatabaseResourceOwnershipValidator (Owner/Lead verification)    │
│      - DatabaseResourceStateValidator (Archival mutation blocking)     │
│      - DefaultPlatformSupportValidator (Fail-closed support gate)      │
│      - DefaultEmergencyAccessValidator (Fail-closed break-glass gate)  │
│      - LoggingAuthorizationAuditRecorder (Structured audit events)     │
│  • Spring Security Bridge:                                             │
│      - DevFlowSecurityService (@authz Spring bean)                     │
│      - JwtAccessDeniedHandler (HTTP 403 standard JSON response)        │
│      - JwtAuthenticationEntryPoint (HTTP 401 standard JSON response)   │
│  • Domain Integration:                                                 │
│      - OrganizationAuthorizationService (Evaluates via AuthApi)        │
│      - TeamAuthorizationService (Delegates org auth, keeps TeamRole)   │
│      - OrganizationService & OrganizationMembershipService             │
│      - WorkspaceService & TeamService                                  │
└────────────────────────────────────────────────────────────────────────┘
```

### Key Architectural Invariants

1. **Strict Dependency Direction:** `backend/app` depends on `backend/devflow-auth`. Under no circumstances may `devflow-auth` acquire dependencies on Spring, JPA, or web frameworks.
2. **Fail-Closed Default:** Missing, unconfigured, or invalid security-critical validators immediately reject requests.
3. **No Caller Attribute Trust:** Caller-supplied user IDs, role claims, or request headers (`X-Role`, `X-User-Id`) are never trusted. The security subject is exclusively resolved from the validated `DevFlowUserDetails` principal in `SecurityContextHolder`.
4. **Strict Separation of Authentication and Authorization:**
   - **Authentication** (JWT bearer validation) establishes identity (`DevFlowUserDetails`).
   - **Authorization** (`AuthApi`) evaluates whether the authenticated identity holds canonical permissions on target resources.
5. **Multi-Tenant Non-Disclosure:** Access denials and tenant mismatches return standardized HTTP 403 Forbidden or HTTP 404 Not Found without leaking cross-tenant resource existence.

---

## 2. Spring Security Integration & Request Pipeline

### 2.1 Authentication vs. Authorization Flow

```
Incoming HTTP Request
         │
         ▼
[JwtAuthenticationFilter]
  ├── Missing / Invalid JWT ──► [JwtAuthenticationEntryPoint] ──► 401 Unauthorized (JSON)
  └── Valid JWT ──────────────► Populates SecurityContextHolder with DevFlowUserDetails
                                     │
                                     ▼
                      [SecurityFilterChain Rules]
                        ├── Public Paths (ApiPaths.PUBLIC_PATHS) ──► PermitAll
                        └── Protected Paths (anyRequest().authenticated())
                                     │
                                     ▼
                      [Controller / Service Boundary]
                        ├── Declarative (@PreAuthorize("@authz.hasOrgPermission(...)"))
                        │       └── Access Denied ──► [GlobalExceptionHandler / JwtAccessDeniedHandler] ──► 403 Forbidden (JSON)
                        └── Programmatic (OrganizationAuthorizationService.require*())
                                └── Access Denied ──► [GlobalExceptionHandler] ──► 403 Forbidden (JSON)
```

### 2.2 Standardized JSON Error Envelopes

Both authentication (401) and authorization (403) failures emit RFC-compliant JSON responses matching DevFlow's API error contract:

**401 Unauthorized (`JwtAuthenticationEntryPoint`):**
```json
{
  "timestamp": "2026-11-06T18:30:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Full authentication is required to access this resource.",
  "path": "/api/v1/organizations/..."
}
```

**403 Forbidden (`JwtAccessDeniedHandler` & `GlobalExceptionHandler`):**
```json
{
  "timestamp": "2026-11-06T18:30:00Z",
  "status": 403,
  "error": "Forbidden",
  "message": "Access is denied: ...",
  "path": "/api/v1/organizations/..."
}
```

### 2.3 Expression Bridge: `DevFlowSecurityService` (`@authz`)

A dedicated Spring component, `DevFlowSecurityService`, is exposed under bean name `@authz` to enable declarative Spring Security method expressions:

```java
@PreAuthorize("@authz.hasOrgPermission(#organizationId, 'member.invite')")
public void inviteMember(UUID organizationId, InviteMemberRequest request) { ... }

@PreAuthorize("@authz.hasProjectPermission(#orgId, #projectId, 'project.write')")
public void updateProject(UUID orgId, UUID projectId, UpdateProjectRequest request) { ... }

@PreAuthorize("@authz.hasPlatformPermission('platform.tenant.provision')")
public void provisionTenant(ProvisionTenantRequest request) { ... }
```

**Security Invariants Enforced by `@authz`:**
- Derives the acting user ID exclusively from `SecurityContextHolder.getContext().getAuthentication().getPrincipal()` (`DevFlowUserDetails.getId()`).
- Rejects unauthenticated, anonymous, or malformed contexts with `false` (fail-closed).
- Queries `PermissionRegistry` by string literal; unmapped or invalid permission values fail closed.
- Delegates evaluation directly to `AuthApi.authorize(request)`.

---

## 3. Engine Wiring & SPI Adapters

The centralized authorization engine is constructed in `AuthorizationConfiguration` via Spring `@Bean` definitions:

```java
@Configuration
public class AuthorizationConfiguration {

    @Bean
    public PermissionRegistry permissionRegistry() {
        return PermissionRegistry.canonical();
    }

    @Bean
    public RolePermissionMapping rolePermissionMapping(PermissionRegistry registry) {
        return RolePermissionMapping.canonical(registry);
    }

    @Bean
    public AuthApi authApi(
            PermissionRegistry registry,
            RolePermissionMapping rolePermissionMapping,
            DatabaseRoleResolver roleResolver,
            DatabaseMembershipValidator membershipValidator,
            DatabaseProjectAssignmentValidator projectAssignmentValidator,
            DatabaseTenantBoundaryValidator tenantBoundaryValidator,
            DatabaseResourceOwnershipValidator resourceOwnershipValidator,
            DatabaseResourceStateValidator resourceStateValidator,
            DefaultPlatformSupportValidator platformSupportValidator,
            DefaultEmergencyAccessValidator emergencyAccessValidator,
            LoggingAuthorizationAuditRecorder auditRecorder
    ) {
        AuthorizationEvaluator evaluator = new AuthorizationEvaluator(
                registry,
                rolePermissionMapping,
                roleResolver,
                membershipValidator,
                projectAssignmentValidator,
                tenantBoundaryValidator,
                resourceOwnershipValidator,
                resourceStateValidator,
                platformSupportValidator,
                emergencyAccessValidator,
                auditRecorder
        );
        return new CentralizedAuthApi(evaluator);
    }
}
```

### 3.1 SPI Adapter Implementations

| SPI Interface | Implementation Class | Responsibilities & Invariants |
|---|---|---|
| `RoleResolver` | `DatabaseRoleResolver` | Resolves persistent active role assignments. Enforces active membership. Supports legacy fallback strictly for unmigrated users without assignment history. |
| `MembershipValidator` | `DatabaseMembershipValidator` | Verifies user has active `OrganizationMembershipStatus.ACTIVE` record. Rejects suspended or missing members. |
| `ProjectAssignmentValidator` | `DatabaseProjectAssignmentValidator` | Verifies active project assignment and active org membership. Rejects expired assignments. |
| `TenantBoundaryValidator` | `DatabaseTenantBoundaryValidator` | Resolves tenant ID for organizations, workspaces, teams, and projects. Verifies cross-tenant project integrity via `ProjectRoleAssignmentRepository`. |
| `ResourceOwnershipValidator` | `DatabaseResourceOwnershipValidator` | Validates resource ownership (org founding owner, team lead, user profile owner). |
| `ResourceStateValidator` | `DatabaseResourceStateValidator` | Prevents mutative actions on archived teams while allowing read access. |
| `PlatformSupportValidator` | `DefaultPlatformSupportValidator` | Evaluates verified platform support cases. Fails closed when unverified or expired. |
| `EmergencyAccessValidator` | `DefaultEmergencyAccessValidator` | Enforces break-glass emergency access gates (security officer approval, 4-hour validity limit). |
| `AuthorizationAuditRecorder` | `LoggingAuthorizationAuditRecorder` | Records structured SLF4J audit events and publishes Spring `AuthorizationAuditApplicationEvent`s. |

---

## 4. Organization Service Migration

`OrganizationAuthorizationService` has been migrated to evaluate all authorization decisions through `AuthApi` while preserving all public method signatures:

```java
public class OrganizationAuthorizationService {

    private final AuthApi authApi;
    private final MembershipValidator membershipValidator;
    private final RoleResolver roleResolver;

    // ...
}
```

### 4.1 Method-to-Permission Mapping

| Service Method | Enforcement Logic via Centralized Engine | Canonical Permission |
|---|---|---|
| `isMember(orgId, userId)` | Queries `membershipValidator.isMemberActive(userId, orgId)` and checks role resolution. | Context membership check |
| `isAdminOrOwner(orgId, userId)` | Evaluates `authApi.authorize(AuthorizationRequest.organization(userId, orgId, MEMBER_INVITE))` | `MEMBER_INVITE` (Held by Owner & Admin, denied to Member/Viewer/Billing) |
| `isOwner(orgId, userId)` | Evaluates `authApi.authorize(AuthorizationRequest.organization(userId, orgId, ORG_DELETE))` | `ORG_DELETE` (Held exclusively by Owner, denied to Admin) |
| `requireMember(orgId, userId)` | Asserts `isMember(orgId, userId)`. Throws `OrganizationMembershipRequiredException` on failure. | Context membership check |
| `requireAdminOrOwner(orgId, userId)` | Asserts `isAdminOrOwner(orgId, userId)`. Throws `OrganizationAccessDeniedException` on failure. | `MEMBER_INVITE` |
| `requireOwner(orgId, userId)` | Asserts `isOwner(orgId, userId)`. Throws `OrganizationAccessDeniedException` on failure. | `ORG_DELETE` |

### 4.2 Security Corrections Enforced

1. **Immediate Role Revocation:** When an organization admin's role is revoked or demoted to `ORGANIZATION_MEMBER`, subsequent calls to `isAdminOrOwner` fail immediately. The legacy fallback mechanism is strictly suppressed for any user with a history of role assignments (ADR-001 §1, Stage 4.1).
2. **Billing Isolation:** Users assigned `ORGANIZATION_BILLING` do not receive administrative privileges (`MEMBER_INVITE`, `ORG_MANAGE`). Organization administrators cannot perform billing mutations (`BILLING_MANAGE`) unless also assigned `ORGANIZATION_OWNER` or `ORGANIZATION_BILLING`.
3. **Owner Exclusivity:** Organization administrators can never delete organizations (`ORG_DELETE`) or perform owner-only operations.

---

## 5. Team Authorization Compatibility

`TeamAuthorizationService` governs team-level collaboration while delegating tenant-level security to the migrated `OrganizationAuthorizationService`:

```
               [Team Authorization Flow]
                           │
       Does user hold active organization membership?
                  (isMember / requireMember)
                    ├── NO  ──► OrganizationMembershipRequiredException (403)
                    └── YES
                           │
             Is workspace visibility PRIVATE?
                    ├── YES ──► Requires isAdminOrOwner(orgId, userId)
                    │             ├── NO  ──► WorkspaceAccessDeniedException (403)
                    │             └── YES ──► Access Granted
                    └── NO (PUBLIC)
                           │
                Is operation mutative (requireCanManageTeam)?
                    ├── YES ──► Requires isAdminOrOwner OR isTeamLead
                    │             ├── NO  ──► TeamAccessDeniedException (403)
                    │             └── YES ──► Access Granted
                    └── NO (requireCanViewTeam) ──► Access Granted
```

### 5.1 Team Role Rules

- **`TeamRole.LEAD` and `TeamRole.MEMBER` Independence:** Team roles remain purely operational collaborative concepts within workspaces and are NOT mapped to canonical organization or project roles.
- **Suspended Membership Fail-Closed:** A suspended organization member is immediately denied all team operations (`canViewTeam` returns `false`, `requireCanManageTeam` throws `OrganizationMembershipRequiredException`), even if their record in `team_members` designates them as `LEAD`.
- **Private Workspace Supremacy:** Workspace visibility restrictions take precedence over team-level leadership. A user who is `LEAD` of a team in a `PRIVATE` workspace cannot access the team unless they also hold `ORGANIZATION_ADMIN` or `ORGANIZATION_OWNER` in the organization.

---

## 6. Canonical Permission Mappings for Protected Operations

| Domain Resource | Operation / Endpoint | Canonical Permission | Enforcement Boundary | Required Role(s) |
|---|---|---|---|---|
| **Organization** | `POST /api/v1/organizations` (create) | `platform.tenant.provision` / self-owner | Controller / Service | Any authenticated user (becomes founding OWNER) |
| **Organization** | `GET /api/v1/organizations/{id}` | Active membership | `OrganizationService.getOrganizationById` | OWNER, ADMIN, MEMBER, VIEWER, BILLING |
| **Organization** | `PATCH /api/v1/organizations/{id}` | `member.invite` / `org.manage` | `OrganizationService.updateOrganization` | OWNER, ADMIN |
| **Organization** | `DELETE /api/v1/organizations/{id}` | `org.delete` | `OrganizationService.deleteOrganization` | OWNER only |
| **Organization Member** | `POST /api/v1/organizations/{id}/members` | `member.invite` | `OrganizationMembershipService.addMember` | OWNER, ADMIN |
| **Organization Member** | `DELETE /api/v1/organizations/{id}/members/{userId}` | `member.remove` | `OrganizationMembershipService.removeMember` | OWNER, ADMIN |
| **Organization Member** | `PATCH /api/v1/organizations/{id}/members/{userId}/role` | `org.manage` | `OrganizationMembershipService.changeMemberRole` | OWNER only |
| **Workspace** | `POST /api/v1/organizations/{id}/workspaces` | `member.invite` | `WorkspaceService.createWorkspace` | OWNER, ADMIN |
| **Workspace** | `GET /api/v1/organizations/{id}/workspaces` | Active membership | `WorkspaceService.listWorkspaces` | All active members (filtered by visibility) |
| **Workspace (Private)** | Access private workspace / teams | `member.invite` | `TeamAuthorizationService.requireCanViewTeam` | OWNER, ADMIN |
| **Team** | Create team in workspace | `member.invite` | `TeamService.createTeam` | OWNER, ADMIN |
| **Team** | Manage team (update, archive, member mgmt) | `member.invite` OR Team Lead | `TeamService.updateTeam`, `archiveTeam`, etc. | OWNER, ADMIN, or Team LEAD (public workspace) |
| **Billing** | `BILLING_VIEW`, `BILLING_MANAGE` | `billing.view`, `billing.manage` | Centralized `AuthApi` / `@authz` | OWNER, BILLING only (ADMIN denied) |

---

## 7. Verification & Test Coverage

Stage 5 is backed by comprehensive integration test suites executing against PostgreSQL and the full Spring Boot application context:

### 7.1 Test Suites Summary

1. **`CentralizedAuthorizationEngineIntegrationTest`** (9 tests):
   - Spring application context loading of all engine beans and SPI adapters.
   - Database-backed role resolution and active vs. suspended membership.
   - Multiple role assignment permission union (e.g. `ORGANIZATION_MEMBER` + `ORGANIZATION_BILLING`).
   - Billing isolation (ensuring `ORGANIZATION_ADMIN` is denied billing management).
   - Platform support case gate (unverified support case denied; verified granted).
   - Emergency break-glass access gate (unapproved fails; approved within 4h window succeeds; expired fails).
   - Fail-closed behavior when security-critical validators are missing.

2. **`OrganizationAuthorizationIntegrationTest`** (6 tests):
   - Canonical role hierarchy (`OWNER`, `ADMIN`, `MEMBER`, `VIEWER`).
   - Legacy `DEVELOPER` mapping to canonical `ORGANIZATION_MEMBER`.
   - Immediate role revocation effect (no privilege resurrection via legacy fallback).
   - Strict cross-tenant access rejection.

3. **`TeamCentralizedAuthorizationIntegrationTest`** (16 tests):
   - Team `LEAD` vs. `MEMBER` privileges in public workspaces.
   - Supervisory privileges of Organization `OWNER` and `ADMIN` over all teams.
   - Private workspace visibility enforcement taking precedence over team `LEAD`.
   - Suspended organization membership fail-closed behavior across all team operations.
   - End-to-end regression testing of team creation, updates, member addition, atomic leadership transfer, and archiving.

4. **`SpringSecurityAuthorizationIntegrationTest`** (11 tests):
   - Unauthenticated requests returning HTTP 401 Unauthorized with standard JSON envelope.
   - Authenticated permitted requests returning HTTP 200/204.
   - Authenticated denied requests returning HTTP 403 Forbidden with standard JSON envelope.
   - Prevention of caller attribute tampering (ignoring client `X-Role` / `X-User-Id` headers).
   - Verification that `@authz` strictly resolves the subject from `DevFlowUserDetails`.
   - Cross-tenant requests failing closed with HTTP 403 without data leakage.
   - Declarative method security verification (`@PreAuthorize("@authz.hasOrgPermission(...)")`).

5. **`OrganizationAuthorizationServiceTest`** (25 unit tests):
   - Migrated unit test suite verifying `AuthApi` evaluation, permission queries, and backward-compatible repository constructor.

**Complete Backend Verification:** All 588 tests passed cleanly (`./gradlew test`).

---

## 8. Integration Boundaries & Stage 6 Prerequisites

### 8.1 Current Integration Boundaries

1. **Stateless JWT Claims:** JWT access tokens currently carry user identity (`sub`, `email`, `username`) without role or permission claims. All authorization decisions query the database via `DatabaseRoleResolver` and `DatabaseMembershipValidator`.
2. **Project Domain Infrastructure:** Project management domain entities and controllers are not yet implemented in `backend/app`. The centralized authorization engine includes full project-level contracts and SPI adapters (`DatabaseProjectAssignmentValidator`, `ProjectAuthorizationContext`), ready for immediate consumption when project entities are introduced.

### 8.2 Stage 6 Prerequisites

Stage 6 will focus on **JWT Claims & Authorization Optimization**:
- Embedding validated role/tenant claims in JWT bearer tokens to enable fast-path evaluation while preserving cache invalidation on role revocation.
- High-performance caching layers for `RoleResolver` and `MembershipValidator`.
- REST endpoints for managing role assignments (`/api/v1/organizations/{id}/roles`).
