# ADR-001: Centralized Authorization Policy Resolution & Hardening

> **Status:** Product-owner approved; ARB ratification pending  
> **Date:** 2026-11-02  
> **Author:** DevFlow Core Security & Platform Engineering  
> **Applies to:** `backend/devflow-auth`, `docs/security/AUTHORIZATION_MODEL.md`  
> **Supercedes/Amends:** Extends and resolves operational gaps in `AUTHORIZATION_MODEL.md` v1.0.0  

---

## 1. Context & Motivation

During Stage 3 implementation of the Centralized Authorization Engine (`backend/devflow-auth`), the baseline architecture document (`docs/security/AUTHORIZATION_MODEL.md` v1.0.0) established fundamental RBAC principles, scopes, and 40 core business permissions. However, several operational policy questions remained unresolved or ambiguous:

1. **Organization Billing Scope:** The role `ORGANIZATION_BILLING` was introduced without a finalized, isolated permission set and clear hierarchy isolation.
2. **Platform Scope Permissions:** Platform administration and customer support lacked concrete canonical permissions and security gates.
3. **Canonical Permission Registry:** The registry required canonical expansion to cover all enterprise operational actions without codebase sprawl.
4. **Scope-Aware Inheritance:** While roles must not cross scope boundaries (e.g., project roles granting organization permissions), parent-to-child administration rules (e.g., organization owners and admins managing projects in their tenant) required formal codification.
5. **Project Assignment Gate:** Whether an organization member can access any project without assignment required explicit security gating.
6. **Resource Ownership Fulfillment:** Concrete rules were needed for ownership-gated operations and ownership alternatives across domains (tasks, repositories, knowledge base, comments, automations).

This Architecture Decision Record (ADR-001) formally resolves these decisions, establishes the complete 48-permission policy matrix, and details the security enforcement gates implemented in `backend/devflow-auth`.

---

## 2. Decision Summary

### 2.1 Resolution of Organization Billing

* **Canonical Mapping:** `ORGANIZATION_BILLING` is granted strictly:
  - `billing.view`
  - `billing.manage`
* **Hierarchy Isolation:** `ORGANIZATION_BILLING` does **not** inherit engineering permissions and does not sit in the general organization role hierarchy (`OWNER -> ADMIN -> MEMBER -> VIEWER`). It has no engineering access.
* **Owner Authority:** `ORGANIZATION_OWNER` retains both `billing.view` and `billing.manage`.
* **Admin Restriction:** `ORGANIZATION_ADMIN` receives **neither** billing permission.
* **Assignment Authority:** Only `ORGANIZATION_OWNER` can assign or revoke the `ORGANIZATION_BILLING` role.
* **Tenant Scope:** Billing access is strictly organization-scoped.

### 2.2 Platform Permissions & Platform Roles

Eight (8) canonical platform permissions are registered:
1. `platform.tenant.provision`: Provision new tenant organizations.
2. `platform.tenant.deprovision`: Deprovision tenant organizations.
3. `platform.featureflag.manage`: Manage platform-wide feature flags.
4. `platform.observability.read`: Access platform telemetry, metrics, and logs.
5. `platform.audit.read`: Read platform-level audit logs.
6. `platform.support.case.read`: Inspect support case details and tenant diagnostic data.
7. `platform.support.tenant.read`: Read-only tenant inspection for support resolution.
8. `platform.emergency_access.execute`: Execute break-glass / emergency operational procedures.

#### Platform Role Grants:
* **`PLATFORM_ADMIN`:** Granted all 8 platform permissions, subject to emergency access validation gates.
* **`PLATFORM_SUPPORT`:** Restricted strictly to read-only customer support permissions:
  - `platform.support.case.read`
  - `platform.support.tenant.read`
  - *No modification permissions; no engineering or billing access; strictly read-only.*

### 2.3 Platform Security Gates (SPI Ports)

Platform permissions must never grant organization or project roles implicitly. In addition, high-privilege and cross-tenant platform actions must pass mandatory security gates:

