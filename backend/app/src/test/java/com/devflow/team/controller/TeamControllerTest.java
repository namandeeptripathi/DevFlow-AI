package com.devflow.team.controller;

import com.devflow.organization.exception.OrganizationMemberNotFoundException;
import com.devflow.security.jwt.JwtAuthenticationEntryPoint;
import com.devflow.security.jwt.JwtTokenProvider;
import com.devflow.security.user.CustomUserDetailsService;
import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamMember;
import com.devflow.team.domain.TeamRole;
import com.devflow.team.domain.TeamStatus;
import com.devflow.team.dto.request.AddTeamMemberRequest;
import com.devflow.team.dto.request.TransferTeamLeadershipRequest;
import com.devflow.team.dto.request.UpdateTeamRequest;
import com.devflow.team.exception.InvalidTeamOperationException;
import com.devflow.team.exception.TeamAccessDeniedException;
import com.devflow.team.exception.TeamAlreadyExistsException;
import com.devflow.team.exception.TeamMemberAlreadyExistsException;
import com.devflow.team.exception.TeamMemberNotFoundException;
import com.devflow.team.exception.TeamNotFoundException;
import com.devflow.team.service.TeamService;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import com.devflow.workspace.exception.WorkspaceAccessDeniedException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TeamController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TeamController REST API Tests")
class TeamControllerTest {

    private static final String BASE_URL = "/api/v1/teams/{teamId}";

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
    private UUID callerUserId;
    private UUID targetUserId;
    private DevFlowUserDetails userDetails;
    private Team testTeam;
    private User testUser;
    private User targetUser;
    private TeamMember leadMember;
    private TeamMember regularMember;

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        callerUserId = UUID.randomUUID();
        targetUserId = UUID.randomUUID();

        testUser = User.builder()
                .id(callerUserId)
                .email("lead@devflow.com")
                .username("techlead")
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        targetUser = User.builder()
                .id(targetUserId)
                .email("developer@devflow.com")
                .username("developer")
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
                .name("Core Backend")
                .workspace(workspace)
                .description("Core backend services")
                .status(TeamStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .version(0L)
                .build();

        leadMember = TeamMember.builder()
                .id(UUID.randomUUID())
                .team(testTeam)
                .user(testUser)
                .role(TeamRole.LEAD)
                .joinedAt(Instant.now())
                .build();

        regularMember = TeamMember.builder()
                .id(UUID.randomUUID())
                .team(testTeam)
                .user(targetUser)
                .role(TeamRole.MEMBER)
                .joinedAt(Instant.now())
                .build();
    }

    // ── GET /api/v1/teams/{teamId} ─────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/teams/{teamId}")
    class GetTeamEndpoint {

