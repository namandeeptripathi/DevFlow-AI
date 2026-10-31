package com.devflow.team.controller;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamMember;
import com.devflow.team.domain.TeamRole;
import com.devflow.team.domain.TeamStatus;
import com.devflow.team.dto.request.AddTeamMemberRequest;
import com.devflow.team.dto.request.CreateTeamRequest;
import com.devflow.team.dto.request.TransferTeamLeadershipRequest;
import com.devflow.team.dto.request.UpdateTeamRequest;
import com.devflow.team.repository.TeamMemberRepository;
import com.devflow.team.repository.TeamRepository;
import com.devflow.team.service.TeamService;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import com.devflow.workspace.repository.WorkspaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-End integration tests for Team REST APIs.
 *
 * <p>Executes against the real PostgreSQL container and full Spring Boot application context,
 * verifying that HTTP requests correctly mutate and query persistence, enforce multi-tenant boundaries,
 * and validate leadership invariants.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("TeamController Integration Tests")
class TeamControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TeamService teamService;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private Organization orgA;
    private Organization orgB;
    private Workspace publicWorkspace;
    private Workspace privateWorkspace;
    private User ownerUser;
    private User adminUser;
    private User memberUser1;
    private User memberUser2;
    private User suspendedUser;
    private User otherOrgUser;

    @BeforeEach
    void setUp() {
        // Clean up
        teamMemberRepository.deleteAll();
        teamRepository.deleteAll();

        // 1. Create Users
        ownerUser = createUser("owner-" + UUID.randomUUID() + "@acme.com", "owner_" + UUID.randomUUID().toString().substring(0, 8));
        adminUser = createUser("admin-" + UUID.randomUUID() + "@acme.com", "admin_" + UUID.randomUUID().toString().substring(0, 8));
        memberUser1 = createUser("dev1-" + UUID.randomUUID() + "@acme.com", "dev1_" + UUID.randomUUID().toString().substring(0, 8));
        memberUser2 = createUser("dev2-" + UUID.randomUUID() + "@acme.com", "dev2_" + UUID.randomUUID().toString().substring(0, 8));
        suspendedUser = createUser("suspended-" + UUID.randomUUID() + "@acme.com", "susp_" + UUID.randomUUID().toString().substring(0, 8));
        otherOrgUser = createUser("other-" + UUID.randomUUID() + "@beta.com", "other_" + UUID.randomUUID().toString().substring(0, 8));

        // 2. Create Organization A
        orgA = Organization.builder()
                .name("Acme Corp")
                .slug("acme-" + UUID.randomUUID())
                .description("Acme Corp Tenant")
                .owner(ownerUser)
                .build();
        orgA = organizationRepository.save(orgA);

        // 3. Create Organization B
        orgB = Organization.builder()
                .name("Beta Corp")
                .slug("beta-" + UUID.randomUUID())
                .description("Beta Corp Tenant")
                .owner(otherOrgUser)
                .build();
        orgB = organizationRepository.save(orgB);

        // 4. Create Organization Memberships
        createOrgMember(orgA, ownerUser, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);
        createOrgMember(orgA, adminUser, OrganizationRole.ADMIN, OrganizationMembershipStatus.ACTIVE);
        createOrgMember(orgA, memberUser1, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.ACTIVE);
        createOrgMember(orgA, memberUser2, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.ACTIVE);
        createOrgMember(orgA, suspendedUser, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.SUSPENDED);
        createOrgMember(orgB, otherOrgUser, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);

        // 5. Create Workspaces in Org A
        publicWorkspace = Workspace.builder()
                .name("Engineering")
                .organization(orgA)
                .visibility(WorkspaceVisibility.PUBLIC)
                .build();
        publicWorkspace = workspaceRepository.save(publicWorkspace);

        privateWorkspace = Workspace.builder()
                .name("Security & Ops")
                .organization(orgA)
                .visibility(WorkspaceVisibility.PRIVATE)
                .build();
        privateWorkspace = workspaceRepository.save(privateWorkspace);

        entityManager.flush();
    }

    private User createUser(String email, String username) {
        User user = User.builder()
                .email(email)
                .username(username)
                .passwordHash("hashed_secret")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
        return userRepository.save(user);
    }

    private OrganizationMember createOrgMember(
            Organization org, User user, OrganizationRole role, OrganizationMembershipStatus status) {
        OrganizationMember member = OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(role)
                .status(status)
                .build();
        return organizationMemberRepository.save(member);
    }

    // ── 1. Team Creation API ───────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/organizations/{orgId}/workspaces/{wsId}/teams")
    class TeamCreationIntegration {

        @Test
        @DisplayName("admin can create team in public workspace and is assigned initial lead")
        void createTeam_adminSuccess() throws Exception {
            CreateTeamRequest request = CreateTeamRequest.builder()
                    .name("Backend Engineering")
                    .description("Core platform services")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/workspaces/{wsId}/teams",
                            orgA.getId(), publicWorkspace.getId())
                            .with(user(new DevFlowUserDetails(adminUser)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Backend Engineering"))
                    .andExpect(jsonPath("$.workspaceId").value(publicWorkspace.getId().toString()))
                    .andExpect(jsonPath("$.organizationId").value(orgA.getId().toString()))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            entityManager.flush();

            // Verify team created in database
            Optional<Team> createdTeam = teamRepository.findByIdAndWorkspaceId(
                    teamRepository.findByWorkspaceId(publicWorkspace.getId()).get(0).getId(),
                    publicWorkspace.getId()
            );
            assertThat(createdTeam).isPresent();
            assertThat(createdTeam.get().getName()).isEqualTo("Backend Engineering");

            // Verify initial lead assignment
            Optional<TeamMember> lead = teamMemberRepository.findLeadByTeamId(createdTeam.get().getId());
            assertThat(lead).isPresent();
            assertThat(lead.get().getUser().getId()).isEqualTo(adminUser.getId());
            assertThat(lead.get().getRole()).isEqualTo(TeamRole.LEAD);
        }

        @Test
        @DisplayName("duplicate team name in same workspace returns 409 Conflict")
        void createTeam_duplicateName_returns409() throws Exception {
            teamService.createTeam(orgA.getId(), publicWorkspace.getId(), "Unique Team", null, adminUser.getId());
            entityManager.flush();

            CreateTeamRequest duplicateRequest = CreateTeamRequest.builder()
                    .name("unique team") // case-insensitive duplicate
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/workspaces/{wsId}/teams",
                            orgA.getId(), publicWorkspace.getId())
                            .with(user(new DevFlowUserDetails(adminUser)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(duplicateRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("regular member cannot create team and receives 403 Forbidden")
        void createTeam_regularMember_returns403() throws Exception {
            CreateTeamRequest request = CreateTeamRequest.builder()
                    .name("Developer Team")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/workspaces/{wsId}/teams",
                            orgA.getId(), publicWorkspace.getId())
                            .with(user(new DevFlowUserDetails(memberUser1)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // ── 2. Team Lifecycle APIs ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Team Resource Endpoints (/api/v1/teams/{teamId})")
    class TeamResourceIntegration {

        private Team team;

        @BeforeEach
        void setupTeam() {
            team = teamService.createTeam(
                    orgA.getId(), publicWorkspace.getId(), "Platform Team", "Initial Charter", adminUser.getId());
            entityManager.flush();
        }

        @Test
        @DisplayName("GET /api/v1/teams/{teamId} retrieves team for active member")
        void getTeam_activeMember_returns200() throws Exception {
            mockMvc.perform(get("/api/v1/teams/{teamId}", team.getId())
                            .with(user(new DevFlowUserDetails(memberUser1))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(team.getId().toString()))
                    .andExpect(jsonPath("$.name").value("Platform Team"))
                    .andExpect(jsonPath("$.workspaceId").value(publicWorkspace.getId().toString()));
        }

        @Test
        @DisplayName("PATCH /api/v1/teams/{teamId} updates name and description")
        void updateTeam_adminOrLead_returns200() throws Exception {
            UpdateTeamRequest request = UpdateTeamRequest.builder()
                    .name("Platform Infrastructure")
                    .description("Updated charter")
                    .build();

            mockMvc.perform(patch("/api/v1/teams/{teamId}", team.getId())
                            .with(user(new DevFlowUserDetails(adminUser)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Platform Infrastructure"))
                    .andExpect(jsonPath("$.description").value("Updated charter"));

            entityManager.flush();
            Team reloaded = teamRepository.findById(team.getId()).orElseThrow();
            assertThat(reloaded.getName()).isEqualTo("Platform Infrastructure");
            assertThat(reloaded.getDescription()).isEqualTo("Updated charter");
        }

        @Test
        @DisplayName("POST /api/v1/teams/{teamId}/archive transitions team to ARCHIVED")
        void archiveTeam_success() throws Exception {
            mockMvc.perform(post("/api/v1/teams/{teamId}/archive", team.getId())
                            .with(user(new DevFlowUserDetails(adminUser))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ARCHIVED"));

            entityManager.flush();
            Team reloaded = teamRepository.findById(team.getId()).orElseThrow();
            assertThat(reloaded.isArchived()).isTrue();

            // Subsequent mutation is rejected
            UpdateTeamRequest updateRequest = UpdateTeamRequest.builder().name("Renamed").build();
            mockMvc.perform(patch("/api/v1/teams/{teamId}", team.getId())
                            .with(user(new DevFlowUserDetails(adminUser)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("user from another organization receives 403 Forbidden on team access")
        void crossOrgAccess_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/teams/{teamId}", team.getId())
                            .with(user(new DevFlowUserDetails(otherOrgUser))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // ── 3. Team Membership & Leadership APIs ───────────────────────────────────

    @Nested
    @DisplayName("Team Membership & Leadership Endpoints")
    class TeamMembershipIntegration {

        private Team team;

        @BeforeEach
        void setupTeam() {
            team = teamService.createTeam(
                    orgA.getId(), publicWorkspace.getId(), "Data Platform", "Big data pipelines", adminUser.getId());
            entityManager.flush();
        }

        @Test
        @DisplayName("add member, list members, transfer lead, remove member, and leave flow")
        void completeMembershipLifecycle() throws Exception {
            // 1. Add memberUser1 to team
            AddTeamMemberRequest addRequest = AddTeamMemberRequest.builder()
                    .userId(memberUser1.getId())
                    .build();

            mockMvc.perform(post("/api/v1/teams/{teamId}/members", team.getId())
                            .with(user(new DevFlowUserDetails(adminUser)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(addRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.userId").value(memberUser1.getId().toString()))
                    .andExpect(jsonPath("$.role").value("MEMBER"))
                    .andExpect(jsonPath("$.email").value(memberUser1.getEmail()));

            entityManager.flush();
            assertThat(teamMemberRepository.existsByTeamIdAndUserId(team.getId(), memberUser1.getId())).isTrue();

            // 2. Add memberUser2 to team
            teamService.addMember(team.getId(), memberUser2.getId(), adminUser.getId());
            entityManager.flush();

            // 3. List members
            mockMvc.perform(get("/api/v1/teams/{teamId}/members", team.getId())
                            .with(user(new DevFlowUserDetails(memberUser1))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3));

            // 4. Get team lead
            mockMvc.perform(get("/api/v1/teams/{teamId}/lead", team.getId())
                            .with(user(new DevFlowUserDetails(memberUser1))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(adminUser.getId().toString()))
                    .andExpect(jsonPath("$.role").value("LEAD"));

            // 5. Transfer leadership to memberUser1
            TransferTeamLeadershipRequest transferRequest = TransferTeamLeadershipRequest.builder()
                    .userId(memberUser1.getId())
                    .build();

            mockMvc.perform(put("/api/v1/teams/{teamId}/lead", team.getId())
                            .with(user(new DevFlowUserDetails(adminUser)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(transferRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(memberUser1.getId().toString()))
                    .andExpect(jsonPath("$.role").value("LEAD"));

            entityManager.flush();

            // Verify database state: memberUser1 is now LEAD, adminUser is now MEMBER
            TeamMember newLead = teamMemberRepository.findByTeamIdAndUserId(team.getId(), memberUser1.getId()).orElseThrow();
            TeamMember demotedLead = teamMemberRepository.findByTeamIdAndUserId(team.getId(), adminUser.getId()).orElseThrow();
            assertThat(newLead.isLead()).isTrue();
            assertThat(demotedLead.getRole()).isEqualTo(TeamRole.MEMBER);

            // 6. MemberUser2 leaves team
            mockMvc.perform(post("/api/v1/teams/{teamId}/leave", team.getId())
                            .with(user(new DevFlowUserDetails(memberUser2))))
                    .andExpect(status().isNoContent());

            entityManager.flush();
            assertThat(teamMemberRepository.existsByTeamIdAndUserId(team.getId(), memberUser2.getId())).isFalse();

            // 7. Attempting to remove active lead (memberUser1) returns 400 Bad Request
            mockMvc.perform(delete("/api/v1/teams/{teamId}/members/{userId}", team.getId(), memberUser1.getId())
                            .with(user(new DevFlowUserDetails(adminUser))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            // 8. Lead removes adminUser (now a regular member) -> returns 204 No Content
            mockMvc.perform(delete("/api/v1/teams/{teamId}/members/{userId}", team.getId(), adminUser.getId())
                            .with(user(new DevFlowUserDetails(memberUser1))))
                    .andExpect(status().isNoContent());

            entityManager.flush();
            assertThat(teamMemberRepository.existsByTeamIdAndUserId(team.getId(), adminUser.getId())).isFalse();
        }

        @Test
        @DisplayName("adding suspended member returns 400 Bad Request")
        void addMember_suspended_returns400() throws Exception {
            AddTeamMemberRequest addRequest = AddTeamMemberRequest.builder()
                    .userId(suspendedUser.getId())
                    .build();

            mockMvc.perform(post("/api/v1/teams/{teamId}/members", team.getId())
                            .with(user(new DevFlowUserDetails(adminUser)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(addRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("adding user from another organization returns 404 Not Found")
        void addMember_otherOrgUser_returns404() throws Exception {
            AddTeamMemberRequest addRequest = AddTeamMemberRequest.builder()
                    .userId(otherOrgUser.getId())
                    .build();

            mockMvc.perform(post("/api/v1/teams/{teamId}/members", team.getId())
                            .with(user(new DevFlowUserDetails(adminUser)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(addRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }
}