1. **Platform Support Case Gate (`PlatformSupportValidator`):**
   - Access to `platform.support.case.read` and `platform.support.tenant.read` must be linked to a verified, open support case.
   - Access is restricted exclusively to the tenant organization associated with that support case.
   - Verified via the framework-independent `PlatformSupportValidator` SPI.
   - Fails closed if the validator is missing or returns `false`.
   - Caller-supplied request attributes are never trusted as proof of case authorization.

2. **Emergency Access Gate (`EmergencyAccessValidator`):**
   - Execution of `platform.emergency_access.execute` requires independent out-of-band verification.
   - Enforces a maximum four-hour authorization window.
   - Verified via the framework-independent `EmergencyAccessValidator` SPI.
   - Fails closed if approval is missing, expired, or unverified.
   - All emergency access attempts (allowed or denied) emit dedicated audit events (`EMERGENCY_ACCESS_ALLOWED`, `EMERGENCY_ACCESS_DENIED`).

### 2.4 Scope Inheritance & Project Assignment Rules

#### Scope Boundaries
* Role hierarchy strictly cannot cross scope boundaries (`PLATFORM`, `ORGANIZATION`, `PROJECT`).
* Project roles cannot grant organization or platform permissions.
* Organization ownership does not imply platform administration.

#### Hierarchical Scope Inheritance
* **Organization Owner (`ORGANIZATION_OWNER`):**
  - Inherits organization permissions into all projects within their organization.
  - Does not require an explicit project role for ordinary project administration.
  - Remains strictly bounded by tenant boundaries and resource-state restrictions.
* **Organization Administrator (`ORGANIZATION_ADMIN`):**
  - Inherits project administrative permissions into all projects within their organization.
  - Does not inherit owner-exclusive actions: organization deletion (`org.delete`), organization ownership transfer, or billing authority (`billing.*`).
* **Organization Viewer (`ORGANIZATION_VIEWER`):**
  - Receives read-only access to organization data.
  - Inherits read-only access to project boards and resources (`project.read`, `task.read`, `repo.read`, `kb.read`, `analytics.view`).
  - Cannot modify project resources.
* **Organization Member (`ORGANIZATION_MEMBER`):**
  - Must have active organization membership.
  - Requires an explicit project role or verified project assignment via `ProjectAssignmentValidator` for project-scoped access.
  - Does **not** receive unrestricted access to all projects in an organization merely by holding organization membership.
  - When assigned to a project without an explicit distinct role, defaults to `PROJECT_MEMBER`.

### 2.5 Ownership Alternatives & Owner-Gated Verification

Ownership is an authorization condition evaluated at request time, not a role.

1. **Base Access Invariant:** Ownership cannot bypass default-deny or grant access to resource types the user has no base role access to.
2. **Tenant Isolation Invariant:** Ownership checks never bypass tenant boundary checks. Mismatched tenant contexts fail before ownership is evaluated.
3. **Owner-Gated Actions:**
   - `project.delete`: Allowed for `ORGANIZATION_OWNER` and `ORGANIZATION_ADMIN` directly. For `PROJECT_LEAD`, allowed only when verified as the project owner via `ResourceOwnershipValidator`.
4. **Ownership Alternatives:**
   - `task.delete`: Project members who created the task can delete their own task.
   - `repo.disconnect`: Project members who connected the repository can disconnect it.
   - `kb.delete`: Project members who authored the knowledge base article can delete it.
   - `automation.delete`: Project members who authored the automation rule can delete it.
   - `comment.edit` & `comment.delete`: Comment authors can edit and delete their own comments.
   - In all cases, unverified ownership fails closed (`OWNERSHIP_REQUIRED`).

---

## 3. Complete 48-Permission Role Matrix

