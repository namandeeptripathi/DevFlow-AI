package com.devflow.workspace.service;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationMembershipRequiredException;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.organization.service.OrganizationAuthorizationService;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import com.devflow.workspace.dto.CreateWorkspaceRequest;
import com.devflow.workspace.dto.UpdateWorkspaceRequest;
import com.devflow.workspace.dto.WorkspaceResponse;
import com.devflow.workspace.exception.WorkspaceAccessDeniedException;
import com.devflow.workspace.exception.WorkspaceAlreadyExistsException;
import com.devflow.workspace.exception.WorkspaceNotFoundException;
import com.devflow.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceService")
class WorkspaceServiceTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationAuthorizationService authorizationService;

    private WorkspaceService workspaceService;

    private UUID organizationId;
    private UUID callerUserId;
    private UUID workspaceId;
    private Organization testOrg;
    private Workspace testWorkspace;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(
                workspaceRepository,
                organizationRepository,
                authorizationService
        );

        organizationId = UUID.randomUUID();
        callerUserId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();

        testOrg = Organization.builder()
                .id(organizationId)
                .name("Acme Corp")
                .slug("acme-corp")
                .build();

        testWorkspace = Workspace.builder()
                .id(workspaceId)
                .name("Engineering")
                .organization(testOrg)
                .visibility(WorkspaceVisibility.PUBLIC)
                .description("Engineering workspace")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .version(0L)
                .build();
    }

    // ── CREATE WORKSPACE ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Create Workspace")
    class CreateWorkspace {

        @Test
        @DisplayName("authorized ADMIN can create workspace successfully")
        void createWorkspace_asAdmin_createsAndReturnsResponse() {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("Engineering")
                    .visibility(WorkspaceVisibility.PUBLIC)
                    .description("Core engineering team")
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.existsByOrganizationIdAndNameIgnoreCase(organizationId, "Engineering"))
                    .thenReturn(false);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));
            when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
                Workspace toSave = invocation.getArgument(0);
                toSave.setId(workspaceId);
                toSave.setCreatedAt(Instant.now());
                toSave.setUpdatedAt(Instant.now());
                toSave.setVersion(0L);
                return toSave;
            });

            WorkspaceResponse response = workspaceService.createWorkspace(organizationId, request, callerUserId);

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo(workspaceId);
            assertThat(response.getName()).isEqualTo("Engineering");
            assertThat(response.getOrganizationId()).isEqualTo(organizationId);
            assertThat(response.getVisibility()).isEqualTo(WorkspaceVisibility.PUBLIC);
            assertThat(response.getDescription()).isEqualTo("Core engineering team");

            verify(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            verify(workspaceRepository).save(any(Workspace.class));
        }

        @Test
        @DisplayName("authorized OWNER can create workspace successfully")
        void createWorkspace_asOwner_createsAndReturnsResponse() {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("Design")
                    .visibility(WorkspaceVisibility.PRIVATE)
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.existsByOrganizationIdAndNameIgnoreCase(organizationId, "Design"))
                    .thenReturn(false);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));
            when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
                Workspace toSave = invocation.getArgument(0);
                toSave.setId(UUID.randomUUID());
                return toSave;
            });

            WorkspaceResponse response = workspaceService.createWorkspace(organizationId, request, callerUserId);

            assertThat(response).isNotNull();
            assertThat(response.getName()).isEqualTo("Design");
            assertThat(response.getVisibility()).isEqualTo(WorkspaceVisibility.PRIVATE);
            verify(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
        }

        @Test
        @DisplayName("unauthorized member cannot create workspace")
        void createWorkspace_whenNotAdminOrOwner_throwsOrganizationAccessDeniedException() {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("Engineering")
                    .build();

            doThrow(new OrganizationAccessDeniedException("Access denied"))
                    .when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);

            assertThatThrownBy(() -> workspaceService.createWorkspace(organizationId, request, callerUserId))
                    .isInstanceOf(OrganizationAccessDeniedException.class)
                    .hasMessageContaining("Access denied");

            verify(workspaceRepository, never()).save(any());
        }

        @Test
        @DisplayName("fails when organization does not exist")
        void createWorkspace_whenOrganizationNotFound_throwsOrganizationNotFoundException() {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("Engineering")
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.existsByOrganizationIdAndNameIgnoreCase(organizationId, "Engineering"))
                    .thenReturn(false);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> workspaceService.createWorkspace(organizationId, request, callerUserId))
                    .isInstanceOf(OrganizationNotFoundException.class)
                    .hasMessageContaining("Organization not found");

            verify(workspaceRepository, never()).save(any());
        }

        @Test
        @DisplayName("name is trimmed before checking uniqueness and saving")
        void createWorkspace_trimsNameBeforeSaving() {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("   Engineering   ")
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.existsByOrganizationIdAndNameIgnoreCase(organizationId, "Engineering"))
                    .thenReturn(false);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));
            when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));

            workspaceService.createWorkspace(organizationId, request, callerUserId);

            ArgumentCaptor<Workspace> captor = ArgumentCaptor.forClass(Workspace.class);
            verify(workspaceRepository).save(captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("Engineering");
            verify(workspaceRepository).existsByOrganizationIdAndNameIgnoreCase(organizationId, "Engineering");
        }

        @Test
        @DisplayName("blank name throws IllegalArgumentException")
        void createWorkspace_whenBlankName_throwsIllegalArgumentException() {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("   ")
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);

            assertThatThrownBy(() -> workspaceService.createWorkspace(organizationId, request, callerUserId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Workspace name must not be blank");

            verify(workspaceRepository, never()).save(any());
        }

        @Test
        @DisplayName("duplicate name is rejected case-insensitively")
        void createWorkspace_whenDuplicateNameIgnoreCase_throwsWorkspaceAlreadyExistsException() {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("engineering")
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.existsByOrganizationIdAndNameIgnoreCase(organizationId, "engineering"))
                    .thenReturn(true);

            assertThatThrownBy(() -> workspaceService.createWorkspace(organizationId, request, callerUserId))
                    .isInstanceOf(WorkspaceAlreadyExistsException.class)
                    .hasMessageContaining("Workspace already exists with name: engineering");

            verify(workspaceRepository, never()).save(any());
        }

        @Test
        @DisplayName("null visibility explicitly defaults to PUBLIC")
        void createWorkspace_whenNullVisibility_defaultsToPublic() {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("Engineering")
                    .visibility(null)
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.existsByOrganizationIdAndNameIgnoreCase(organizationId, "Engineering"))
                    .thenReturn(false);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));
            when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));

            workspaceService.createWorkspace(organizationId, request, callerUserId);

            ArgumentCaptor<Workspace> captor = ArgumentCaptor.forClass(Workspace.class);
            verify(workspaceRepository).save(captor.capture());
            assertThat(captor.getValue().getVisibility()).isEqualTo(WorkspaceVisibility.PUBLIC);
        }

        @Test
        @DisplayName("description is trimmed and normalized")
        void createWorkspace_normalisesDescription() {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("Engineering")
                    .description("   Core engineering team   ")
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.existsByOrganizationIdAndNameIgnoreCase(organizationId, "Engineering"))
                    .thenReturn(false);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));
            when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));

            workspaceService.createWorkspace(organizationId, request, callerUserId);

            ArgumentCaptor<Workspace> captor = ArgumentCaptor.forClass(Workspace.class);
            verify(workspaceRepository).save(captor.capture());
            assertThat(captor.getValue().getDescription()).isEqualTo("Core engineering team");
        }
    }

    // ── LIST WORKSPACES ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("List Workspaces")
    class ListWorkspaces {

        @Test
        @DisplayName("ADMIN/OWNER can see both PUBLIC and PRIVATE workspaces")
        void getWorkspaces_asAdminOrOwner_returnsPublicAndPrivateWorkspaces() {
            Workspace privateWorkspace = Workspace.builder()
                    .id(UUID.randomUUID())
                    .name("Security")
                    .organization(testOrg)
                    .visibility(WorkspaceVisibility.PRIVATE)
                    .build();

            doNothing().when(authorizationService).requireMember(organizationId, callerUserId);
            when(authorizationService.isAdminOrOwner(organizationId, callerUserId)).thenReturn(true);
            when(workspaceRepository.findByOrganizationId(organizationId))
                    .thenReturn(List.of(testWorkspace, privateWorkspace));

            List<WorkspaceResponse> responses = workspaceService.getWorkspaces(organizationId, callerUserId);

            assertThat(responses).hasSize(2);
            assertThat(responses).extracting(WorkspaceResponse::getName)
                    .containsExactly("Engineering", "Security");
            verify(workspaceRepository).findByOrganizationId(organizationId);
            verify(workspaceRepository, never()).findByOrganizationIdAndVisibility(any(), any());
        }

        @Test
        @DisplayName("DEVELOPER/VIEWER can see PUBLIC workspaces only")
        void getWorkspaces_asDeveloperOrViewer_returnsPublicWorkspacesOnly() {
            doNothing().when(authorizationService).requireMember(organizationId, callerUserId);
            when(authorizationService.isAdminOrOwner(organizationId, callerUserId)).thenReturn(false);
            when(workspaceRepository.findByOrganizationIdAndVisibility(organizationId, WorkspaceVisibility.PUBLIC))
                    .thenReturn(List.of(testWorkspace));

            List<WorkspaceResponse> responses = workspaceService.getWorkspaces(organizationId, callerUserId);

            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getName()).isEqualTo("Engineering");
            assertThat(responses.get(0).getVisibility()).isEqualTo(WorkspaceVisibility.PUBLIC);
            verify(workspaceRepository).findByOrganizationIdAndVisibility(organizationId, WorkspaceVisibility.PUBLIC);
            verify(workspaceRepository, never()).findByOrganizationId(organizationId);
        }

        @Test
        @DisplayName("non-member cannot list workspaces")
        void getWorkspaces_whenNotMember_throwsOrganizationMembershipRequiredException() {
            doThrow(new OrganizationMembershipRequiredException("Membership required"))
                    .when(authorizationService).requireMember(organizationId, callerUserId);

            assertThatThrownBy(() -> workspaceService.getWorkspaces(organizationId, callerUserId))
                    .isInstanceOf(OrganizationMembershipRequiredException.class)
                    .hasMessageContaining("Membership required");

            verify(workspaceRepository, never()).findByOrganizationId(any());
            verify(workspaceRepository, never()).findByOrganizationIdAndVisibility(any(), any());
        }
    }

    // ── GET WORKSPACE BY ID ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Get Workspace by ID")
    class GetWorkspaceById {

        @Test
        @DisplayName("PUBLIC workspace is accessible to any active member")
        void getWorkspace_publicWorkspace_accessibleToAnyActiveMember() {
            doNothing().when(authorizationService).requireMember(organizationId, callerUserId);
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(testWorkspace));

            WorkspaceResponse response = workspaceService.getWorkspace(organizationId, workspaceId, callerUserId);

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo(workspaceId);
            assertThat(response.getName()).isEqualTo("Engineering");
            verify(authorizationService).requireMember(organizationId, callerUserId);
        }

        @Test
        @DisplayName("PRIVATE workspace is accessible to ADMIN/OWNER")
        void getWorkspace_privateWorkspace_accessibleToAdminOrOwner() {
            Workspace privateWorkspace = Workspace.builder()
                    .id(workspaceId)
                    .name("Executive")
                    .organization(testOrg)
                    .visibility(WorkspaceVisibility.PRIVATE)
                    .build();

            doNothing().when(authorizationService).requireMember(organizationId, callerUserId);
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(privateWorkspace));
            when(authorizationService.isAdminOrOwner(organizationId, callerUserId)).thenReturn(true);

            WorkspaceResponse response = workspaceService.getWorkspace(organizationId, workspaceId, callerUserId);

            assertThat(response).isNotNull();
            assertThat(response.getVisibility()).isEqualTo(WorkspaceVisibility.PRIVATE);
        }

        @Test
        @DisplayName("PRIVATE workspace is inaccessible to DEVELOPER/VIEWER and throws WorkspaceAccessDeniedException")
        void getWorkspace_privateWorkspace_inaccessibleToDeveloperOrViewer_throwsWorkspaceAccessDeniedException() {
            Workspace privateWorkspace = Workspace.builder()
                    .id(workspaceId)
                    .name("Executive")
                    .organization(testOrg)
                    .visibility(WorkspaceVisibility.PRIVATE)
                    .build();

            doNothing().when(authorizationService).requireMember(organizationId, callerUserId);
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(privateWorkspace));
            when(authorizationService.isAdminOrOwner(organizationId, callerUserId)).thenReturn(false);

            assertThatThrownBy(() -> workspaceService.getWorkspace(organizationId, workspaceId, callerUserId))
                    .isInstanceOf(WorkspaceAccessDeniedException.class)
                    .hasMessageContaining("ADMIN or OWNER role required to access private workspace");
        }

        @Test
        @DisplayName("workspace from another organization produces WorkspaceNotFoundException")
        void getWorkspace_whenWorkspaceNotFoundInOrganization_throwsWorkspaceNotFoundException() {
            doNothing().when(authorizationService).requireMember(organizationId, callerUserId);
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> workspaceService.getWorkspace(organizationId, workspaceId, callerUserId))
                    .isInstanceOf(WorkspaceNotFoundException.class)
                    .hasMessageContaining("Workspace not found: " + workspaceId);
        }

        @Test
        @DisplayName("non-member cannot get workspace")
        void getWorkspace_whenNotMember_throwsOrganizationMembershipRequiredException() {
            doThrow(new OrganizationMembershipRequiredException("Membership required"))
                    .when(authorizationService).requireMember(organizationId, callerUserId);

            assertThatThrownBy(() -> workspaceService.getWorkspace(organizationId, workspaceId, callerUserId))
                    .isInstanceOf(OrganizationMembershipRequiredException.class);

            verify(workspaceRepository, never()).findByIdAndOrganizationId(any(), any());
        }
    }

    // ── UPDATE WORKSPACE ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Update Workspace")
    class UpdateWorkspace {

        @Test
        @DisplayName("authorized ADMIN can update workspace fields")
        void updateWorkspace_asAdminOrOwner_updatesSpecifiedFields() {
            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .name("Engineering Core")
                    .visibility(WorkspaceVisibility.PRIVATE)
                    .description("Updated description")
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(testWorkspace));
            when(workspaceRepository.existsByOrganizationIdAndNameIgnoreCase(organizationId, "Engineering Core"))
                    .thenReturn(false);
            when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));

            WorkspaceResponse response = workspaceService.updateWorkspace(organizationId, workspaceId, request, callerUserId);

            assertThat(response).isNotNull();
            assertThat(response.getName()).isEqualTo("Engineering Core");
            assertThat(response.getVisibility()).isEqualTo(WorkspaceVisibility.PRIVATE);
            assertThat(response.getDescription()).isEqualTo("Updated description");

            verify(workspaceRepository).save(testWorkspace);
        }

        @Test
        @DisplayName("non-admin cannot update workspace")
        void updateWorkspace_whenNotAdminOrOwner_throwsOrganizationAccessDeniedException() {
            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .name("New Name")
                    .build();

            doThrow(new OrganizationAccessDeniedException("Access denied"))
                    .when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);

            assertThatThrownBy(() -> workspaceService.updateWorkspace(organizationId, workspaceId, request, callerUserId))
                    .isInstanceOf(OrganizationAccessDeniedException.class);

            verify(workspaceRepository, never()).save(any());
        }

        @Test
        @DisplayName("name is trimmed on update")
        void updateWorkspace_trimsNameAndPreservesCasing() {
            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .name("   Platform Eng   ")
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(testWorkspace));
            when(workspaceRepository.existsByOrganizationIdAndNameIgnoreCase(organizationId, "Platform Eng"))
                    .thenReturn(false);
            when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));

            workspaceService.updateWorkspace(organizationId, workspaceId, request, callerUserId);

            assertThat(testWorkspace.getName()).isEqualTo("Platform Eng");
            verify(workspaceRepository).existsByOrganizationIdAndNameIgnoreCase(organizationId, "Platform Eng");
        }

        @Test
        @DisplayName("blank name on update throws IllegalArgumentException")
        void updateWorkspace_whenBlankName_throwsIllegalArgumentException() {
            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .name("   ")
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(testWorkspace));

            assertThatThrownBy(() -> workspaceService.updateWorkspace(organizationId, workspaceId, request, callerUserId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Workspace name must not be blank");

            verify(workspaceRepository, never()).save(any());
        }

        @Test
        @DisplayName("duplicate name on update throws WorkspaceAlreadyExistsException")
        void updateWorkspace_whenNewNameConflictsWithExisting_throwsWorkspaceAlreadyExistsException() {
            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .name("Product")
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(testWorkspace));
            when(workspaceRepository.existsByOrganizationIdAndNameIgnoreCase(organizationId, "Product"))
                    .thenReturn(true);

            assertThatThrownBy(() -> workspaceService.updateWorkspace(organizationId, workspaceId, request, callerUserId))
                    .isInstanceOf(WorkspaceAlreadyExistsException.class)
                    .hasMessageContaining("Workspace already exists with name: Product");

            verify(workspaceRepository, never()).save(any());
        }

        @Test
        @DisplayName("updating with same name (ignoring case) does not produce false conflict")
        void updateWorkspace_whenNameUnchangedSameCaseOrDifferentCase_allowsUpdateWithoutConflict() {
            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .name("engineering")
                    .description("New description")
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(testWorkspace));
            when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));

            WorkspaceResponse response = workspaceService.updateWorkspace(organizationId, workspaceId, request, callerUserId);

            assertThat(response.getName()).isEqualTo("engineering");
            assertThat(response.getDescription()).isEqualTo("New description");
            verify(workspaceRepository, never()).existsByOrganizationIdAndNameIgnoreCase(any(), any());
            verify(workspaceRepository).save(testWorkspace);
        }

        @Test
        @DisplayName("null PATCH fields remain unchanged")
        void updateWorkspace_whenFieldsAreNull_retainsExistingValues() {
            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .name(null)
                    .visibility(null)
                    .description(null)
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(testWorkspace));
            when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));

            WorkspaceResponse response = workspaceService.updateWorkspace(organizationId, workspaceId, request, callerUserId);

            assertThat(response.getName()).isEqualTo("Engineering");
            assertThat(response.getVisibility()).isEqualTo(WorkspaceVisibility.PUBLIC);
            assertThat(response.getDescription()).isEqualTo("Engineering workspace");
        }

        @Test
        @DisplayName("update throws WorkspaceNotFoundException when workspace not in organization")
        void updateWorkspace_whenNotFoundInOrganization_throwsWorkspaceNotFoundException() {
            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .name("New Name")
                    .build();

            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> workspaceService.updateWorkspace(organizationId, workspaceId, request, callerUserId))
                    .isInstanceOf(WorkspaceNotFoundException.class)
                    .hasMessageContaining("Workspace not found: " + workspaceId);

            verify(workspaceRepository, never()).save(any());
        }
    }

    // ── DELETE WORKSPACE ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Delete Workspace")
    class DeleteWorkspace {

        @Test
        @DisplayName("authorized ADMIN can delete workspace")
        void deleteWorkspace_asAdminOrOwner_deletesSuccessfully() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(testWorkspace));

            workspaceService.deleteWorkspace(organizationId, workspaceId, callerUserId);

            verify(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            verify(workspaceRepository).delete(testWorkspace);
        }

        @Test
        @DisplayName("non-admin cannot delete workspace")
        void deleteWorkspace_whenNotAdminOrOwner_throwsOrganizationAccessDeniedException() {
            doThrow(new OrganizationAccessDeniedException("Access denied"))
                    .when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);

            assertThatThrownBy(() -> workspaceService.deleteWorkspace(organizationId, workspaceId, callerUserId))
                    .isInstanceOf(OrganizationAccessDeniedException.class);

            verify(workspaceRepository, never()).delete(any());
        }

        @Test
        @DisplayName("delete throws WorkspaceNotFoundException when workspace not in organization")
        void deleteWorkspace_whenNotFoundInOrganization_throwsWorkspaceNotFoundException() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> workspaceService.deleteWorkspace(organizationId, workspaceId, callerUserId))
                    .isInstanceOf(WorkspaceNotFoundException.class)
                    .hasMessageContaining("Workspace not found: " + workspaceId);

            verify(workspaceRepository, never()).delete(any());
        }
    }
}
