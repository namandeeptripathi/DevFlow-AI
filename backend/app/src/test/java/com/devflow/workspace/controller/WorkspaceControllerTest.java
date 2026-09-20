package com.devflow.workspace.controller;

import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationMembershipRequiredException;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.security.jwt.JwtAuthenticationEntryPoint;
import com.devflow.security.jwt.JwtTokenProvider;
import com.devflow.security.user.CustomUserDetailsService;
import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.workspace.domain.WorkspaceVisibility;
import com.devflow.workspace.dto.CreateWorkspaceRequest;
import com.devflow.workspace.dto.UpdateWorkspaceRequest;
import com.devflow.workspace.dto.WorkspaceResponse;
import com.devflow.workspace.exception.WorkspaceAccessDeniedException;
import com.devflow.workspace.exception.WorkspaceAlreadyExistsException;
import com.devflow.workspace.exception.WorkspaceNotFoundException;
import com.devflow.workspace.service.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkspaceController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("WorkspaceController REST API")
class WorkspaceControllerTest {

    private static final String BASE_URL = "/api/v1/organizations/{organizationId}/workspaces";
    private static final String ITEM_URL = "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkspaceService workspaceService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    @MockBean
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private UUID organizationId;
    private UUID workspaceId;
    private UUID userId;
    private DevFlowUserDetails userDetails;
    private WorkspaceResponse testWorkspaceResponse;

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        userId = UUID.randomUUID();