| # | Permission String | Resource | Action | Platform Roles | Organization Roles | Project Roles | Ownership Alternative / Gate |
|---|-------------------|----------|--------|----------------|--------------------|---------------|------------------------------|
| 1 | `project.read` | `project` | `read` | - | Owner, Admin, Member, Viewer | Lead, Member, Viewer | - |
| 2 | `project.write` | `project` | `write` | - | Owner, Admin, Member | Lead | - |
| 3 | `project.delete` | `project` | `delete` | - | Owner, Admin | Lead (owner-gated) | Project Owner |
| 4 | `task.create` | `task` | `create` | - | Owner, Admin, Member | Lead, Member | - |
| 5 | `task.read` | `task` | `read` | - | Owner, Admin, Member, Viewer | Lead, Member, Viewer | - |
| 6 | `task.write` | `task` | `write` | - | Owner, Admin, Member | Lead, Member | - |
| 7 | `task.assign` | `task` | `assign` | - | Owner, Admin, Member | Lead | - |
| 8 | `task.delete` | `task` | `delete` | - | Owner, Admin | Lead | Task Creator |
| 9 | `cycle.manage` | `cycle` | `manage` | - | Owner, Admin | Lead | - |
| 10 | `repo.connect` | `repo` | `connect` | - | Owner, Admin | - | - |
| 11 | `repo.disconnect` | `repo` | `disconnect` | - | Owner, Admin | - | Connection Owner |
| 12 | `repo.read` | `repo` | `read` | - | Owner, Admin, Member, Viewer | Lead, Member, Viewer | - |
| 13 | `repo.scan` | `repo` | `scan` | - | Owner, Admin, Member | Lead, Member | - |
| 14 | `repo.configure` | `repo` | `configure` | - | Owner, Admin | - | - |
| 15 | `ai.chat` | `ai` | `chat` | - | Owner, Admin, Member | Lead, Member | - |
| 16 | `ai.review` | `ai` | `review` | - | Owner, Admin, Member | Lead, Member | - |
| 17 | `ai.review.approve` | `ai` | `approve` | - | Owner, Admin | Lead | Project Lead / Admin |
| 18 | `ai.configure` | `ai` | `configure` | - | Owner, Admin | - | - |
| 19 | `kb.read` | `kb` | `read` | - | Owner, Admin, Member, Viewer | Lead, Member, Viewer | - |
| 20 | `kb.write` | `kb` | `write` | - | Owner, Admin, Member | Lead, Member | - |
| 21 | `kb.manage` | `kb` | `manage` | - | Owner, Admin | Lead | - |
| 22 | `kb.delete` | `kb` | `delete` | - | Owner, Admin | Lead | Article Author |
| 23 | `analytics.view` | `analytics` | `view` | - | Owner, Admin, Member, Viewer | Lead, Member, Viewer | - |
| 24 | `analytics.export` | `analytics` | `export` | - | Owner, Admin | Lead | - |
| 25 | `analytics.configure` | `analytics` | `configure` | - | Owner, Admin | - | - |
| 26 | `automation.read` | `automation` | `read` | - | Owner, Admin, Member | Lead, Member | - |
| 27 | `automation.configure` | `automation` | `configure` | - | Owner, Admin | Lead | - |
| 28 | `automation.delete` | `automation` | `delete` | - | Owner, Admin | - | Rule Owner |
| 29 | `comment.create` | `comment` | `create` | - | Owner, Admin, Member | Lead, Member | - |
| 30 | `comment.edit` | `comment` | `edit` | - | Owner, Admin | Lead | Comment Author |
| 31 | `comment.delete` | `comment` | `delete` | - | Owner, Admin | Lead | Comment Author |
| 32 | `comment.moderate` | `comment` | `moderate` | - | Owner, Admin | Lead | - |
| 33 | `member.invite` | `member` | `invite` | - | Owner, Admin | - | - |
| 34 | `member.remove` | `member` | `remove` | - | Owner, Admin | - | - |
| 35 | `notification.configure` | `notification` | `configure` | - | Owner, Admin | - | - |
| 36 | `notification.broadcast` | `notification` | `broadcast` | - | Owner, Admin | - | - |
| 37 | `org.manage` | `org` | `manage` | - | Owner | - | - |
| 38 | `org.delete` | `org` | `delete` | - | Owner | - | Owner Only |
| 39 | `billing.view` | `billing` | `view` | - | Owner, Billing | - | Owner / Billing |
| 40 | `billing.manage` | `billing` | `manage` | - | Owner, Billing | - | Owner / Billing |
| 41 | `platform.tenant.provision` | `platform` | `provision` | Admin | - | - | Platform Admin |
| 42 | `platform.tenant.deprovision` | `platform` | `deprovision` | Admin | - | - | Platform Admin |
| 43 | `platform.featureflag.manage` | `platform` | `manage` | Admin | - | - | Platform Admin |
| 44 | `platform.observability.read` | `platform` | `read` | Admin | - | - | Platform Admin |
| 45 | `platform.audit.read` | `platform` | `read` | Admin | - | - | Platform Admin |
| 46 | `platform.support.case.read` | `platform` | `read` | Admin, Support | - | - | Case-Linked Gate |
| 47 | `platform.support.tenant.read` | `platform` | `read` | Admin, Support | - | - | Case-Linked Gate |
| 48 | `platform.emergency_access.execute` | `platform` | `execute` | Admin | - | - | 4h Approval Gate |

