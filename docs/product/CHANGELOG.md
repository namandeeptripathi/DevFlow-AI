# Changelog

All notable changes to DevFlow-AI are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

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
