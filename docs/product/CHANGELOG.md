# Changelog

All notable changes to DevFlow-AI are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Stage 2 — Permissions Implementation 2.2] — Durable Project Ownership Registry

### Added
- **Flyway Database Migration (`V17__create_project_registrations_table.sql`)**:
  - Introduced `project_registrations` relational table anchoring durable project ownership in PostgreSQL.
  - Schema defines `project_id UUID PRIMARY KEY`, `organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE`, `registered_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL`, `registered_at TIMESTAMPTZ NOT NULL`, `created_at TIMESTAMPTZ NOT NULL`, `updated_at TIMESTAMPTZ NOT NULL`, and `version BIGINT NOT NULL DEFAULT 0`.
  - Database primary key constraint strictly enforces globally unique project registration, preventing cross-tenant collision or multi-tenant binding at the storage layer.
- **Durable JPA Entity & Repository (`ProjectRegistration`, `ProjectRegistrationRepository`)**:
  - `ProjectRegistration`: JPA entity mapped to `project_registrations` with `@EntityListeners(AuditingEntityListener.class)` and `@Version` optimistic locking.
  - `ProjectRegistrationRepository`: Spring Data JPA repository providing indexed queries (`findByOrganizationId`, `findByProjectId`) and atomic deletion (`deleteByProjectId`).
- **Relational Project Registry Implementation (`DatabaseProjectRegistry`)**:
  - Created persistent, `@Primary` `@Component` implementation of `ProjectRegistry` replacing in-memory storage.
  - Executes registration inside transactions, catches `DataIntegrityViolationException` on constraint violations, and translates storage-level conflicts into domain `InvalidProjectRegistrationException`.
  - Provides atomic, multi-instance consistency across separate JVM instances and surviving application restarts.
- **Dedicated Durability Integration Test Suite (`DurableProjectRegistryIntegrationTest`)**:
  - Property 1: Ownership survives registry instance and service recreation.
  - Property 2: Multiple registry instances observe consistent ownership via shared database state.
  - Property 3: Cross-organization registration conflicts are rejected at application and database level.
  - Property 4: Concurrent registrations across separate service instances cannot create conflicting ownership (ForkJoinPool asynchronous race condition test).
  - Property 5: Database rollback does not leave stale project registrations.
  - Property 6: Unregistered project authorization is denied (Fail-closed default deny).
  - Property 7: Existing registered project role assignments continue working seamlessly.
  - Property 8: Arbitrary project UUIDs cannot be registered through role assignment alone.
  - Property 9: Missing ownership data defaults to deny.

### Changed
- **Retired In-Memory Registry (`ConcurrentProjectRegistry`)**:
  - Deleted volatile `ConcurrentProjectRegistry` to eliminate any reliance on ephemeral in-memory state for authoritative security boundaries.
- **Domain Port Evolution (`ProjectRegistry`)**:
  - Added actor attribution support `registerProject(UUID organizationId, UUID projectId, UUID registeredByUserId)` with backwards-compatible default overload.
- **Fail-Closed Project Role Operations (`RoleAssignmentService`)**:
  - Wires `projectRegistry.registerProject(organizationId, projectId, assignedByUserId)` during `bindInitialProjectRole()`.
  - Enforces three-tier fail-closed validation in `assignProjectRole()`: checks `tenantBoundaryValidator` if present, falls back to `projectRegistry.isProjectRegistered()`, and finally validates non-empty matching assignments in `projectAssignmentRepository`.

## [Stage 2 — Permissions Implementation 2.1] — Initial Project Binding Security Review

### Fixed
- **Eliminated Tenant Boundary Bypass in Project Role Assignment (`RoleAssignmentService`)**:
  - Completely removed concrete implementation checks (`!(tenantBoundaryValidator instanceof DatabaseTenantBoundaryValidator)`) that previously allowed bypassing `isProjectInOrganization()` for projects with no existing assignments.
  - Enforced unconditional `tenantBoundaryValidator.isProjectInOrganization(organizationId, projectId)` evaluation in `assignProjectRole()`. Arbitrary unregistered project UUIDs without authoritative ownership strictly fail closed with `InvalidRoleAssignmentException`.
- **Authoritative Initial Project Binding Flow (`RoleAssignmentService.bindInitialProjectRole`)**:
  - Explicitly separated initial project registration and initial binding from ordinary project-role assignment.
  - Validates organization context, organization owner/admin caller authority, existing assignment tenant consistency, and authoritatively registers the project in `ProjectRegistry` before persisting the initial assignment.

