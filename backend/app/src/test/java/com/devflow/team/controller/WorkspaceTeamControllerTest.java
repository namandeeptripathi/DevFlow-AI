package com.devflow.team.controller;

import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.security.jwt.JwtAuthenticationEntryPoint;
import com.devflow.security.jwt.JwtTokenProvider;
import com.devflow.security.user.CustomUserDetailsService;
import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamStatus;
import com.devflow.team.dto.request.CreateTeamRequest;
import com.devflow.team.exception.TeamAlreadyExistsException;
import com.devflow.team.exception.TeamNotFoundException;
import com.devflow.team.service.TeamService;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import com.devflow.workspace.exception.WorkspaceAccessDeniedException;
import com.devflow.workspace.exception.WorkspaceNotFoundException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkspaceTeamController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("WorkspaceTeamController REST API Tests")
class WorkspaceTeamControllerTest {

    private static final String BASE_URL = "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams";
    private static final String ITEM_URL = "/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams/{teamId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TeamService teamService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    @MockBean
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private UUID organizationId;
    private UUID workspaceId;
    private UUID teamId;
    private UUID userId;
    private DevFlowUserDetails userDetails;
    private Team testTeam;

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        teamId = UUID.randomUUID();
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

        Workspace workspace = Workspace.builder()
                .id(workspaceId)
                .name("Core Engineering")
                .visibility(WorkspaceVisibility.PUBLIC)
                .build();

        testTeam = Team.builder()
                .id(teamId)
                .name("Backend Platform")
                .workspace(workspace)
                .description("Core backend systems")
                .status(TeamStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .version(0L)
                .build();
    }

