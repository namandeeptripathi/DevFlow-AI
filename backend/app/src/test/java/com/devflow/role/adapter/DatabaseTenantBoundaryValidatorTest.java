package com.devflow.role.adapter;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.domain.ProjectRoleAssignment;
import com.devflow.role.registry.ProjectRegistry;
import com.devflow.role.repository.ProjectRoleAssignmentRepository;
import com.devflow.team.domain.Team;
import com.devflow.team.repository.TeamRepository;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit test suite for {@link DatabaseTenantBoundaryValidator}.
 *
 * <p>Verifies fail-closed tenant isolation guarantees:
 * <ul>
 *   <li>Missing role assignments strictly fail closed (never default-allow).</li>
 *   <li>Non-existent organizations fail closed.</li>
 *   <li>Cross-tenant project associations fail closed.</li>
 *   <li>Resource tenant boundary resolution fails closed for unknown/unbound entities.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DatabaseTenantBoundaryValidator Unit Tests")
class DatabaseTenantBoundaryValidatorTest {

    @Mock
    private ProjectRoleAssignmentRepository projectRoleAssignmentRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private ProjectRegistry projectRegistry;

    @InjectMocks
    private DatabaseTenantBoundaryValidator validator;

    private UUID orgId;
    private UUID otherOrgId;
    private UUID projId;
    private Organization org;
    private Organization otherOrg;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        otherOrgId = UUID.randomUUID();
        projId = UUID.randomUUID();

        org = Organization.builder()
                .name("Acme Corp")
                .slug("acme")
                .build();
        org.setId(orgId);