---

## 4. SPI Contracts Introduced

All SPI ports are pure Java interfaces without framework dependencies:

1. `PlatformSupportValidator`:
   ```java
   public interface PlatformSupportValidator {
       boolean isSupportAccessAuthorized(UUID userId, UUID targetOrganizationId, String supportCaseId);
   }
   ```
2. `EmergencyAccessValidator`:
   ```java
   public interface EmergencyAccessValidator {
       boolean isEmergencyAccessAuthorized(UUID userId, Instant evaluationTime);
   }
   ```
3. `ProjectAssignmentValidator`:
   ```java
   public interface ProjectAssignmentValidator {
       boolean isAssignedToProject(UUID userId, UUID organizationId, UUID projectId);
   }
   ```

---

## 5. Denial Reasons & Audit Event Types Added

### Denial Reasons (`DenialReason`)
- `UNKNOWN_PERMISSION`: Requested permission is not registered in the canonical registry.
- `SCOPE_MISMATCH`: Caller's assigned roles do not match the required authorization scope.
- `SUPPORT_CASE_UNAUTHORIZED`: Platform support access not authorized for the specified case or tenant.
- `EMERGENCY_ACCESS_NOT_APPROVED`: Emergency access lacks valid, unexpired out-of-band approval.
- `PROJECT_ASSIGNMENT_REQUIRED`: Organization member is not assigned to the requested project.

### Audit Event Types (`AuthorizationAuditEventType`)
- `SUPPORT_ACCESS_ALLOWED`: Support staff authorized to inspect tenant data under a verified case.
- `SUPPORT_ACCESS_DENIED`: Unauthorized attempt to access tenant support data.
- `EMERGENCY_ACCESS_ALLOWED`: Break-glass / emergency execution authorized under active approval.
- `EMERGENCY_ACCESS_DENIED`: Emergency access attempt denied due to missing/expired approval.
- `PROJECT_ASSIGNMENT_DENIAL`: Access denied because organization member lacks project assignment.

---

## 6. Migration and Operational Impact

1. **Framework Independence:** The `devflow-auth` module has zero dependencies on Spring, JPA, or web frameworks.
2. **Backward Compatibility:** All existing contracts (`AuthorizationRequest`, `AuthorizationDecision`, `AuthorizationContext`, `Role`, `Permission`, `RoleScope`) remain fully backward compatible.
3. **Application Preservation:** The existing application role `OrganizationRole.DEVELOPER` in `backend/app` maps to `Role.ORGANIZATION_MEMBER` without database schema or enum modifications.
4. **ARB Review:** This document is marked *Product-owner approved; ARB ratification pending* to preserve the integrity of the historical ARB approval of `AUTHORIZATION_MODEL.md` v1.0.0.
5. **Persistence & Scoped Assignments (Stage 4):** Persisted in PostgreSQL via `roles`, `permissions`, `role_permissions`, `organization_role_assignments`, and `project_role_assignments` tables (migration `V13__create_authorization_tables.sql`). See [Stage 4 Persistence Architecture](STAGE-4-PERSISTENCE-ARCHITECTURE.md).