        User testUser = User.builder()
                .id(userId)
                .email("admin@devflow.com")
                .username("adminuser")
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        userDetails = new DevFlowUserDetails(testUser);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);

        testWorkspaceResponse = WorkspaceResponse.builder()
                .id(workspaceId)
                .name("Engineering")
                .organizationId(organizationId)
                .visibility(WorkspaceVisibility.PUBLIC)
                .description("Engineering workspace")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .version(0L)
                .build();
    }

    // ── POST /api/v1/organizations/{organizationId}/workspaces ────────────────

    @Nested
    @DisplayName("POST /api/v1/organizations/{organizationId}/workspaces")
    class CreateWorkspaceEndpoint {

        @Test
        @DisplayName("valid request returns 201 Created with WorkspaceResponse")
        void createWorkspace_validRequest_returns201() throws Exception {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("Engineering")
                    .visibility(WorkspaceVisibility.PUBLIC)
                    .description("Core engineering team")
                    .build();

            when(workspaceService.createWorkspace(eq(organizationId), any(CreateWorkspaceRequest.class), eq(userId)))
                    .thenReturn(testWorkspaceResponse);

            mockMvc.perform(post(BASE_URL, organizationId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(workspaceId.toString()))
                    .andExpect(jsonPath("$.name").value("Engineering"))
                    .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
                    .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                    .andExpect(jsonPath("$.description").value("Engineering workspace"));

            verify(workspaceService).createWorkspace(eq(organizationId), any(CreateWorkspaceRequest.class), eq(userId));
        }

        @Test
        @DisplayName("invalid request with blank name returns 400 Bad Request")
        void createWorkspace_blankName_returns400() throws Exception {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("")
                    .build();

            mockMvc.perform(post(BASE_URL, organizationId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"));

            verify(workspaceService, never()).createWorkspace(any(), any(), any());
        }

        @Test
        @DisplayName("invalid request with name exceeding 100 characters returns 400 Bad Request")
        void createWorkspace_nameTooLong_returns400() throws Exception {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("A".repeat(101))
                    .build();

            mockMvc.perform(post(BASE_URL, organizationId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(workspaceService, never()).createWorkspace(any(), any(), any());
        }

        @Test
        @DisplayName("invalid request with description exceeding 500 characters returns 400 Bad Request")
        void createWorkspace_descriptionTooLong_returns400() throws Exception {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("Engineering")
                    .description("D".repeat(501))
                    .build();

            mockMvc.perform(post(BASE_URL, organizationId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(workspaceService, never()).createWorkspace(any(), any(), any());
        }

        @Test
        @DisplayName("missing organization returns 404 Not Found")
        void createWorkspace_organizationNotFound_returns404() throws Exception {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("Engineering")
                    .build();

            when(workspaceService.createWorkspace(eq(organizationId), any(CreateWorkspaceRequest.class), eq(userId)))
                    .thenThrow(new OrganizationNotFoundException("Organization not found: " + organizationId));

            mockMvc.perform(post(BASE_URL, organizationId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        @DisplayName("duplicate name in organization returns 409 Conflict")
        void createWorkspace_duplicateName_returns409() throws Exception {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("Engineering")
                    .build();

            when(workspaceService.createWorkspace(eq(organizationId), any(CreateWorkspaceRequest.class), eq(userId)))
                    .thenThrow(new WorkspaceAlreadyExistsException("Workspace already exists with name: Engineering"));

            mockMvc.perform(post(BASE_URL, organizationId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.error").value("Conflict"));
        }

        @Test
        @DisplayName("unauthorized caller returns 403 Forbidden")
        void createWorkspace_unauthorized_returns403() throws Exception {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("Engineering")
                    .build();

            when(workspaceService.createWorkspace(eq(organizationId), any(CreateWorkspaceRequest.class), eq(userId)))
                    .thenThrow(new OrganizationAccessDeniedException("Access denied: ADMIN or OWNER required"));

            mockMvc.perform(post(BASE_URL, organizationId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }
    }

    // ── GET /api/v1/organizations/{organizationId}/workspaces ─────────────────

    @Nested
    @DisplayName("GET /api/v1/organizations/{organizationId}/workspaces")
    class ListWorkspacesEndpoint {

        @Test
        @DisplayName("authenticated request returns 200 OK with list of WorkspaceResponse")
        void getWorkspaces_validRequest_returns200() throws Exception {
            when(workspaceService.getWorkspaces(organizationId, userId))
                    .thenReturn(List.of(testWorkspaceResponse));

            mockMvc.perform(get(BASE_URL, organizationId)
                            .with(user(userDetails)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(workspaceId.toString()))
                    .andExpect(jsonPath("$[0].name").value("Engineering"));

            verify(workspaceService).getWorkspaces(organizationId, userId);
        }

        @Test
        @DisplayName("non-member request returns 403 Forbidden")
        void getWorkspaces_nonMember_returns403() throws Exception {
            when(workspaceService.getWorkspaces(organizationId, userId))
                    .thenThrow(new OrganizationMembershipRequiredException("Membership required"));

            mockMvc.perform(get(BASE_URL, organizationId)
                            .with(user(userDetails)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // ── GET /api/v1/organizations/{organizationId}/workspaces/{workspaceId} ───

    @Nested
    @DisplayName("GET /api/v1/organizations/{organizationId}/workspaces/{workspaceId}")
    class GetWorkspaceEndpoint {

        @Test
        @DisplayName("valid request returns 200 OK with WorkspaceResponse")
        void getWorkspace_validRequest_returns200() throws Exception {
            when(workspaceService.getWorkspace(organizationId, workspaceId, userId))
                    .thenReturn(testWorkspaceResponse);

            mockMvc.perform(get(ITEM_URL, organizationId, workspaceId)
                            .with(user(userDetails)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(workspaceId.toString()))
                    .andExpect(jsonPath("$.name").value("Engineering"))
                    .andExpect(jsonPath("$.organizationId").value(organizationId.toString()));

            verify(workspaceService).getWorkspace(organizationId, workspaceId, userId);
        }

        @Test
        @DisplayName("workspace not found returns 404 Not Found")
        void getWorkspace_notFound_returns404() throws Exception {
            when(workspaceService.getWorkspace(organizationId, workspaceId, userId))
                    .thenThrow(new WorkspaceNotFoundException("Workspace not found: " + workspaceId));

            mockMvc.perform(get(ITEM_URL, organizationId, workspaceId)
                            .with(user(userDetails)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        @DisplayName("access denied to private workspace returns 403 Forbidden")
        void getWorkspace_privateAccessDenied_returns403() throws Exception {
            when(workspaceService.getWorkspace(organizationId, workspaceId, userId))
                    .thenThrow(new WorkspaceAccessDeniedException("ADMIN or OWNER role required"));

            mockMvc.perform(get(ITEM_URL, organizationId, workspaceId)
                            .with(user(userDetails)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }
    }

    // ── PATCH /api/v1/organizations/{organizationId}/workspaces/{workspaceId} ──

    @Nested
    @DisplayName("PATCH /api/v1/organizations/{organizationId}/workspaces/{workspaceId}")
    class UpdateWorkspaceEndpoint {

        @Test
        @DisplayName("valid request returns 200 OK with updated WorkspaceResponse")
        void updateWorkspace_validRequest_returns200() throws Exception {
            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .name("Engineering Core")
                    .visibility(WorkspaceVisibility.PRIVATE)
                    .description("Updated description")
                    .build();

            WorkspaceResponse updatedResponse = WorkspaceResponse.builder()
                    .id(workspaceId)
                    .name("Engineering Core")
                    .organizationId(organizationId)
                    .visibility(WorkspaceVisibility.PRIVATE)
                    .description("Updated description")
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .version(1L)
                    .build();

            when(workspaceService.updateWorkspace(eq(organizationId), eq(workspaceId), any(UpdateWorkspaceRequest.class), eq(userId)))
                    .thenReturn(updatedResponse);

            mockMvc.perform(patch(ITEM_URL, organizationId, workspaceId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Engineering Core"))
                    .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                    .andExpect(jsonPath("$.description").value("Updated description"));

            verify(workspaceService).updateWorkspace(eq(organizationId), eq(workspaceId), any(UpdateWorkspaceRequest.class), eq(userId));
        }

        @Test
        @DisplayName("name exceeding 100 characters returns 400 Bad Request")
        void updateWorkspace_nameTooLong_returns400() throws Exception {
            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .name("X".repeat(101))
                    .build();

            mockMvc.perform(patch(ITEM_URL, organizationId, workspaceId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(workspaceService, never()).updateWorkspace(any(), any(), any(), any());
        }

        @Test
        @DisplayName("description exceeding 500 characters returns 400 Bad Request")
        void updateWorkspace_descriptionTooLong_returns400() throws Exception {
            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .description("D".repeat(501))
                    .build();

            mockMvc.perform(patch(ITEM_URL, organizationId, workspaceId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(workspaceService, never()).updateWorkspace(any(), any(), any(), any());
        }

        @Test
        @DisplayName("workspace not found returns 404 Not Found")
        void updateWorkspace_notFound_returns404() throws Exception {
            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .name("New Name")
                    .build();

            when(workspaceService.updateWorkspace(eq(organizationId), eq(workspaceId), any(UpdateWorkspaceRequest.class), eq(userId)))
                    .thenThrow(new WorkspaceNotFoundException("Workspace not found: " + workspaceId));

            mockMvc.perform(patch(ITEM_URL, organizationId, workspaceId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("duplicate name returns 409 Conflict")
        void updateWorkspace_duplicateName_returns409() throws Exception {
            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .name("Existing Workspace")
                    .build();

            when(workspaceService.updateWorkspace(eq(organizationId), eq(workspaceId), any(UpdateWorkspaceRequest.class), eq(userId)))
                    .thenThrow(new WorkspaceAlreadyExistsException("Workspace already exists with name: Existing Workspace"));

            mockMvc.perform(patch(ITEM_URL, organizationId, workspaceId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("non-admin caller returns 403 Forbidden")
        void updateWorkspace_nonAdmin_returns403() throws Exception {
            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .name("New Name")
                    .build();

            when(workspaceService.updateWorkspace(eq(organizationId), eq(workspaceId), any(UpdateWorkspaceRequest.class), eq(userId)))
                    .thenThrow(new OrganizationAccessDeniedException("ADMIN or OWNER role required"));

            mockMvc.perform(patch(ITEM_URL, organizationId, workspaceId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // ── DELETE /api/v1/organizations/{organizationId}/workspaces/{workspaceId} ─

    @Nested
    @DisplayName("DELETE /api/v1/organizations/{organizationId}/workspaces/{workspaceId}")
    class DeleteWorkspaceEndpoint {

        @Test
        @DisplayName("valid request returns 204 No Content with empty body")
        void deleteWorkspace_validRequest_returns204() throws Exception {
            doNothing().when(workspaceService).deleteWorkspace(organizationId, workspaceId, userId);

            mockMvc.perform(delete(ITEM_URL, organizationId, workspaceId)
                            .with(user(userDetails)))
                    .andExpect(status().isNoContent())
                    .andExpect(jsonPath("$").doesNotExist());

            verify(workspaceService).deleteWorkspace(organizationId, workspaceId, userId);
        }

        @Test
        @DisplayName("workspace not found returns 404 Not Found")
        void deleteWorkspace_notFound_returns404() throws Exception {
            doThrow(new WorkspaceNotFoundException("Workspace not found: " + workspaceId))
                    .when(workspaceService).deleteWorkspace(organizationId, workspaceId, userId);

            mockMvc.perform(delete(ITEM_URL, organizationId, workspaceId)
                            .with(user(userDetails)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("non-admin caller returns 403 Forbidden")
        void deleteWorkspace_nonAdmin_returns403() throws Exception {
            doThrow(new OrganizationAccessDeniedException("ADMIN or OWNER role required"))
                    .when(workspaceService).deleteWorkspace(organizationId, workspaceId, userId);

            mockMvc.perform(delete(ITEM_URL, organizationId, workspaceId)
                            .with(user(userDetails)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }
}