### Added
- **Authoritative Project Registry SPI & Concurrent In-Memory Implementation**:
  - `ProjectRegistry`: Domain port interface in `com.devflow.role.registry` establishing authoritative project-to-organization ownership boundaries without requiring speculative database tables or migrations.
  - `ConcurrentProjectRegistry`: Thread-safe Spring `@Component` implementation utilizing atomic `putIfAbsent` on `ConcurrentHashMap` to guarantee that concurrent initial bindings cannot associate the same project with multiple organizations.
  - `InvalidProjectRegistrationException`: Domain exception extending `InvalidRoleAssignmentException` thrown when an attempted project registration violates tenant isolation invariants (e.g. cross-organization registration conflicts).
- **Hardened Database Tenant Boundary Validator (`DatabaseTenantBoundaryValidator`)**:
  - Integrated `ProjectRegistry` into `isProjectInOrganization()`: registered projects are recognized as valid for their owning tenant prior to initial role assignment persistence, while unregistered projects with no role assignments strictly fail closed (`return false`).
  - Cross-tenant registered projects fail closed immediately without querying assignment repositories.
  - Hardened `getResourceOrganizationId("project", entityId)` to resolve owning organization from `ProjectRegistry` while verifying assignment consistency.
- **Dedicated Security Test Suite (7 Implementation 2.1 Scenarios in `ScopedRoleAssignmentDatabaseIntegrationTest`)**:
  - Test 1: Arbitrary unregistered project UUID cannot be bound without authoritative ownership verification (fails closed with `InvalidRoleAssignmentException` and evaluator denies with `TENANT_MISMATCH`).
  - Test 2: Valid registered project can be authoritatively bound to its actual organization via `bindInitialProjectRole`.
  - Test 3: Project cannot be registered or bound to two organizations (concurrent race simulation verifies atomic single-winner binding with `InvalidProjectRegistrationException`).
  - Test 4: Cross-organization initial binding is rejected.
  - Test 5: Existing project assignments cannot be moved across tenants.
  - Test 6: Ordinary authorized project-role assignment works for correctly registered projects.
  - Test 7: Unknown and incomplete project ownership data defaults to deny.
- **Unit Test Coverage (`DatabaseTenantBoundaryValidatorTest`)**:
  - Added unit tests covering project registered in registry with zero assignments, cross-tenant registered rejection, and registry-backed resource organization resolution.

## [Stage 2 — Permissions Implementation 2] — Project Tenant-Boundary Fail-Closed Correction

### Fixed
- **Project Tenant-Boundary Fail-Closed Correction (`DatabaseTenantBoundaryValidator`)**:
  - Corrected `isProjectInOrganization()` so that an unresolvable project-to-organization relationship strictly fails closed (`return false`). Missing role assignments are never interpreted as belonging to the organization or authorizing access.
  - Enforced organization existence check (`organizationRepository.existsById(organizationId)`) failing closed on non-existent organizations.
  - Corrected `getResourceOrganizationId("project", resourceId)` to fail closed (`Optional.empty()`) when a project has no role assignments or inconsistent organization references.
- **Fail-Closed Project Role Operations (`RoleAssignmentService`)**:
  - Hardened `assignProjectRole()` to verify that existing projects belong to the target organization before role assignment. For new projects without assignments, initial binding is permitted for authorized organization owners and admins, while external/custom tenant boundary validators are strictly respected.
  - Hardened `revokeProjectRole()`, `revokeProjectRoleAssignment()`, and `listProjectRoleAssignments()` to evaluate tenant boundaries first, failing closed with `InvalidRoleAssignmentException` on unassigned or foreign projects.
  - Resolved partial unique index (`uk_project_role_assignments_active`) conflicts by flushing superseded expired assignments prior to persisting new assignments.

### Added
- **Unit Test Suite (`DatabaseTenantBoundaryValidatorTest`)**:
  - 15 dedicated unit tests validating null-safe guards, non-existent organization handling, zero-assignment fail-closed rejection, cross-tenant rejection, inconsistent assignment detection, and resource organization resolution.
- **Security & Database Integration Tests (`ScopedRoleAssignmentDatabaseIntegrationTest`)**:
  - 10 required security test scenarios covering valid project-org ownership, fail-closed unassigned projects, non-resolvable organizations, cross-tenant denial, insufficient permission denial, expired/revoked assignment denial, project lead/owner access, legacy role fallback tenant isolation, and default-deny for unknown requests.
- **REST Controller Integration Tests (`ProjectRoleControllerIntegrationTest`)**:
  - Verifies that listing, granting, or revoking project roles on unassigned or cross-tenant projects fails closed with HTTP 400 Bad Request.

## [Stage 2 — Permissions Implementation 1] — Founding Owner Role Synchronization

### Added
- **Founding Owner Role Assignment Synchronization**:
  - `OrganizationService.createOrganization()` atomically persists both `OrganizationMember` (`role = OWNER`) and `OrganizationRoleAssignment` (`role = ORGANIZATION_OWNER`, `assignedBy = owner`, `roleScope = ORGANIZATION`, `assignedAt = now`).
  - Added duplicate check `hasActiveAssignment` preventing redundant assignments.
  - Provided backward-compatible 5-argument constructor and `@Autowired` 8-argument constructor for seamless dependency injection.