        otherOrg = Organization.builder()
                .name("Beta Corp")
                .slug("beta")
                .build();
        otherOrg.setId(otherOrgId);
    }

    // ── 1. isProjectInOrganization ───────────────────────────────────────────

    @Nested
    @DisplayName("isProjectInOrganization")
    class IsProjectInOrganizationTests {

        @Test
        @DisplayName("Returns false when organizationId or projectId is null (Null guards fail closed)")
        void nullGuards_failClosed() {
            assertThat(validator.isProjectInOrganization(null, projId)).isFalse();
            assertThat(validator.isProjectInOrganization(orgId, null)).isFalse();
            assertThat(validator.isProjectInOrganization(null, null)).isFalse();
            verifyNoInteractions(organizationRepository, projectRoleAssignmentRepository);
        }

        @Test
        @DisplayName("Returns false when organization does not exist in repository (Non-existent org fails closed)")
        void nonExistentOrg_failsClosed() {
            when(organizationRepository.existsById(orgId)).thenReturn(false);

            assertThat(validator.isProjectInOrganization(orgId, projId)).isFalse();
            verify(organizationRepository).existsById(orgId);
            verifyNoInteractions(projectRoleAssignmentRepository);
        }

        @Test
        @DisplayName("Returns false when project has NO role assignments (Missing assignments fail closed)")
        void projectWithNoAssignments_failsClosed() {
            when(organizationRepository.existsById(orgId)).thenReturn(true);
            when(projectRoleAssignmentRepository.findByProjectId(projId)).thenReturn(Collections.emptyList());

            boolean result = validator.isProjectInOrganization(orgId, projId);

            assertThat(result).isFalse();
            verify(organizationRepository).existsById(orgId);
            verify(projectRoleAssignmentRepository).findByProjectId(projId);
        }

        @Test
        @DisplayName("Returns true when project has role assignments belonging to the organization")
        void projectBelongingToOrganization_returnsTrue() {
            when(organizationRepository.existsById(orgId)).thenReturn(true);

            ProjectRoleAssignment assignment1 = ProjectRoleAssignment.builder()
                    .projectId(projId)
                    .organization(org)
                    .build();
            ProjectRoleAssignment assignment2 = ProjectRoleAssignment.builder()
                    .projectId(projId)
                    .organization(org)
                    .build();

            when(projectRoleAssignmentRepository.findByProjectId(projId))
                    .thenReturn(List.of(assignment1, assignment2));

            boolean result = validator.isProjectInOrganization(orgId, projId);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Returns false when project belongs to a different organization (Cross-tenant rejection)")
        void projectBelongingToDifferentOrg_failsClosed() {
            when(organizationRepository.existsById(orgId)).thenReturn(true);

            ProjectRoleAssignment otherOrgAssignment = ProjectRoleAssignment.builder()
                    .projectId(projId)
                    .organization(otherOrg)
                    .build();

            when(projectRoleAssignmentRepository.findByProjectId(projId))
                    .thenReturn(List.of(otherOrgAssignment));

            boolean result = validator.isProjectInOrganization(orgId, projId);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Returns true when project is registered in ProjectRegistry even with NO assignments")
        void projectRegisteredInRegistry_withNoAssignments_returnsTrue() {
            when(organizationRepository.existsById(orgId)).thenReturn(true);
            when(projectRegistry.getProjectOrganizationId(projId)).thenReturn(Optional.of(orgId));
            when(projectRoleAssignmentRepository.findByProjectId(projId)).thenReturn(Collections.emptyList());
            when(projectRegistry.isProjectRegistered(orgId, projId)).thenReturn(true);

            boolean result = validator.isProjectInOrganization(orgId, projId);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Returns false when project is registered to another organization in ProjectRegistry (Cross-tenant registered)")
        void projectRegisteredToDifferentOrgInRegistry_failsClosed() {
            when(organizationRepository.existsById(orgId)).thenReturn(true);
            when(projectRegistry.getProjectOrganizationId(projId)).thenReturn(Optional.of(otherOrgId));

            boolean result = validator.isProjectInOrganization(orgId, projId);

            assertThat(result).isFalse();
            verifyNoInteractions(projectRoleAssignmentRepository);
        }

        @Test
        @DisplayName("Returns false when project assignments contain mixed or null organizations (Inconsistent data fails closed)")
        void mixedOrNullOrganizationAssignments_failClosed() {
            when(organizationRepository.existsById(orgId)).thenReturn(true);

            ProjectRoleAssignment validAssignment = ProjectRoleAssignment.builder()
                    .projectId(projId)
                    .organization(org)
                    .build();
            ProjectRoleAssignment nullOrgAssignment = ProjectRoleAssignment.builder()
                    .projectId(projId)
                    .organization(null)
                    .build();

            when(projectRoleAssignmentRepository.findByProjectId(projId))
                    .thenReturn(List.of(validAssignment, nullOrgAssignment));

            assertThat(validator.isProjectInOrganization(orgId, projId)).isFalse();
        }
    }

    // ── 2. getResourceOrganizationId ─────────────────────────────────────────

    @Nested
    @DisplayName("getResourceOrganizationId")
    class GetResourceOrganizationIdTests {

        @Test
        @DisplayName("Returns empty when resourceType or resourceId is null or blank")
        void nullOrBlankResource_returnsEmpty() {
            assertThat(validator.getResourceOrganizationId(null, projId.toString())).isEmpty();
            assertThat(validator.getResourceOrganizationId("", projId.toString())).isEmpty();
            assertThat(validator.getResourceOrganizationId("   ", projId.toString())).isEmpty();
            assertThat(validator.getResourceOrganizationId("project", null)).isEmpty();
            assertThat(validator.getResourceOrganizationId("project", "")).isEmpty();
            assertThat(validator.getResourceOrganizationId("project", "   ")).isEmpty();
        }

        @Test
        @DisplayName("Returns empty when resourceId is not a valid UUID")
        void invalidUUID_returnsEmpty() {
            assertThat(validator.getResourceOrganizationId("project", "not-a-valid-uuid")).isEmpty();
            assertThat(validator.getResourceOrganizationId("organization", "12345")).isEmpty();
        }

        @Test
        @DisplayName("Resolves organization resource when organization exists")
        void organizationResource_resolvesCorrectly() {
            when(organizationRepository.existsById(orgId)).thenReturn(true);
            assertThat(validator.getResourceOrganizationId("organization", orgId.toString()))
                    .contains(orgId);
            assertThat(validator.getResourceOrganizationId("org", orgId.toString()))
                    .contains(orgId);

            when(organizationRepository.existsById(otherOrgId)).thenReturn(false);
            assertThat(validator.getResourceOrganizationId("organization", otherOrgId.toString()))
                    .isEmpty();
        }

        @Test
        @DisplayName("Resolves workspace resource to enclosing organization ID")
        void workspaceResource_resolvesCorrectly() {
            UUID workspaceId = UUID.randomUUID();
            Workspace workspace = Workspace.builder()
                    .name("Engineering")
                    .organization(org)
                    .build();

            when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
            assertThat(validator.getResourceOrganizationId("workspace", workspaceId.toString()))
                    .contains(orgId);

            UUID missingId = UUID.randomUUID();
            when(workspaceRepository.findById(missingId)).thenReturn(Optional.empty());
            assertThat(validator.getResourceOrganizationId("workspace", missingId.toString()))
                    .isEmpty();
        }

        @Test
        @DisplayName("Resolves team resource through workspace to enclosing organization ID")
        void teamResource_resolvesCorrectly() {
            UUID teamId = UUID.randomUUID();
            Workspace workspace = Workspace.builder()
                    .name("Engineering")
                    .organization(org)
                    .build();
            Team team = Team.builder()
                    .name("Backend")
                    .workspace(workspace)
                    .build();

            when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
            assertThat(validator.getResourceOrganizationId("team", teamId.toString()))
                    .contains(orgId);

            UUID missingId = UUID.randomUUID();
            when(teamRepository.findById(missingId)).thenReturn(Optional.empty());
            assertThat(validator.getResourceOrganizationId("team", missingId.toString()))
                    .isEmpty();
        }

        @Test
        @DisplayName("Project resource with NO assignments fails closed (returns empty)")
        void projectResourceWithNoAssignments_failsClosed() {
            when(projectRoleAssignmentRepository.findByProjectId(projId))
                    .thenReturn(Collections.emptyList());

            assertThat(validator.getResourceOrganizationId("project", projId.toString()))
                    .isEmpty();
        }

        @Test
        @DisplayName("Project resource registered in ProjectRegistry resolves to owning organization ID even without assignments")
        void projectResourceRegisteredInRegistry_resolvesCorrectly() {
            when(projectRegistry.getProjectOrganizationId(projId)).thenReturn(Optional.of(orgId));
            when(projectRoleAssignmentRepository.findByProjectId(projId)).thenReturn(Collections.emptyList());

            assertThat(validator.getResourceOrganizationId("project", projId.toString()))
                    .contains(orgId);
        }

        @Test
        @DisplayName("Project resource with assignments resolves to owning organization ID")
        void projectResourceWithAssignments_resolvesCorrectly() {
            ProjectRoleAssignment assignment = ProjectRoleAssignment.builder()
                    .projectId(projId)
                    .organization(org)
                    .build();

            when(projectRoleAssignmentRepository.findByProjectId(projId))
                    .thenReturn(List.of(assignment));

            assertThat(validator.getResourceOrganizationId("project", projId.toString()))
                    .contains(orgId);
        }

        @Test
        @DisplayName("Project resource with inconsistent assignments fails closed (returns empty)")
        void projectResourceWithInconsistentAssignments_failsClosed() {
            ProjectRoleAssignment assignment1 = ProjectRoleAssignment.builder()
                    .projectId(projId)
                    .organization(org)
                    .build();
            ProjectRoleAssignment assignment2 = ProjectRoleAssignment.builder()
                    .projectId(projId)
                    .organization(otherOrg)
                    .build();

            when(projectRoleAssignmentRepository.findByProjectId(projId))
                    .thenReturn(List.of(assignment1, assignment2));

            assertThat(validator.getResourceOrganizationId("project", projId.toString()))
                    .isEmpty();
        }

        @Test
        @DisplayName("Unknown resource type fails closed (returns empty)")
        void unknownResourceType_returnsEmpty() {
            assertThat(validator.getResourceOrganizationId("unknown_type", projId.toString()))
                    .isEmpty();
            assertThat(validator.getResourceOrganizationId("repository", projId.toString()))
                    .isEmpty();
        }
    }
}