        @Test
        @DisplayName("existing team returns 200 OK and TeamResponse")
        void getTeam_existing_returns200() throws Exception {
            when(teamService.getTeam(eq(teamId), eq(callerUserId))).thenReturn(testTeam);

            mockMvc.perform(get(BASE_URL, teamId)
                            .with(user(userDetails)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(teamId.toString()))
                    .andExpect(jsonPath("$.name").value("Core Backend"))
                    .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            verify(teamService).getTeam(eq(teamId), eq(callerUserId));
        }

        @Test
        @DisplayName("nonexistent team returns 404 Not Found")
        void getTeam_nonexistent_returns404() throws Exception {
            when(teamService.getTeam(eq(teamId), eq(callerUserId)))
                    .thenThrow(new TeamNotFoundException("Team not found: " + teamId));

            mockMvc.perform(get(BASE_URL, teamId)
                            .with(user(userDetails)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("access denied returns 403 Forbidden")
        void getTeam_unauthorized_returns403() throws Exception {
            when(teamService.getTeam(eq(teamId), eq(callerUserId)))
                    .thenThrow(new WorkspaceAccessDeniedException("Access denied to workspace"));

            mockMvc.perform(get(BASE_URL, teamId)
                            .with(user(userDetails)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // ── PATCH /api/v1/teams/{teamId} ───────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /api/v1/teams/{teamId}")
    class UpdateTeamEndpoint {

        @Test
        @DisplayName("valid partial update returns 200 OK and updated TeamResponse")
        void updateTeam_validUpdate_returns200() throws Exception {
            UpdateTeamRequest request = UpdateTeamRequest.builder()
                    .name("Platform Core")
                    .description("Updated description")
                    .build();

            Team updatedTeam = Team.builder()
                    .id(teamId)
                    .name("Platform Core")
                    .workspace(testTeam.getWorkspace())
                    .description("Updated description")
                    .status(TeamStatus.ACTIVE)
                    .createdAt(testTeam.getCreatedAt())
                    .updatedAt(Instant.now())
                    .version(1L)
                    .build();

            when(teamService.updateTeam(eq(teamId), eq("Platform Core"), eq("Updated description"), eq(callerUserId)))
                    .thenReturn(updatedTeam);

            mockMvc.perform(patch(BASE_URL, teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Platform Core"))
                    .andExpect(jsonPath("$.description").value("Updated description"));

            verify(teamService).updateTeam(eq(teamId), eq("Platform Core"), eq("Updated description"), eq(callerUserId));
        }

        @Test
        @DisplayName("name exceeding 100 characters returns 400 Bad Request")
        void updateTeam_nameTooLong_returns400() throws Exception {
            UpdateTeamRequest request = UpdateTeamRequest.builder()
                    .name("A".repeat(101))
                    .build();

            mockMvc.perform(patch(BASE_URL, teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(teamService, never()).updateTeam(any(), any(), any(), any());
        }

        @Test
        @DisplayName("duplicate name returns 409 Conflict")
        void updateTeam_duplicateName_returns409() throws Exception {
            UpdateTeamRequest request = UpdateTeamRequest.builder()
                    .name("Existing Team")
                    .build();

            when(teamService.updateTeam(eq(teamId), eq("Existing Team"), any(), eq(callerUserId)))
                    .thenThrow(new TeamAlreadyExistsException("Team with name [Existing Team] already exists"));

            mockMvc.perform(patch(BASE_URL, teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("updating archived team returns 400 Bad Request")
        void updateTeam_archivedTeam_returns400() throws Exception {
            UpdateTeamRequest request = UpdateTeamRequest.builder()
                    .name("New Name")
                    .build();

            when(teamService.updateTeam(eq(teamId), eq("New Name"), any(), eq(callerUserId)))
                    .thenThrow(new InvalidTeamOperationException("Cannot update archived team [" + teamId + "]"));

            mockMvc.perform(patch(BASE_URL, teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("unauthorized caller returns 403 Forbidden")
        void updateTeam_unauthorized_returns403() throws Exception {
            UpdateTeamRequest request = UpdateTeamRequest.builder()
                    .name("New Name")
                    .build();

            when(teamService.updateTeam(eq(teamId), eq("New Name"), any(), eq(callerUserId)))
                    .thenThrow(new TeamAccessDeniedException("Access denied: ADMIN, OWNER, or LEAD role required"));

            mockMvc.perform(patch(BASE_URL, teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // ── POST /api/v1/teams/{teamId}/archive ────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/teams/{teamId}/archive")
    class ArchiveTeamEndpoint {

        @Test
        @DisplayName("active team returns 200 OK and archived TeamResponse")
        void archiveTeam_active_returns200() throws Exception {
            Team archivedTeam = Team.builder()
                    .id(teamId)
                    .name("Core Backend")
                    .workspace(testTeam.getWorkspace())
                    .status(TeamStatus.ARCHIVED)
                    .createdAt(testTeam.getCreatedAt())
                    .updatedAt(Instant.now())
                    .version(1L)
                    .build();

            when(teamService.archiveTeam(eq(teamId), eq(callerUserId))).thenReturn(archivedTeam);

            mockMvc.perform(post(BASE_URL + "/archive", teamId)
                            .with(user(userDetails)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ARCHIVED"));

            verify(teamService).archiveTeam(eq(teamId), eq(callerUserId));
        }

        @Test
        @DisplayName("already archived team returns 400 Bad Request")
        void archiveTeam_alreadyArchived_returns400() throws Exception {
            when(teamService.archiveTeam(eq(teamId), eq(callerUserId)))
                    .thenThrow(new InvalidTeamOperationException("Team [" + teamId + "] is already archived"));

            mockMvc.perform(post(BASE_URL + "/archive", teamId)
                            .with(user(userDetails)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("unauthorized caller returns 403 Forbidden")
        void archiveTeam_unauthorized_returns403() throws Exception {
            when(teamService.archiveTeam(eq(teamId), eq(callerUserId)))
                    .thenThrow(new TeamAccessDeniedException("Access denied: ADMIN, OWNER, or LEAD role required"));

            mockMvc.perform(post(BASE_URL + "/archive", teamId)
                            .with(user(userDetails)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // ── POST /api/v1/teams/{teamId}/members ────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/teams/{teamId}/members")
    class AddMemberEndpoint {

        @Test
        @DisplayName("valid request returns 201 Created and TeamMemberResponse")
        void addMember_valid_returns201() throws Exception {
            AddTeamMemberRequest request = AddTeamMemberRequest.builder()
                    .userId(targetUserId)
                    .build();

            when(teamService.addMember(eq(teamId), eq(targetUserId), eq(callerUserId)))
                    .thenReturn(regularMember);

            mockMvc.perform(post(BASE_URL + "/members", teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.userId").value(targetUserId.toString()))
                    .andExpect(jsonPath("$.role").value("MEMBER"))
                    .andExpect(jsonPath("$.email").value("developer@devflow.com"));

            verify(teamService).addMember(eq(teamId), eq(targetUserId), eq(callerUserId));
        }

        @Test
        @DisplayName("null userId returns 400 Bad Request")
        void addMember_nullUserId_returns400() throws Exception {
            AddTeamMemberRequest request = AddTeamMemberRequest.builder()
                    .userId(null)
                    .build();

            mockMvc.perform(post(BASE_URL + "/members", teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(teamService, never()).addMember(any(), any(), any());
        }

        @Test
        @DisplayName("already member returns 409 Conflict")
        void addMember_alreadyMember_returns409() throws Exception {
            AddTeamMemberRequest request = AddTeamMemberRequest.builder()
                    .userId(targetUserId)
                    .build();

            when(teamService.addMember(eq(teamId), eq(targetUserId), eq(callerUserId)))
                    .thenThrow(new TeamMemberAlreadyExistsException("User is already a member"));

            mockMvc.perform(post(BASE_URL + "/members", teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("target user not member of organization returns 404 Not Found")
        void addMember_notOrgMember_returns404() throws Exception {
            AddTeamMemberRequest request = AddTeamMemberRequest.builder()
                    .userId(targetUserId)
                    .build();

            when(teamService.addMember(eq(teamId), eq(targetUserId), eq(callerUserId)))
                    .thenThrow(new OrganizationMemberNotFoundException("User is not member of organization"));

            mockMvc.perform(post(BASE_URL + "/members", teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("suspended organization member returns 400 Bad Request")
        void addMember_suspendedOrgMember_returns400() throws Exception {
            AddTeamMemberRequest request = AddTeamMemberRequest.builder()
                    .userId(targetUserId)
                    .build();

            when(teamService.addMember(eq(teamId), eq(targetUserId), eq(callerUserId)))
                    .thenThrow(new InvalidTeamOperationException("Cannot add non-active or suspended member"));

            mockMvc.perform(post(BASE_URL + "/members", teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    // ── GET /api/v1/teams/{teamId}/members ─────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/teams/{teamId}/members")
    class ListMembersEndpoint {

        @Test
        @DisplayName("valid team returns 200 OK and list of members")
        void listMembers_valid_returns200() throws Exception {
            when(teamService.listMembers(eq(teamId), eq(callerUserId)))
                    .thenReturn(List.of(leadMember, regularMember));

            mockMvc.perform(get(BASE_URL + "/members", teamId)
                            .with(user(userDetails)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].role").value("LEAD"))
                    .andExpect(jsonPath("$[1].role").value("MEMBER"));

            verify(teamService).listMembers(eq(teamId), eq(callerUserId));
        }
    }

    // ── DELETE /api/v1/teams/{teamId}/members/{userId} ─────────────────────────

    @Nested
    @DisplayName("DELETE /api/v1/teams/{teamId}/members/{userId}")
    class RemoveMemberEndpoint {

        @Test
        @DisplayName("valid removal returns 204 No Content")
        void removeMember_valid_returns204() throws Exception {
            doNothing().when(teamService).removeMember(eq(teamId), eq(targetUserId), eq(callerUserId));

            mockMvc.perform(delete(BASE_URL + "/members/{userId}", teamId, targetUserId)
                            .with(user(userDetails)))
                    .andExpect(status().isNoContent());

            verify(teamService).removeMember(eq(teamId), eq(targetUserId), eq(callerUserId));
        }

        @Test
        @DisplayName("removing active lead returns 400 Bad Request")
        void removeMember_leadRemoval_returns400() throws Exception {
            doThrow(new InvalidTeamOperationException("Cannot remove active team lead"))
                    .when(teamService).removeMember(eq(teamId), eq(callerUserId), eq(callerUserId));

            mockMvc.perform(delete(BASE_URL + "/members/{userId}", teamId, callerUserId)
                            .with(user(userDetails)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("member not found returns 404 Not Found")
        void removeMember_notFound_returns404() throws Exception {
            doThrow(new TeamMemberNotFoundException("Member not found"))
                    .when(teamService).removeMember(eq(teamId), eq(targetUserId), eq(callerUserId));

            mockMvc.perform(delete(BASE_URL + "/members/{userId}", teamId, targetUserId)
                            .with(user(userDetails)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    // ── POST /api/v1/teams/{teamId}/leave ──────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/teams/{teamId}/leave")
    class LeaveTeamEndpoint {

        @Test
        @DisplayName("regular member can leave team returning 204 No Content")
        void leaveTeam_regularMember_returns204() throws Exception {
            doNothing().when(teamService).leaveTeam(eq(teamId), eq(callerUserId));

            mockMvc.perform(post(BASE_URL + "/leave", teamId)
                            .with(user(userDetails)))
                    .andExpect(status().isNoContent());

            verify(teamService).leaveTeam(eq(teamId), eq(callerUserId));
        }

        @Test
        @DisplayName("lead leaving without transfer returns 400 Bad Request")
        void leaveTeam_leadAttempt_returns400() throws Exception {
            doThrow(new InvalidTeamOperationException("Team lead cannot leave team without transferring leadership"))
                    .when(teamService).leaveTeam(eq(teamId), eq(callerUserId));

            mockMvc.perform(post(BASE_URL + "/leave", teamId)
                            .with(user(userDetails)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    // ── GET /api/v1/teams/{teamId}/lead ────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/teams/{teamId}/lead")
    class GetTeamLeadEndpoint {

        @Test
        @DisplayName("resolves lead returning 200 OK and TeamLeadResponse")
        void getTeamLead_existing_returns200() throws Exception {
            when(teamService.getTeamLead(eq(teamId), eq(callerUserId))).thenReturn(leadMember);

            mockMvc.perform(get(BASE_URL + "/lead", teamId)
                            .with(user(userDetails)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(callerUserId.toString()))
                    .andExpect(jsonPath("$.role").value("LEAD"))
                    .andExpect(jsonPath("$.email").value("lead@devflow.com"));

            verify(teamService).getTeamLead(eq(teamId), eq(callerUserId));
        }

        @Test
        @DisplayName("lead not found returns 404 Not Found")
        void getTeamLead_notFound_returns404() throws Exception {
            when(teamService.getTeamLead(eq(teamId), eq(callerUserId)))
                    .thenThrow(new TeamMemberNotFoundException("No lead found for team"));

            mockMvc.perform(get(BASE_URL + "/lead", teamId)
                            .with(user(userDetails)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    // ── PUT /api/v1/teams/{teamId}/lead ────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/v1/teams/{teamId}/lead")
    class TransferLeadershipEndpoint {

        @Test
        @DisplayName("valid leadership transfer returns 200 OK and new TeamLeadResponse")
        void transferLeadership_valid_returns200() throws Exception {
            TransferTeamLeadershipRequest request = TransferTeamLeadershipRequest.builder()
                    .userId(targetUserId)
                    .build();

            TeamMember promotedLead = TeamMember.builder()
                    .id(regularMember.getId())
                    .team(testTeam)
                    .user(targetUser)
                    .role(TeamRole.LEAD)
                    .joinedAt(regularMember.getJoinedAt())
                    .build();

            when(teamService.transferLeadership(eq(teamId), eq(targetUserId), eq(callerUserId)))
                    .thenReturn(promotedLead);

            mockMvc.perform(put(BASE_URL + "/lead", teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(targetUserId.toString()))
                    .andExpect(jsonPath("$.role").value("LEAD"))
                    .andExpect(jsonPath("$.username").value("developer"));

            verify(teamService).transferLeadership(eq(teamId), eq(targetUserId), eq(callerUserId));
        }

        @Test
        @DisplayName("null userId in transfer returns 400 Bad Request")
        void transferLeadership_nullUserId_returns400() throws Exception {
            TransferTeamLeadershipRequest request = TransferTeamLeadershipRequest.builder()
                    .userId(null)
                    .build();

            mockMvc.perform(put(BASE_URL + "/lead", teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(teamService, never()).transferLeadership(any(), any(), any());
        }

        @Test
        @DisplayName("target user not member returns 404 Not Found")
        void transferLeadership_targetNotMember_returns404() throws Exception {
            TransferTeamLeadershipRequest request = TransferTeamLeadershipRequest.builder()
                    .userId(targetUserId)
                    .build();

            when(teamService.transferLeadership(eq(teamId), eq(targetUserId), eq(callerUserId)))
                    .thenThrow(new TeamMemberNotFoundException("Target is not a member of team"));

            mockMvc.perform(put(BASE_URL + "/lead", teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("target user already lead returns 400 Bad Request")
        void transferLeadership_alreadyLead_returns400() throws Exception {
            TransferTeamLeadershipRequest request = TransferTeamLeadershipRequest.builder()
                    .userId(targetUserId)
                    .build();

            when(teamService.transferLeadership(eq(teamId), eq(targetUserId), eq(callerUserId)))
                    .thenThrow(new InvalidTeamOperationException("Target user is already the lead"));

            mockMvc.perform(put(BASE_URL + "/lead", teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("archived team transfer returns 400 Bad Request")
        void transferLeadership_archivedTeam_returns400() throws Exception {
            TransferTeamLeadershipRequest request = TransferTeamLeadershipRequest.builder()
                    .userId(targetUserId)
                    .build();

            when(teamService.transferLeadership(eq(teamId), eq(targetUserId), eq(callerUserId)))
                    .thenThrow(new InvalidTeamOperationException("Cannot transfer leadership of archived team"));

            mockMvc.perform(put(BASE_URL + "/lead", teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("unauthorized caller returns 403 Forbidden")
        void transferLeadership_unauthorized_returns403() throws Exception {
            TransferTeamLeadershipRequest request = TransferTeamLeadershipRequest.builder()
                    .userId(targetUserId)
                    .build();

            when(teamService.transferLeadership(eq(teamId), eq(targetUserId), eq(callerUserId)))
                    .thenThrow(new TeamAccessDeniedException("Access denied: ADMIN, OWNER, or current LEAD role required"));

            mockMvc.perform(put(BASE_URL + "/lead", teamId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }
}