- **Tenant Deletion Cleanup**:
  - Added derived delete `OrganizationRoleAssignmentRepository.deleteByOrganizationId(UUID organizationId)` to clean up scoped role assignments during organization deletion, respecting foreign key restrictions.
  - Added `deleteByOrganizationIdAndUserId(UUID organizationId, UUID userId)` for clean user removal.
- **Membership and Role Assignment Bi-directional Synchronization**:
  - `OrganizationMembershipService.addMember()` automatically synchronizes canonical role assignment to `OrganizationRoleAssignment`.
  - `OrganizationMembershipService.changeMemberRole()` revokes the active previous canonical role assignment (`ROLE_CHANGED`) and grants the new canonical role assignment.
  - `OrganizationMembershipService.removeMember()` cleans up role assignments for the removed member.
  - `RoleAssignmentService.assignOrganizationRole()` updates `OrganizationMember.role` when granting `ORGANIZATION_OWNER` or `ORGANIZATION_ADMIN`.
  - `RoleAssignmentService.revokeOrganizationRole()` demotes `OrganizationMember.role` when revoking `ORGANIZATION_OWNER` or `ORGANIZATION_ADMIN`.
- **Sole Active Owner Protection & Expired Assignment Hardening**:
  - Unified `countActiveOwners` across `OrganizationMembershipService` and `RoleAssignmentService` that excludes expired assignments (`expiresAt <= now`) and revoked assignments (`revokedAt != null`), preventing organizations from losing their last active owner.
  - Demotion and removal guardrails verify whether the target user is currently recognized as an active owner before evaluating the active owner threshold.
  - Concurrent revocations and removals remain serialized by pessimistic row locking (`organizationRepository.findByIdForUpdate(organizationId)`).

## [Phase 2.5] — Workspace Management & Organization Test Wrap-Up

### Added
- **Workspace Management Module**:
  - Implemented `Workspace` aggregate root and `V11__create_workspaces_table.sql` database migration.
  - Implemented `WorkspaceRepository` with tenant-scoped query methods and case-insensitive unique naming within organizations.
  - Implemented `WorkspaceService` managing workspace lifecycle, public/private visibility filtering, and multi-tenant RBAC.
  - Implemented `WorkspaceController` exposing REST endpoints (`POST`, `GET`, `PATCH`, `DELETE`) under `/api/v1/organizations/{organizationId}/workspaces`.
  - Comprehensive workspace test suites: `WorkspaceServiceTest`, `WorkspaceControllerTest`, and `WorkspaceIntegrationTest`.
- **Organization Management Automated Test Suite**:
  - `OrganizationAuthorizationServiceTest`: Unit coverage for RBAC decision trees, active status checks (`ACTIVE` vs `INVITED`/`SUSPENDED`), and defensive fail-closed null handling.
  - `OrganizationServiceTest`: Unit coverage for tenant creation, slug trimming/normalization, slug uniqueness enforcement, atomic founding owner assignment, and slug immutability on update.
  - `OrganizationMembershipServiceTest`: Unit coverage for member additions, removals, role promotions/demotions, and enforcement of the last active owner preservation invariant (`activeOwnerCount <= 1`).
  - `OrganizationInvitationServiceTest`: Unit coverage for invitation generation (secure tokens, 7-day expiry), status transitions (`PENDING`, `ACCEPTED`, `REVOKED`, `EXPIRED`), batch expiry, and recipient email matching on acceptance.
  - `OrganizationControllerTest`: `@WebMvcTest` slice coverage verifying request validation (`@Valid`), error translation via `GlobalExceptionHandler` (400, 403, 404, 409), and DTO response mapping across all 5 REST endpoints.
  - `OrganizationIntegrationTest`: End-to-end integration tests against live PostgreSQL verifying JPA auditing, database constraints (`uk_organizations_slug`), tenant isolation query scoping (`findByIdAndOrganizationId`), and transactional rollback safety.

### Fixed
- **Organization Deletion Dependent Record Cleanup**:
  - Resolved `TransientObjectException` and foreign-key violation (`fk_organization_members_organization ON DELETE RESTRICT`) in `OrganizationService.deleteOrganization()` by atomically deleting dependent invitation records and membership records within the tenant boundary prior to deleting the organization entity.
- **Test Infrastructure Stability**:
  - Resolved Flyway V10 H2 syntax incompatibility by aligning `application-test.yml` fallback to local PostgreSQL (`devflow_test`) with `spring.test.database.replace: none`.
  - Added missing mock controller beans to `SecurityConfigurationTest`.
  - Aligned `WorkspaceIntegrationTest` to use `@ActiveProfiles("test")`.
