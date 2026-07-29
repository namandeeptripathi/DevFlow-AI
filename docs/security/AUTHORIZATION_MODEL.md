# DevFlow — Authorization Model

> **Version:** 1.0.0
> **Status:** Final / Architecture Review Board (ARB) Approved
> **Author:** Principal Security Architect
> **Date:** 2026-07-29
> **Classification:** Internal — Engineering & Security

---

## Table of Contents

1. [Purpose](#1-purpose)
2. [Authorization Architecture](#2-authorization-architecture)
3. [Authorization Principles](#3-authorization-principles)
4. [Authorization Model](#4-authorization-model)
5. [Role-Based Access Control (RBAC)](#5-role-based-access-control-rbac)
6. [Permission Model](#6-permission-model)
7. [Ownership Model](#7-ownership-model)
8. [Multi-Tenant Authorization](#8-multi-tenant-authorization)
9. [Resource Authorization](#9-resource-authorization)
10. [Permission Evaluation Flow](#10-permission-evaluation-flow)
11. [Administrative Authorization](#11-administrative-authorization)
12. [Delegation](#12-delegation)
13. [Audit & Authorization Events](#13-audit--authorization-events)
14. [Security Considerations](#14-security-considerations)
15. [Future Evolution](#15-future-evolution)
16. [Architectural Principles & Key Design Decisions](#16-architectural-principles--key-design-decisions)

---

## 1. Purpose

### 1.1 What is Authorization?

**Authorization** is the process of determining whether an authenticated principal — a verified user identity — is **permitted** to perform a specific action on a specific resource. It is the system's answer to a different and independent question from authentication:

```
Authentication asks:   "WHO are you?"       -> Verified by JWT (see Authentication Strategy)
Authorization asks:    "WHAT can you do?"   -> Decided by the Authorization Engine
```

Where authentication establishes *identity*, authorization enforces *access policy*. A user may be fully and correctly authenticated, holding a valid JWT, while still being explicitly denied access to a resource they are not permitted to interact with. These two concerns are architecturally separate and must never be conflated.

### 1.2 Authentication vs. Authorization

| Dimension | Authentication | Authorization |
| :--- | :--- | :--- |
| **Question answered** | Who is this principal? | What is this principal allowed to do? |
| **Mechanism** | JWT validation, credential verification | Role evaluation, permission checks, ownership checks |
| **Point in request lifecycle** | Security Filter Chain (before routing) | Domain module (after routing) |
| **Failure response** | `401 Unauthorized` | `403 Forbidden` |
| **State held** | Stateless (JWT claims) | Stateful (role assignments, memberships in PostgreSQL) |
| **Covered by** | Authentication Strategy document | This document |

### 1.3 Why Authorization is Critical in a Multi-Tenant Engineering Platform

DevFlow is a multi-tenant platform. A single deployed instance serves many independent engineering organizations simultaneously, each with their own users, projects, repositories, and AI-generated insights. Without a robust authorization model, the following failure modes become possible:

*   **Cross-tenant data leakage:** A developer in Organization A reads or modifies data belonging to Organization B.
*   **Privilege escalation:** A read-only contributor modifies project settings, deletes tasks, or exports analytics they are not entitled to see.
*   **Insider threats:** A disgruntled team member accesses repositories or AI review outputs they were removed from.
*   **Unauthorized AI access:** A user triggers expensive AI code review operations on repositories their organization has not connected.
*   **Cascading blast radius:** An overly permissive role assignment grants one user the ability to affect the entire organization's workflows, automations, and notifications.

DevFlow custodies proprietary source code histories, engineering velocity data, and confidential AI-generated analyses. Authorization is not a secondary feature — it is a foundational contract with every enterprise customer.

---

## 2. Authorization Architecture

### 2.1 Layered Authorization Model

Authorization in DevFlow is evaluated across multiple distinct enforcement layers. No single layer is trusted in isolation; each layer contributes an independent check.

```
+-------------------------------------------------------------------+
|                      AUTHENTICATED USER                           |
|                                                                   |
|  Identity confirmed via RS256 JWT (see Authentication Strategy)   |
+-----------------------------------+-------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------+
|                       SECURITY CONTEXT                            |
|                                                                   |
|  Populated by the Security Filter Chain from verified JWT claims: |
|                                                                   |
|  +-------------------+  +-------------------+  +---------------+ |
|  |  userId (UUID)    |  |  orgId (UUID)     |  |  roles[]      | |
|  +-------------------+  +-------------------+  +---------------+ |
|  +-------------------+  +-------------------+                   |
|  |  scopes[]         |  |  tenantContext     |                   |
|  +-------------------+  +-------------------+                   |
+-----------------------------------+-------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------+
|                     AUTHORIZATION ENGINE                          |
|                       (devflow-auth module)                       |
|                                                                   |
|  +------------------------+  +--------------------------------+   |
|  |  Role Resolver         |  |  Permission Evaluator          |   |
|  |  (loads roles for org) |  |  (evaluates permission claims) |   |
|  +------------------------+  +--------------------------------+   |
|                                                                   |
|  +------------------------+  +--------------------------------+   |
|  |  Ownership Checker     |  |  Tenant Boundary Enforcer      |   |
|  |  (creator/member check)|  |  (hard orgId isolation)        |   |
|  +------------------------+  +--------------------------------+   |
+-----------------------------------+-------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------+
|                    PERMISSION EVALUATION                          |
|                                                                   |
|  Evaluation order (all conditions must pass):                    |
|                                                                   |
|  1. Tenant Boundary Check  ->  Does orgId match resource?        |
|  2. Role Hierarchy Check   ->  Does the user's role include      |
|                                the required permission?           |
|  3. Ownership Check        ->  Is the user the resource owner     |
|                                (for owner-gated operations)?     |
|  4. Resource State Check   ->  Is the resource in a state that   |
|                                permits this operation?            |
+-----------------------------------+-------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------+
|                      PROTECTED RESOURCE                           |
|                                                                   |
|  Project | Task | Repository | ChatSession | Document |           |
|  MetricSnapshot | AutomationRule | CommentThread | Notification   |
+-------------------------------------------------------------------+
```

### 2.2 Component Responsibilities

| Component | Responsibility |
| :--- | :--- |
| **Security Context** | The in-memory, request-scoped data structure populated once per request by the Security Filter Chain. It holds the verified principal identity (`userId`), the active tenant (`orgId`), and the coarse-grained role list embedded in the JWT. All downstream authorization checks read from this context — they never re-query the JWT. |
| **Authorization Engine** | The logical authorization subsystem within the `devflow-auth` module. It is invoked by all other domain modules via the compiled `AuthApi` public interface. It never relies on the calling module's internal state; it evaluates decisions based on the Security Context and its own data. |
| **Role Resolver** | Queries the `WorkspaceMembership` records for the current `(userId, orgId)` pair to determine the user's active role(s) within the current tenant. Roles embedded in the JWT serve as a fast-path cache; the Role Resolver performs fresh lookups when high-consistency decisions are required (e.g., administrative actions). |
| **Permission Evaluator** | Maps the resolved role(s) against the platform's permission registry to determine whether the specific `action:resource` combination is authorized. Returns a binary `ALLOW` or `DENY` decision. |
| **Ownership Checker** | For operations where resource ownership grants additional or exclusive rights (e.g., project deletion, automation rule management), verifies that the requesting user is the recorded creator or owner of the resource. |
| **Tenant Boundary Enforcer** | The first and hardest check. Validates that the `orgId` embedded in the JWT matches the `organizationId` of the resource being accessed. A mismatch results in an immediate `DENY` — no further evaluation is performed. |

---

## 3. Authorization Principles

### 3.1 Principle of Least Privilege

Every user, role, and service identity is granted the minimum set of permissions required to perform their intended function — no more.

**Why it exists in DevFlow:** DevFlow users span many organizational roles — developers, project managers, team leads, and executives — each requiring distinct access boundaries. A developer assigned to a project should not be able to restructure the organization, delete other users' repositories, or export all analytics data. Least privilege ensures that the blast radius of a compromised or misbehaving account is structurally bounded.

### 3.2 Default Deny

When a permission evaluation completes and no explicit `ALLOW` decision can be confirmed, the result is **always `DENY`**. There are no implicit or inherited grants that are assumed by default.

**Why it exists in DevFlow:** In a platform where new resources (projects, repositories, documents) are continuously created, it is impossible for administrators to manually deny access to every object for every user. Default deny reverses this burden — access is always opt-in and explicitly granted, never opt-out.

### 3.3 Defense in Depth

Authorization is enforced at multiple layers independently:
1. **JWT claims** carry coarse-grained roles (fast-path check).
2. **Method-level authorization** validates permissions at the application service boundary.
3. **Data layer tenant filters** ensure queries are always scoped to the active organization.

**Why it exists in DevFlow:** No single authorization layer is impenetrable. A misconfigured permission annotation, a missing `@PreAuthorize`, or a flawed role assignment could allow unauthorized access at one layer. Defense in depth ensures that each layer is an independent backstop.

### 3.4 Explicit Permission Evaluation

Every access-controlled operation must result from an **explicit, evaluated permission check**. Implicit access (e.g., "if the user can see the project, they can see all its tasks") is not permitted. Each resource type and action must have its own permission definition.

**Why it exists in DevFlow:** Implicit permission chains create invisible attack surfaces. When permissions are evaluated explicitly for every operation, permission regressions are caught statically through permission registry analysis rather than discovered through security incidents.

### 3.5 Tenant Isolation

An authenticated user's authorization scope is strictly bounded to their **active organization**. No cross-organization data access is possible regardless of what roles or permissions a user holds elsewhere.

**Why it exists in DevFlow:** Multi-tenancy is a hard contractual guarantee DevFlow makes to every enterprise customer. A security team at one organization cannot assume that their administrative access does not extend to another organization sharing the same platform instance.

### 3.6 Ownership

Resource creators hold a special **owner** relationship to their resources. Ownership grants certain exclusive operations (e.g., deleting a project they created) that cannot be delegated through standard roles alone.

**Why it exists in DevFlow:** Role-based permissions operate at a broad level. Ownership provides fine-grained control that allows individuals to protect their own work without requiring administrators to manually manage per-resource access control lists.

### 3.7 Separation of Duties

No single user role should hold both the ability to perform an action **and** the ability to approve or audit that same action. Administrative privileges are separated from operational privileges.

**Why it exists in DevFlow:** In engineering organizations, separation of duties prevents a single compromised account from performing a destructive action and then concealing it by clearing audit logs or reassigning permissions. The `ORGANIZATION_OWNER` can manage memberships but cannot bypass audit records.

---

## 4. Authorization Model

### 4.1 Hierarchical Authorization Scope

DevFlow's authorization model is organized as a five-level scope hierarchy. Each level narrows the authorization context and inherits constraints from the level above it.

```
+----------------------------------------------------------------------+
|                         PLATFORM LEVEL                               |
|  Scope:  Entire DevFlow deployment                                   |
|  Actors: Platform Administrators (DevFlow internal team)             |
|  Controls: System-wide feature flags, tenant provisioning,           |
|            platform-wide incident response                           |
+----------------------------------+-----------------------------------+
                                   |
                                   | (Organization is a tenant boundary)
                                   v
+----------------------------------------------------------------------+
|                       ORGANIZATION LEVEL                             |
|  Scope:  Single tenant — one independent engineering organization    |
|  Actors: Organization Owners, Workspace Administrators, Members      |
|  Controls: Membership management, billing, global settings,          |
|            project creation, integration authorization               |
+----------------------------------+-----------------------------------+
                                   |
                        +----------+----------+
                        |                     |
                        v                     v
+---------------------+       +---------------------------------+
|    PROJECT LEVEL    |       |       REPOSITORY LEVEL          |
|                     |       |                                 |
|  Scope: One project |       |  Scope: One connected Git repo  |
|  Actors: Members    |       |  Actors: Members with repo      |
|  with project-level |       |  access granted by org          |
|  roles              |       |  administrators                 |
+----------+----------+       +----------------+----------------+
           |                                   |
           v                                   v
+----------------------------------------------------------------------+
|                         RESOURCE LEVEL                               |
|  Scope:  Individual entities within a Project or Repository:         |
|          Task, Epic, Cycle, Document, ChatSession,                   |
|          AutomationRule, CommentThread, MetricSnapshot               |
|  Actors: Resource owners + role-permitted members                    |
|  Controls: Ownership-gated actions, visibility rules,                |
|            resource state-based permission gates                     |
+----------------------------------------------------------------------+
```

### 4.2 Inheritance Rules

Authorization decisions flow **downward** through the hierarchy with strict rules:

| Rule | Description |
| :--- | :--- |
| **Parent grants access to children** | A user with `ORGANIZATION_OWNER` role has implicit access to all projects and repositories within that organization. They do not need separate project-level grants. |
| **Child cannot exceed parent** | A project-level role cannot grant permissions that the user does not hold at the organization level. Permissions are bounded by the organization membership. |
| **Tenant boundaries never inherit across** | No permission from one organization propagates to another, regardless of hierarchy. The tenant boundary is absolute. |
| **Resource state overrides roles** | A user with `project.write` permission cannot modify a project that has been archived or locked, regardless of their role. Resource state is an independent gate. |
| **Ownership supplements but does not override tenant boundaries** | A user who owns a resource cannot access it from a different organization context, even if they are technically the creator. `orgId` is the first and hardest check. |

---

## 5. Role-Based Access Control (RBAC)

### 5.1 Platform Roles

Platform roles are assigned only to DevFlow's internal engineering and operations team. They are not visible to or assignable by any tenant organization.

| Role | Scope | Purpose |
| :--- | :--- | :--- |
| `PLATFORM_ADMIN` | Entire deployment | Full system access including tenant provisioning, feature flag management, and emergency access. Strictly limited to the core engineering and security team. |
| `PLATFORM_SUPPORT` | Read-only cross-tenant | Allows the support team to investigate reported issues without the ability to modify any tenant's data. All access is logged. |

### 5.2 Organization Roles

Organization roles are the primary access control dimension in DevFlow. They are assigned per-organization, per-user through `WorkspaceMembership`.

| Role | Scope | Permissions Summary |
| :--- | :--- | :--- |
| `ORGANIZATION_OWNER` | Organization-wide | Full administrative control: membership management, billing, integration setup, project creation and deletion, global settings. The bootstrapping role assigned to the account that created the organization. |
| `ORGANIZATION_ADMIN` | Organization-wide | Administrative access excluding billing and organization deletion. Can manage members, roles, integrations, and all projects. Cannot transfer ownership or delete the organization. |
| `ORGANIZATION_MEMBER` | Organization-wide | Standard access. Can be added to projects, create tasks, contribute to repositories, and interact with the AI engine within the bounds of their project assignments. |
| `ORGANIZATION_VIEWER` | Organization-wide | Read-only access to all non-sensitive organization data (project boards, analytics dashboards, documents). Cannot create, modify, or delete any resource. |
| `ORGANIZATION_BILLING` | Billing scope only | Access exclusively to billing management, invoice history, and subscription settings. No access to any engineering data. |

### 5.3 Project Roles

Project roles narrow permissions within the scope of a single project. A user must already hold an organization-level role before they can be assigned a project role.

| Role | Scope | Permissions Summary |
| :--- | :--- | :--- |
| `PROJECT_LEAD` | Single project | Full project management authority: project settings, board configuration, cycle management, member assignment, epic planning. Equivalent to a technical lead or scrum master. |
| `PROJECT_MEMBER` | Single project | Can create, edit, and comment on tasks and epics. Can view all project boards and cycles. Cannot change project structure (add columns, delete the project). |
| `PROJECT_VIEWER` | Single project | Read-only access to project boards, tasks, cycles, and epics. Cannot create or modify any resource within the project. |

### 5.4 Default Role Assignments

| Event | Default Role Assigned |
| :--- | :--- |
| User creates an organization | `ORGANIZATION_OWNER` |
| User is invited to an organization | `ORGANIZATION_MEMBER` (can be overridden by inviter) |
| User is added to a project | `PROJECT_MEMBER` (can be overridden by the `PROJECT_LEAD`) |
| Organization is created | No `PROJECT_LEAD` is pre-assigned — project leads are explicitly designated |

### 5.5 Role Hierarchy

Roles form an implicit hierarchy where higher-level roles encompass the permissions of all lower-level roles within their scope:

```
PLATFORM_ADMIN
    |
    +---> ORGANIZATION_OWNER
              |
              +---> ORGANIZATION_ADMIN
                        |
                        +---> ORGANIZATION_MEMBER
                                   |
                                   +---> PROJECT_LEAD
                                              |
                                              +---> PROJECT_MEMBER
                                                         |
                                                         +---> PROJECT_VIEWER
                                                         |
                                                         +---> ORGANIZATION_VIEWER
```

**Role hierarchy rules:**
- A higher-level role always has all the permissions of all roles beneath it in the hierarchy.
- Role hierarchy operates **within scope boundaries**. `ORGANIZATION_OWNER` does not inherit `PLATFORM_ADMIN` capabilities.
- A user may hold **multiple roles simultaneously** (e.g., `ORGANIZATION_MEMBER` + `PROJECT_LEAD` on specific projects). The union of granted permissions applies.

### 5.6 Role Lifecycle

```
  INVITATION SENT
       |
       v
  PENDING (User receives invitation)
       |
       +-- User accepts --> ACTIVE
       |
       +-- Invitation expired --> CANCELLED
       |
  ACTIVE
       |
       +-- Admin changes role --> ROLE_CHANGED (audit logged)
       |
       +-- Admin removes member --> REMOVED
       |
       +-- User leaves org --> REMOVED
       |
  REMOVED (Access immediately revoked; tokens invalidated)
```

### 5.7 Custom Roles (Future)

In a future release, `ORGANIZATION_OWNER` and `ORGANIZATION_ADMIN` principals will be able to define custom roles with specific permission subsets. Custom roles will be defined as a named collection of explicitly selected permissions from the platform's permission registry. They will not override the platform role hierarchy but will slot into the organization-level role space alongside the standard roles.

---

## 6. Permission Model

### 6.1 Permission Naming Convention

All permissions follow a consistent `{resource}.{action}` naming pattern:

```
{resource}.{action}

Where:
  {resource} = The domain resource the permission governs
  {action}   = The specific operation being authorized

Examples:
  project.read
  project.write
  project.delete
  task.create
  task.assign
  repo.connect
  repo.scan
  ai.review
  ai.chat
  kb.manage
  analytics.view
  automation.configure
```

### 6.2 Permission Grouping

Permissions are organized into resource domains that correspond directly to DevFlow's Bounded Contexts:

| Domain | Permission Group | Example Permissions |
| :--- | :--- | :--- |
| **Identity & Access** | `org.*`, `member.*` | `org.manage`, `org.delete`, `member.invite`, `member.remove` |
| **Project Management** | `project.*`, `task.*`, `cycle.*`, `epic.*` | `project.read`, `project.write`, `project.delete`, `task.create`, `task.assign`, `cycle.manage` |
| **Repository Intelligence** | `repo.*` | `repo.connect`, `repo.disconnect`, `repo.read`, `repo.scan`, `repo.configure` |
| **AI Engine** | `ai.*` | `ai.chat`, `ai.review`, `ai.review.approve`, `ai.configure` |
| **Knowledge Base** | `kb.*` | `kb.read`, `kb.write`, `kb.manage`, `kb.delete` |
| **Developer Analytics** | `analytics.*` | `analytics.view`, `analytics.export`, `analytics.configure` |
| **Workflow Automation** | `automation.*` | `automation.read`, `automation.configure`, `automation.delete` |
| **Collaboration** | `comment.*` | `comment.create`, `comment.edit`, `comment.delete`, `comment.moderate` |
| **Notifications** | `notification.*` | `notification.configure`, `notification.broadcast` |
| **Billing** | `billing.*` | `billing.view`, `billing.manage` |

### 6.3 Action-Based Permissions

Actions map to CRUD operations plus domain-specific operations:

| Action Suffix | Semantics |
| :--- | :--- |
| `.read` / `.view` | Read-only access to a resource or collection |
| `.create` | Create a new instance of the resource |
| `.write` / `.edit` | Modify an existing resource's attributes |
| `.delete` / `.archive` | Remove or archive a resource |
| `.manage` | Full lifecycle control (create + write + delete + configure) |
| `.configure` | Modify structural or behavioral settings |
| `.assign` | Associate one resource with another (e.g., task to a user) |
| `.export` | Extract data from the system |
| `.approve` | Authorize a pending action (e.g., approve an AI review) |
| `.broadcast` | Send platform-wide or organization-wide messages |

### 6.4 Composite Permissions

Some operations require holding multiple permissions simultaneously. The Permission Evaluator applies `AND` logic for composite requirements:

| Operation | Required Permissions |
| :--- | :--- |
| Move a task to a different cycle | `task.write` AND `cycle.manage` |
| Connect a repository and configure scanning | `repo.connect` AND `repo.configure` |
| Export analytics data | `analytics.view` AND `analytics.export` |
| Configure AI review on a repository | `repo.scan` AND `ai.review` AND `ai.configure` |

### 6.5 Role-to-Permission Mapping

The following table defines the canonical mapping between roles and their included permissions:

| Permission | VIEWER | MEMBER | PROJECT_LEAD | ADMIN | OWNER |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `project.read` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `project.write` | — | ✓ | ✓ | ✓ | ✓ |
| `project.delete` | — | — | ✓* | ✓ | ✓ |
| `task.create` | — | ✓ | ✓ | ✓ | ✓ |
| `task.assign` | — | ✓ | ✓ | ✓ | ✓ |
| `repo.connect` | — | — | — | ✓ | ✓ |
| `repo.scan` | — | ✓ | ✓ | ✓ | ✓ |
| `ai.chat` | — | ✓ | ✓ | ✓ | ✓ |
| `ai.review` | — | ✓ | ✓ | ✓ | ✓ |
| `kb.write` | — | ✓ | ✓ | ✓ | ✓ |
| `kb.manage` | — | — | ✓ | ✓ | ✓ |
| `analytics.view` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `analytics.export` | — | — | ✓ | ✓ | ✓ |
| `automation.configure` | — | — | ✓ | ✓ | ✓ |
| `member.invite` | — | — | — | ✓ | ✓ |
| `member.remove` | — | — | — | ✓ | ✓ |
| `org.manage` | — | — | — | — | ✓ |
| `billing.manage` | — | — | — | — | ✓ |

*`project.delete` for `PROJECT_LEAD` applies only to projects they own.

### 6.6 Future Extensibility

The permission model is designed as a **registry-driven system**. New permissions are added to the permission registry without modifying role definitions. New roles are assembled from existing permission sets without altering the permission semantics. This separation of concerns allows the permission vocabulary to grow independently of the role hierarchy, and provides the foundation for ABAC (Attribute-Based Access Control) extensions described in Section 15.

---

## 7. Ownership Model

Ownership is a first-class authorization concept in DevFlow. It represents the relationship between a principal and a resource they created or were explicitly designated to own. Ownership supplements role-based permissions for operations that require a personal accountability boundary.

### 7.1 Ownership Rules by Resource

| Resource | Owner | Owner-Exclusive Privileges |
| :--- | :--- | :--- |
| **Organization** | The founding user (`ORGANIZATION_OWNER` role) | Organization deletion, ownership transfer, billing management |
| **Project** | The user who created the project | Project deletion (even if their role was subsequently downgraded), project transfer |
| **Repository Connection** | The admin who connected the repository | Repository disconnection without requiring `ORGANIZATION_OWNER` approval |
| **Document (Knowledge Base)** | The user who authored the document | Document deletion, permanent revision purge |
| **ChatSession (AI)** | The user who initiated the session | Session deletion, session export, sharing the session |
| **AutomationRule** | The user who created the rule | Rule deletion, rule enable/disable without admin approval |
| **CommentThread** | The user who opened the thread | Thread resolution and thread deletion |
| **Comment** | The comment author | Comment editing and soft deletion of own comments |
| **Personal Resources** | The user themselves | Profile settings, personal notification preferences, personal API keys (future) |

### 7.2 Ownership vs. Role-Based Access

Ownership does not override tenant boundaries or role-based permissions at higher scopes. An owner's exclusive privileges apply only when:
1. The user's organization membership is active.
2. The JWT `orgId` matches the resource's `organizationId`.
3. The user's base role permits access to the resource type (ownership cannot bypass the `Default Deny` principle for resource types the user's role has no access to).

### 7.3 Ownership Transfer

Ownership of transferable resources (Organizations, Projects) can be transferred by the current owner to another user with an appropriate role within the same organization. Transfer is an audited action and requires the recipient to accept the transfer explicitly.

---

## 8. Multi-Tenant Authorization

### 8.1 Workspace Isolation Model

DevFlow implements **Workspace Isolation** as the primary multi-tenancy guarantee. Each organization is a fully isolated workspace — a logical boundary within which all resources, permissions, and audit events are contained.

```
+---------------------------------------------------------------------+
|                      DEVFLOW PLATFORM                               |
|                                                                     |
|  +-----------------------+    +-----------------------+             |
|  |   ORGANIZATION A      |    |   ORGANIZATION B      |             |
|  |   (Acme Engineering)  |    |   (Startup X)         |             |
|  |                       |    |                       |             |
|  |  Users    Projects    |    |  Users    Projects    |             |
|  |  Repos    Analytics   |    |  Repos    Analytics   |             |
|  |  AI Data  Documents   |    |  AI Data  Documents   |             |
|  |                       |    |                       |             |
|  |  [ No path between ]  |    |  [ No path between ]  |             |
|  +-----------------------+    +-----------------------+             |
|                                                                     |
|  A user in Org A who is ALSO a member of Org B cannot access        |
|  Org B's data while authenticated under Org A's JWT context.        |
+---------------------------------------------------------------------+
```

### 8.2 Tenant Boundary Enforcement

The tenant boundary is enforced in three independent layers:

| Layer | Mechanism | What It Prevents |
| :--- | :--- | :--- |
| **JWT Claims** | The `orgId` claim in the JWT encodes the active organization. Switching organizations requires a new token issuance. | A single token cannot be used to access resources in multiple organizations. |
| **Tenant Resolution Filter** | On every request, the `TenantResolutionFilter` validates that the JWT `orgId` maps to an active `WorkspaceMembership`. Suspended or removed memberships result in `403 Forbidden`. | Revoked members are blocked even if their JWT has not yet expired. |
| **Data Layer Scoping** | All database queries in protected modules carry a mandatory `organizationId` filter applied by Hibernate's tenant discriminator. | Even if an authorization check were bypassed at the application layer, queries would still return no data for a mismatched tenant. |

### 8.3 Cross-Tenant Prevention

Cross-tenant access is architecturally impossible through normal request paths:

1. A request arrives with `orgId = org_A`.
2. The Tenant Resolution Filter confirms membership in `org_A`.
3. All resource lookups filter by `organizationId = org_A`.
4. If a resource with a matching ID exists in `org_B` but not `org_A`, the query returns **empty** — not `403`. This is deliberate: it prevents ID enumeration attacks that confirm the existence of resources in other tenants.

### 8.4 Context Switching

A user who is a member of multiple organizations holds independent `WorkspaceMembership` records for each. To operate within a different organization context, the user must explicitly switch their active organization in the web client. This triggers a token exchange that:
1. Validates the user holds an active membership in the target organization.
2. Issues a new JWT with `orgId` set to the target organization.
3. Updates the `roles` and `scopes` claims to reflect the user's role in the **target** organization (which may differ from their role in their previous organization).

### 8.5 Tenant Resolution Sequence

```
Incoming Request          Tenant Filter           Auth Module        PostgreSQL
       |                       |                      |                  |
       |-- JWT: orgId=org_A -->|                      |                  |
       |                       |-- Query membership ->|                  |
       |                       |                      |-- SELECT from -->|
       |                       |                      |   workspace_     |
       |                       |                      |   memberships    |
       |                       |                      |<-- Active record-|
       |                       |<-- Membership valid --|                  |
       |                       |-- Set DB schema      |                  |
       |                       |   to org_A context   |                  |
       |                       |-- Set TenantContext  |                  |
       |-- Proceed to domain module ----------------->|                  |
```

---

## 9. Resource Authorization

### 9.1 Projects

| Operation | Required Permission | Notes |
| :--- | :--- | :--- |
| List organization projects | `project.read` | Returns only projects where membership exists |
| View project details | `project.read` | |
| Create a project | `project.write` | Requires `ORGANIZATION_ADMIN` or `ORGANIZATION_OWNER` |
| Update project settings | `project.write` | |
| Manage project board structure | `project.write` | Includes adding/removing columns |
| Archive a project | `project.delete` | `PROJECT_LEAD` may archive their own project |
| Delete a project permanently | `project.delete` | `ORGANIZATION_ADMIN` or project owner only |

### 9.2 Tasks

| Operation | Required Permission | Notes |
| :--- | :--- | :--- |
| View tasks on a board | `task.read` | Inherits from `project.read` |
| Create a task | `task.create` | Any `PROJECT_MEMBER` or above |
| Edit a task's description, priority, status | `task.write` | |
| Assign a task to a user | `task.assign` | Assignee must be a project member |
| Move a task between cycles | `task.write` + `cycle.manage` | Composite permission |
| Delete a task | `task.delete` | Task creator or `PROJECT_LEAD` |

### 9.3 Repositories

| Operation | Required Permission | Notes |
| :--- | :--- | :--- |
| View connected repositories | `repo.read` | All organization members |
| Connect a new repository | `repo.connect` | `ORGANIZATION_ADMIN` or `ORGANIZATION_OWNER` only |
| Disconnect a repository | `repo.disconnect` | Admin or repository connection owner |
| Trigger a repository sync | `repo.scan` | `PROJECT_MEMBER` or above |
| View commit history | `repo.read` | |
| View pull request list | `repo.read` | |
| Configure repository scanning settings | `repo.configure` | `ORGANIZATION_ADMIN` or above |

### 9.4 AI Reviews & Chat

| Operation | Required Permission | Notes |
| :--- | :--- | :--- |
| Create a new AI chat session | `ai.chat` | Any organization member |
| View own chat sessions | `ai.chat` | User is session owner — automatic |
| View another user's chat session | `ai.chat` + session sharing grant | Sessions are private by default |
| Request an AI code review on a PR | `ai.review` | Requires `repo.scan` on the linked repo |
| Approve or dismiss an AI review suggestion | `ai.review.approve` | `PROJECT_LEAD` or `ORGANIZATION_ADMIN` |
| Configure AI model preferences | `ai.configure` | `ORGANIZATION_ADMIN` or `ORGANIZATION_OWNER` |

### 9.5 Knowledge Base

| Operation | Required Permission | Notes |
| :--- | :--- | :--- |
| View documents | `kb.read` | All organization members |
| Create a document | `kb.write` | `ORGANIZATION_MEMBER` or above |
| Edit a document | `kb.write` | Author or `kb.manage` holder |
| Delete a document | `kb.manage` | Author or `PROJECT_LEAD` / Admin |
| Manage folder structure | `kb.manage` | `PROJECT_LEAD` or above |
| View document revision history | `kb.read` | |

### 9.6 Developer Analytics

| Operation | Required Permission | Notes |
| :--- | :--- | :--- |
| View team-level analytics | `analytics.view` | All organization members (aggregate data) |
| View individual contributor metrics | `analytics.view` | Members can only view their own individual data; admins view all |
| Export analytics data | `analytics.export` | `PROJECT_LEAD` or `ORGANIZATION_ADMIN` |
| Configure analytics reporting | `analytics.configure` | `ORGANIZATION_ADMIN` or `ORGANIZATION_OWNER` |

### 9.7 Workflow Automation

| Operation | Required Permission | Notes |
| :--- | :--- | :--- |
| View existing automation rules | `automation.read` | `ORGANIZATION_MEMBER` or above |
| Create an automation rule | `automation.configure` | `PROJECT_LEAD` or above |
| Edit an automation rule | `automation.configure` | Rule creator or `ORGANIZATION_ADMIN` |
| Delete an automation rule | `automation.configure` | Rule owner or `ORGANIZATION_ADMIN` |
| Enable / disable a rule | `automation.configure` | Rule owner or `PROJECT_LEAD` |
| View automation execution logs | `automation.read` | |

### 9.8 Notifications

| Operation | Required Permission | Notes |
| :--- | :--- | :--- |
| View own notifications | N/A (self-access) | Always permitted for the authenticated user |
| Configure own notification preferences | N/A (self-access) | Always permitted |
| Configure organization-level notification channels | `notification.configure` | `ORGANIZATION_ADMIN` or above |
| Send a broadcast notification | `notification.broadcast` | `ORGANIZATION_ADMIN` or above |

---

## 10. Permission Evaluation Flow

The following sequence describes the complete authorization decision path for every protected API request in DevFlow.

```
Authenticated Request        Auth Engine            PostgreSQL         Resource Module
       |                         |                      |                    |
       | [JWT validated,         |                      |                    |
       |  SecurityContext set]   |                      |                    |
       |                         |                      |                    |
       |-- 1. Invoke AuthApi --->|                      |                    |
       |   (permission,          |                      |                    |
       |    resourceId)          |                      |                    |
       |                         |                      |                    |
       |                         |-- 2. TENANT CHECK -->|                    |
       |                         |   Does resource.orgId|                    |
       |                         |   == JWT.orgId?      |                    |
       |                         |<-- orgId verified ---|                    |
       |                         |                      |                    |
       |              [DENY if orgId mismatch -> 404 Not Found]              |
       |                         |                      |                    |
       |                         |-- 3. MEMBERSHIP  --->|                    |
       |                         |   CHECK              |                    |
       |                         |   Is WorkspaceMember-|                    |
       |                         |   ship ACTIVE?       |                    |
       |                         |<-- Membership status-|                    |
       |                         |                      |                    |
       |             [DENY if membership inactive -> 403 Forbidden]          |
       |                         |                      |                    |
       |                         |-- 4. ROLE RESOLUTION |                    |
       |                         |   Load roles for     |                    |
       |                         |   (userId, orgId)    |                    |
       |                         |<-- roles[] ----------|                    |
       |                         |                      |                    |
       |                         |-- 5. PERMISSION      |                    |
       |                         |   EVALUATION         |                    |
       |                         |   Does any role in   |                    |
       |                         |   roles[] grant the  |                    |
       |                         |   required           |                    |
       |                         |   permission?        |                    |
       |                         |                      |                    |
       |             [DENY if no role grants permission -> 403 Forbidden]    |
       |                         |                      |                    |
       |                         |-- 6. OWNERSHIP CHECK |                    |
       |                         |   (if operation is   |                    |
       |                         |   owner-gated)       |                    |
       |                         |   Is userId ==       |                    |
       |                         |   resource.ownerId?  |                    |
       |                         |                      |                    |
       |             [DENY if owner-gated and not owner -> 403 Forbidden]    |
       |                         |                      |                    |
       |                         |-- 7. RESOURCE STATE  |                    |
       |                         |   CHECK              |                    |
       |                         |   Is resource in a   |                    |
       |                         |   state permitting   |                    |
       |                         |   this operation?    |                    |
       |                         |   (e.g., not archived|                    |
       |                         |    not locked)       |                    |
       |                         |                      |                    |
       |             [DENY if resource state blocks operation -> 422]        |
       |                         |                      |                    |
       |                         |-- 8. DECISION: ALLOW |                    |
       |<-- ALLOW decision ------|                      |                    |
       |                         |                      |                    |
       |-- Proceed to resource -->                      |                   |
       |   module                                       |                   |
       |                         |                      |                    |
       |                                                |--- Execute ------->|
       |<----------------------------------------------|  query with tenant  |
       |                                                |  filter applied    |
```

### 10.1 Decision Matrix

| Condition | Result | HTTP Status |
| :--- | :--- | :--- |
| `orgId` mismatch between JWT and resource | DENY | `404 Not Found` (prevents tenant enumeration) |
| Membership inactive or revoked | DENY | `403 Forbidden` |
| No role grants the required permission | DENY | `403 Forbidden` |
| Owner-gated operation, not the owner | DENY | `403 Forbidden` |
| Resource state blocks the operation | DENY | `422 Unprocessable Entity` |
| All checks pass | ALLOW | (operation proceeds) |

---

## 11. Administrative Authorization

### 11.1 Platform Administrators

Platform administrators are DevFlow's internal engineering and operations team members. Their authorization scope spans the entire platform deployment, across all tenant organizations.

**Capabilities:**
- Provisioning and deprovisioning tenant organizations.
- Setting platform-wide feature flags and configuration.
- Accessing system observability dashboards and audit logs.
- Executing emergency procedures (see Section 11.5).

**Controls:** Platform admin access is not self-assignable. It is provisioned through a separate, offline identity process (e.g., corporate identity provider + hardware key). All platform admin actions are logged to an isolated, tamper-resistant audit trail.

### 11.2 Organization Owners

Organization owners are the highest-authority principals within a tenant organization. They hold all permissions within the organization scope and are uniquely authorized to:
- Transfer ownership to another member.
- Delete the organization.
- Manage billing and subscription.
- Override any role assignment within the organization.

An organization must always have at least one active owner. If an owner account is deactivated, ownership must be transferred before deactivation is completed.

### 11.3 Workspace Administrators

`ORGANIZATION_ADMIN` principals manage the day-to-day operations of the organization workspace:
- Inviting and removing members.
- Assigning and modifying project roles.
- Configuring repository integrations.
- Setting up workflow automations and AI preferences.

Workspace administrators cannot perform billing management, organization deletion, or ownership transfer. These operations are exclusively gated to `ORGANIZATION_OWNER`.

### 11.4 Security Administrators (Future)

A planned future role, `ORGANIZATION_SECURITY_ADMIN`, will provide access to:
- Audit log review.
- Forced session revocation.
- Security policy configuration (IP allowlisting, MFA enforcement).
- Viewing active device sessions for all organization members.

This role is specifically designed so that security review responsibilities can be delegated to a dedicated security team member without granting general administrative permissions.

### 11.5 Emergency Access

In security incidents where normal access channels are unavailable or compromised, DevFlow's platform engineering team holds a documented emergency access procedure:

- Emergency access is time-bounded, maximum 4-hour window.
- It requires out-of-band approval from a designated security officer.
- All actions taken under emergency access are logged to an immutable, isolated audit trail that cannot be modified even by platform administrators.
- Affected tenant organizations are notified of any emergency access events.

---

## 12. Delegation

### 12.1 Invitation Model

Access to an organization is granted through an explicit invitation flow:

```
Organization Admin          Auth Module            Invited User
       |                        |                       |
       |-- Invite email ------->|                       |
       |   {email, role}        |                       |
       |                        |-- Create pending ----->|
       |                        |   invitation record   |
       |                        |-- Send invitation --->| (via email)
       |                        |                       |
       |                        |               User registers (if new)
       |                        |               or logs in (if existing)
       |                        |                       |
       |                        |<-- Accept invitation--|
       |                        |-- Create WorkspaceMembership
       |                        |   (userId, orgId, role)
       |                        |-- Issue new JWT with orgId
       |<-- Membership active --|
```

**Delegation rules:**
- An inviter can only assign roles up to and including their own role. An `ORGANIZATION_ADMIN` cannot invite a new `ORGANIZATION_OWNER`.
- Invitations are single-use, time-bound (72-hour expiry), and non-transferable.
- Pending invitations are visible to organization admins and can be revoked before acceptance.

### 12.2 Temporary Permissions

DevFlow does not currently support time-bounded temporary role grants. All role assignments are permanent until explicitly revoked. Temporary permission capabilities are identified as a Phase 3 feature requirement, to be implemented alongside the ABAC evolution described in Section 15.

### 12.3 Team Permissions (Future)

A future `Team` concept will allow grouping organization members into named teams (e.g., "Backend Team", "Platform Team"). Permissions can be assigned at the team level and automatically inherited by all team members. Team membership changes will propagate permission grants and revocations without requiring individual role reassignments.

### 12.4 Delegated Administration

`ORGANIZATION_OWNER` principals can delegate specific administrative capabilities to `ORGANIZATION_ADMIN` principals without transferring full ownership. This follows the Separation of Duties principle — for example, billing management can be delegated to a finance team member with a `ORGANIZATION_BILLING` role without granting them access to engineering data.

### 12.5 Service Identities (Future)

A future Service Account identity type will allow automation systems, CI/CD pipelines, and third-party integrations to interact with DevFlow's APIs under a dedicated, non-human principal:
- Service accounts will hold explicit, scoped permission sets (not roles).
- They will be organization-scoped — a service account belongs to exactly one organization.
- They will be revocable independently of human user accounts.
- Their activity will be logged separately in the audit trail for compliance visibility.

---

## 13. Audit & Authorization Events

Every authorization decision — both grants and denials — is a security-relevant event. DevFlow maintains an immutable authorization audit log that captures the full decision context.

### 13.1 Audit Event Taxonomy

| Event Type | Logged Data | Severity |
| :--- | :--- | :--- |
| `PERMISSION_DENIED` | userId, orgId, attempted action, resource type, resourceId, denial reason, requestId, timestamp | `MEDIUM` |
| `PERMISSION_DENIED_TENANT_MISMATCH` | userId, JWT orgId, resource orgId, resourceId, requestId, timestamp | `HIGH` |
| `ROLE_ASSIGNED` | adminUserId, targetUserId, orgId, role granted, previous role, timestamp | `MEDIUM` |
| `ROLE_CHANGED` | adminUserId, targetUserId, orgId, old role, new role, timestamp | `MEDIUM` |
| `ROLE_REVOKED` | adminUserId, targetUserId, orgId, role removed, reason, timestamp | `MEDIUM` |
| `MEMBER_INVITED` | adminUserId, inviteeEmail, orgId, role offered, timestamp | `LOW` |
| `MEMBER_REMOVED` | adminUserId, removedUserId, orgId, reason, timestamp | `MEDIUM` |
| `OWNERSHIP_TRANSFERRED` | fromUserId, toUserId, resourceType, resourceId, orgId, timestamp | `HIGH` |
| `ORGANIZATION_DELETED` | ownerId, orgId, timestamp | `CRITICAL` |
| `ADMIN_ACTION` | adminUserId, action type, affected entityType, entityId, orgId, timestamp | `HIGH` |
| `EMERGENCY_ACCESS_INITIATED` | platformAdminId, orgId, reason, approvedBy, validUntil, timestamp | `CRITICAL` |
| `EMERGENCY_ACCESS_COMPLETED` | platformAdminId, orgId, actionsPerformed[], timestamp | `CRITICAL` |
| `INVITATION_REVOKED` | adminUserId, invitationId, inviteeEmail, orgId, timestamp | `LOW` |
| `INVITATION_EXPIRED` | invitationId, inviteeEmail, orgId, timestamp | `LOW` |

### 13.2 Audit Log Properties

- **Write-once:** Audit log entries are written as immutable append-only records.
- **Tamper-resistant:** Audit logs are shipped to the centralized observability stack (Grafana Loki) in real-time. Even if the primary database record were modified, the shipped log remains unaltered.
- **Retention:** Audit logs are retained for a minimum of 2 years, configurable per organization for compliance requirements.
- **Queryability:** Audit logs are indexed by `userId`, `orgId`, `resourceId`, and `timestamp`, enabling compliance teams to answer questions like "Who changed this permission and when?" without scanning the full log.

---

## 14. Security Considerations

### 14.1 Vertical Privilege Escalation Prevention

**Threat:** A user modifies their own JWT claims or exploits an API to grant themselves higher roles.

**Mitigations:**
- JWTs are signed with RS256 private keys held exclusively by the backend. Modifying JWT contents invalidates the signature and causes immediate rejection.
- Role assignment endpoints require the assigning user to hold a role **higher** than the role they are granting. An `ORGANIZATION_MEMBER` cannot grant `ORGANIZATION_ADMIN`.
- All role change operations are audited and reviewed.

### 14.2 Horizontal Privilege Escalation Prevention

**Threat:** A user accesses another user's resources within the same organization (e.g., viewing another user's private AI chat sessions).

**Mitigations:**
- Resource ownership is checked explicitly for owner-gated resources.
- Private resources (e.g., `ChatSession`) are not returned in collection queries unless the requesting user is the owner or holds an explicit sharing grant.
- The Permission Evaluator distinguishes between `self` and `other` contexts where relevant (e.g., `analytics.view` returns aggregate data for members but full individual data only for admins viewing others).

### 14.3 The Confused Deputy Problem

**Threat:** A service or component with high privileges is tricked into performing an action on behalf of a lower-privileged principal — effectively laundering privileges.

**Mitigations:**
- The `AuthApi` always evaluates permissions against the **original requesting user's** Security Context — never against the identity of the service making the internal call.
- Internal module-to-module calls do not carry elevated permissions. The calling module passes the original Security Context, not its own identity.
- Background jobs that operate outside of a user request context use Service Account identities with explicitly scoped permissions, not inherited user permissions.

### 14.4 Permission Caching

**Consideration:** Role and permission data could be cached in Redis to reduce PostgreSQL lookups on every authorization check.

**Architectural position:**
- Role data embedded in JWTs serves as a 15-minute fast-path cache for standard checks.
- For high-consistency checks (administrative actions, role assignments, membership validation), the Authorization Engine performs a fresh database read, bypassing any cached representation.
- Cache invalidation occurs immediately on any role change or membership revocation event, propagated through the internal application event system.
- Cache entries are tagged with the `(userId, orgId)` pair and have a maximum TTL of 15 minutes — matching the access token lifetime — ensuring eventual consistency even in the absence of an explicit invalidation event.

### 14.5 Revocation Consistency

Permission revocations (role removal, membership termination) are applied to the **refresh token registry immediately** — a revoked member cannot obtain a new access token with their old organization context. However, existing access tokens carrying the old role claims remain valid until their 15-minute expiry. This is the accepted consistency window — identical to the pattern described in the Authentication Strategy for token revocation.

For high-severity revocations (security incidents, account suspension), the platform optionally blocklists all active access token `jti` values for the affected user in Redis, reducing the effective revocation window to near-instant at the cost of a Redis lookup on the hot request path.

### 14.6 Race Conditions

**Threat:** A role is revoked simultaneously with an authorized operation reaching a domain module — the operation proceeds with stale authorization data.

**Mitigation:** Domain modules that execute long-running or high-impact operations (e.g., initiating a repository sync, triggering an organization-wide AI review) perform a **re-validation** of the principal's active permissions at the point of execution, not just at the API entry point. This closes the window between the initial authorization check and the actual resource mutation.

### 14.7 Future ABAC Compatibility

The current RBAC model is designed to be **forward-compatible with Attribute-Based Access Control (ABAC)**. The Permission Evaluator's interface accepts a context object that today carries only `(userId, orgId, roles)` but is structured to accept arbitrary attribute bags in the future (e.g., resource tags, time-of-day constraints, IP geolocation). Migrating to ABAC does not require restructuring the authorization call sites — only the internal evaluation engine changes.

---

## 15. Future Evolution

### 15.1 Attribute-Based Access Control (ABAC)

ABAC extends RBAC by evaluating permissions against arbitrary attributes of the subject (user), resource, action, and environment. In DevFlow's future ABAC model:
- A user could be permitted to view analytics **only during business hours** (environment attribute).
- A repository scan could be permitted **only if the repository is tagged as `internal`** (resource attribute).
- An AI review could be blocked **if the user's device is not on a corporate network** (subject attribute).

The Permission Evaluator interface is designed to accept this extensibility without breaking existing call sites.

### 15.2 Policy-Based Access Control (PBAC)

PBAC abstracts authorization rules into declarative policy documents that can be managed by administrators without code changes. In a future PBAC model:
- Organization admins could define custom policies such as: _"Members of the 'QA' team can approve AI reviews on repositories tagged 'staging'."_
- Policies are evaluated by a centralized policy engine, separate from the domain modules.
- Policy changes take effect without a deployment cycle.

### 15.3 Open Policy Agent (OPA) Integration

OPA is a popular open-source, general-purpose policy engine that evaluates Rego policy documents. DevFlow's Authorization Engine is architecturally positioned to delegate evaluation to an OPA sidecar:
- Domain modules call the Authorization Engine's `AuthApi` interface — they are unaware of the underlying evaluation mechanism.
- The `AuthApi` implementation can be swapped from an in-process evaluator to an OPA HTTP client without any module-level changes.
- OPA policies can be stored, versioned, and deployed independently of the main application code.

### 15.4 Enterprise Policy Engines

Enterprise customers using commercial policy management platforms (e.g., PlainID, Axiomatics, AWS Verified Permissions) can be integrated at the Authorization Engine boundary. The `AuthApi` interface becomes an adapter that forwards authorization queries to the external engine.

### 15.5 Fine-Grained Permissions

Future fine-grained permissions will allow authorization at the **field level** and **record level**, not only at the resource type level:
- A user could be permitted to view a project's task list but not its analytics breakdown.
- A viewer could be permitted to see task titles but not task descriptions containing confidential business requirements.

### 15.6 Dynamic Policies

Dynamic policies evaluate authorization in real-time based on changing conditions:
- Automatic permission reduction during security incidents (e.g., temporarily restricting all `ai.review` operations organization-wide).
- Time-bounded elevated access for on-call engineers.
- Automatic permission scaling based on organizational subscription tier changes.

---

## 16. Architectural Principles & Key Design Decisions

| # | Principle | Rationale |
| :--- | :--- | :--- |
| **1** | **Authorization is always the responsibility of the `devflow-auth` module.** No domain module (Project Management, AI Engine, etc.) implements its own authorization logic. All checks are delegated to the `AuthApi`. | Prevents authorization logic from fragmenting across modules, where individual implementations might differ in security guarantees, miss checks, or be updated inconsistently. |
| **2** | **Default Deny is unconditional.** The absence of an explicit grant is always a denial. There are no implicit permissions. | Eliminates the possibility of unintended access through misconfiguration or missing permission definitions on new resources. |
| **3** | **The tenant boundary is the first and hardest check.** A `orgId` mismatch returns `404 Not Found`, not `403 Forbidden`. | Prevents cross-tenant resource ID enumeration — an attacker cannot confirm that a resource exists in another tenant by probing IDs. |
| **4** | **Role hierarchy simplifies administration without sacrificing clarity.** Higher roles inherit all lower-role permissions within their scope. | Reduces the administrative burden of role management for common cases while preserving the ability to grant fine-grained access through explicit permission combinations. |
| **5** | **Ownership is a first-class authorization concept, not a workaround.** Resource creators hold ownership rights that are modeled and evaluated explicitly, not inferred from role assignments. | Aligns the authorization model with natural human expectations (creators control their own work) without requiring administrators to manually assign per-resource access for every created resource. |
| **6** | **Permission evaluation is composable.** Complex operations require the `AND` of multiple permissions, each of which is evaluated independently. | Prevents permission shortcuts where holding one broad permission implicitly unlocks unrelated capabilities. |
| **7** | **Authorization decisions are always evaluated against the live Security Context, never against cached external state that the caller provides.** | Eliminates a class of confused-deputy and privilege-laundering attacks where a module provides a manipulated authorization context. |
| **8** | **All authorization events are audited.** Both `ALLOW` and `DENY` outcomes for sensitive operations are logged with full decision context. | Provides the evidence trail necessary for security incident investigation, compliance reporting, and anomaly detection. |
| **9** | **The Authorization Engine interface is protocol-agnostic.** The `AuthApi` contract is defined by its inputs and outputs, not by its internal evaluation mechanism. | Enables the internal evaluator to be upgraded from RBAC to ABAC to OPA without requiring any changes in the domain modules that consume authorization decisions. |
| **10** | **Authorization and Authentication are strictly separated.** The Security Filter Chain handles authentication. Domain module boundaries handle authorization. They share only the Security Context object. | Enforces a clean separation that allows each concern to evolve independently. Future authentication changes (e.g., adding WebAuthn) do not require authorization model changes, and vice versa. |

---

*This document is the official authorization architecture specification for DevFlow. Changes to the authorization model require review and approval from the Architecture Review Board (ARB) and the Security Review function.*