    // ── POST /api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams ─

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateTeamEndpoint {

        @Test
        @DisplayName("valid payload returns 201 Created and TeamResponse")
        void createTeam_validPayload_returns201() throws Exception {
            CreateTeamRequest request = CreateTeamRequest.builder()
                    .name("Backend Platform")
                    .description("Core backend systems")
                    .build();

            when(teamService.createTeam(eq(organizationId), eq(workspaceId), eq("Backend Platform"), eq("Core backend systems"), eq(userId)))
                    .thenReturn(testTeam);

            mockMvc.perform(post(BASE_URL, organizationId, workspaceId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(teamId.toString()))
                    .andExpect(jsonPath("$.name").value("Backend Platform"))
                    .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()))
                    .andExpect(jsonPath("$.description").value("Core backend systems"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            verify(teamService).createTeam(eq(organizationId), eq(workspaceId), eq("Backend Platform"), eq("Core backend systems"), eq(userId));
        }

        @Test
        @DisplayName("blank name returns 400 Bad Request")
        void createTeam_blankName_returns400() throws Exception {
            CreateTeamRequest request = CreateTeamRequest.builder()
                    .name("")
                    .build();

            mockMvc.perform(post(BASE_URL, organizationId, workspaceId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(teamService, never()).createTeam(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("name exceeding 100 characters returns 400 Bad Request")
        void createTeam_nameTooLong_returns400() throws Exception {
            CreateTeamRequest request = CreateTeamRequest.builder()
                    .name("A".repeat(101))
                    .build();

            mockMvc.perform(post(BASE_URL, organizationId, workspaceId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(teamService, never()).createTeam(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("description exceeding 500 characters returns 400 Bad Request")
        void createTeam_descriptionTooLong_returns400() throws Exception {
            CreateTeamRequest request = CreateTeamRequest.builder()
                    .name("Backend Platform")
                    .description("D".repeat(501))
                    .build();

            mockMvc.perform(post(BASE_URL, organizationId, workspaceId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(teamService, never()).createTeam(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("duplicate team name returns 409 Conflict")
        void createTeam_duplicateName_returns409() throws Exception {
            CreateTeamRequest request = CreateTeamRequest.builder()
                    .name("Backend Platform")
                    .build();

            when(teamService.createTeam(eq(organizationId), eq(workspaceId), eq("Backend Platform"), any(), eq(userId)))
                    .thenThrow(new TeamAlreadyExistsException("Team with name [Backend Platform] already exists"));

            mockMvc.perform(post(BASE_URL, organizationId, workspaceId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.error").value("Conflict"));
        }

        @Test
        @DisplayName("workspace not found returns 404 Not Found")
        void createTeam_workspaceNotFound_returns404() throws Exception {
            CreateTeamRequest request = CreateTeamRequest.builder()
                    .name("Backend Platform")
                    .build();

            when(teamService.createTeam(eq(organizationId), eq(workspaceId), eq("Backend Platform"), any(), eq(userId)))
                    .thenThrow(new WorkspaceNotFoundException("Workspace not found: " + workspaceId));

            mockMvc.perform(post(BASE_URL, organizationId, workspaceId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("unauthorized caller returns 403 Forbidden")
        void createTeam_unauthorized_returns403() throws Exception {
            CreateTeamRequest request = CreateTeamRequest.builder()
                    .name("Backend Platform")
                    .build();

            when(teamService.createTeam(eq(organizationId), eq(workspaceId), eq("Backend Platform"), any(), eq(userId)))
                    .thenThrow(new OrganizationAccessDeniedException("Access denied: ADMIN or OWNER role required"));

            mockMvc.perform(post(BASE_URL, organizationId, workspaceId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // ── GET /api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams ──

    @Nested
    @DisplayName("GET " + BASE_URL)
    class ListTeamsEndpoint {

        @Test
        @DisplayName("list without filter returns 200 OK and all teams")
        void listTeams_noFilter_returns200() throws Exception {
            when(teamService.listTeams(eq(organizationId), eq(workspaceId), eq(null), eq(userId)))
                    .thenReturn(List.of(testTeam));

            mockMvc.perform(get(BASE_URL, organizationId, workspaceId)
                            .with(user(userDetails)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(teamId.toString()))
                    .andExpect(jsonPath("$[0].name").value("Backend Platform"));

            verify(teamService).listTeams(eq(organizationId), eq(workspaceId), eq(null), eq(userId));
        }

        @Test
        @DisplayName("list with status filter returns 200 OK and filtered teams")
        void listTeams_withStatusFilter_returns200() throws Exception {
            when(teamService.listTeams(eq(organizationId), eq(workspaceId), eq(TeamStatus.ACTIVE), eq(userId)))
                    .thenReturn(List.of(testTeam));

            mockMvc.perform(get(BASE_URL, organizationId, workspaceId)
                            .param("status", "ACTIVE")
                            .with(user(userDetails)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].status").value("ACTIVE"));

            verify(teamService).listTeams(eq(organizationId), eq(workspaceId), eq(TeamStatus.ACTIVE), eq(userId));
        }

        @Test
        @DisplayName("private workspace without admin/owner access returns 403 Forbidden")
        void listTeams_privateWorkspaceUnauthorized_returns403() throws Exception {
            when(teamService.listTeams(eq(organizationId), eq(workspaceId), any(), eq(userId)))
                    .thenThrow(new WorkspaceAccessDeniedException("Access denied: ADMIN or OWNER role required"));

            mockMvc.perform(get(BASE_URL, organizationId, workspaceId)
                            .with(user(userDetails)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // ── GET /api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams/{teamId} ─

    @Nested
    @DisplayName("GET " + ITEM_URL)
    class GetTeamInWorkspaceEndpoint {

        @Test
        @DisplayName("existing team returns 200 OK and TeamResponse")
        void getTeam_existing_returns200() throws Exception {
            when(teamService.getTeam(eq(organizationId), eq(workspaceId), eq(teamId), eq(userId)))
                    .thenReturn(testTeam);

            mockMvc.perform(get(ITEM_URL, organizationId, workspaceId, teamId)
                            .with(user(userDetails)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(teamId.toString()))
                    .andExpect(jsonPath("$.name").value("Backend Platform"));

            verify(teamService).getTeam(eq(organizationId), eq(workspaceId), eq(teamId), eq(userId));
        }

        @Test
        @DisplayName("nonexistent team returns 404 Not Found")
        void getTeam_nonexistent_returns404() throws Exception {
            when(teamService.getTeam(eq(organizationId), eq(workspaceId), eq(teamId), eq(userId)))
                    .thenThrow(new TeamNotFoundException("Team not found: " + teamId));

            mockMvc.perform(get(ITEM_URL, organizationId, workspaceId, teamId)
                            .with(user(userDetails)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }
}

