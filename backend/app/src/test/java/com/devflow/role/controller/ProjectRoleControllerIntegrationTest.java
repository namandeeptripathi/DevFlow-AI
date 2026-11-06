package com.devflow.role.controller;

import com.devflow.modules.auth.domain.Role;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.domain.ProjectRoleAssignment;
import com.devflow.role.dto.GrantRoleRequest;
import com.devflow.role.dto.RevokeRoleRequest;
import com.devflow.role.service.RoleAssignmentService;
import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
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

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Stage 6: Project Role Management REST API Tests")
class ProjectRoleControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private User ownerA;
    private User adminA;
    private User leadA;
    private User devA;
    private User userB;

    private Organization orgA;
    private UUID projectA1;

    @BeforeEach
    void setUp() {
        ownerA = createUser("owner-a");
        adminA = createUser("admin-a");
        leadA = createUser("lead-a");
        devA = createUser("dev-a");
        userB = createUser("user-b");

        orgA = organizationRepository.save(Organization.builder()
                .name("Alpha Org")
                .slug("alpha-" + UUID.randomUUID())
                .owner(ownerA)
                .build());

        createOrgMember(orgA, ownerA, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);
        createOrgMember(orgA, adminA, OrganizationRole.ADMIN, OrganizationMembershipStatus.ACTIVE);
        createOrgMember(orgA, leadA, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.ACTIVE);
        createOrgMember(orgA, devA, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.ACTIVE);

        projectA1 = UUID.randomUUID();

        // Assign PROJECT_LEAD in projectA1 to leadA
        roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, leadA.getId(), Role.PROJECT_LEAD, adminA.getId(), null);

        entityManager.flush();
        entityManager.clear();
    }

    private User createUser(String prefix) {
        return userRepository.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@devflow.io")
                .username(prefix + "_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());
    }

    private OrganizationMember createOrgMember(
            Organization org, User user, OrganizationRole role, OrganizationMembershipStatus status) {
        return organizationMemberRepository.save(OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(role)
                .status(status)
                .build());
    }

    // ── 1. GET /api/v1/organizations/{orgId}/projects/{projId}/roles ──────────

    @Nested
    @DisplayName("GET /api/v1/organizations/{organizationId}/projects/{projectId}/roles")
    class ListProjectRoles {

        @Test
        @DisplayName("Project lead can list project role assignments successfully")
        void projectLead_canListProjectRoles() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{orgId}/projects/{projId}/roles",
                            orgA.getId(), projectA1)
                            .with(user(new DevFlowUserDetails(leadA))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].projectId").value(projectA1.toString()))
                    .andExpect(jsonPath("$[0].role").value("PROJECT_LEAD"))
                    .andExpect(jsonPath("$[0].active").value(true));
        }

        @Test
        @DisplayName("Non-member of project or org is denied listing project roles (403 Forbidden)")
        void outsider_cannotListProjectRoles() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{orgId}/projects/{projId}/roles",
                            orgA.getId(), projectA1)
                            .with(user(new DevFlowUserDetails(userB))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // ── 2. POST /api/v1/organizations/{orgId}/projects/{projId}/roles ─────────

    @Nested
    @DisplayName("POST /api/v1/organizations/{organizationId}/projects/{projectId}/roles")
    class GrantProjectRole {

        @Test
        @DisplayName("Project lead can grant PROJECT_MEMBER to an active organization member")
        void projectLead_grantsProjectMember_success() throws Exception {
            GrantRoleRequest request = GrantRoleRequest.builder()
                    .userId(devA.getId())
                    .role("PROJECT_MEMBER")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/projects/{projId}/roles",
                            orgA.getId(), projectA1)
                            .with(user(new DevFlowUserDetails(leadA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.role").value("PROJECT_MEMBER"))
                    .andExpect(jsonPath("$.projectId").value(projectA1.toString()))
                    .andExpect(jsonPath("$.userId").value(devA.getId().toString()))
                    .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        @DisplayName("Organization admin can grant PROJECT_VIEWER to a member")
        void admin_grantsProjectViewer_success() throws Exception {
            GrantRoleRequest request = GrantRoleRequest.builder()
                    .userId(devA.getId())
                    .role("PROJECT_VIEWER")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/projects/{projId}/roles",
                            orgA.getId(), projectA1)
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.role").value("PROJECT_VIEWER"))
                    .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        @DisplayName("Cannot assign organization role to a project (400 Bad Request)")
        void organizationRole_rejected() throws Exception {
            GrantRoleRequest request = GrantRoleRequest.builder()
                    .userId(devA.getId())
                    .role("ORGANIZATION_ADMIN")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/projects/{projId}/roles",
                            orgA.getId(), projectA1)
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("Cannot assign platform role to a project (400 Bad Request)")
        void platformRole_rejected() throws Exception {
            GrantRoleRequest request = GrantRoleRequest.builder()
                    .userId(devA.getId())
                    .role("PLATFORM_ADMIN")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/projects/{projId}/roles",
                            orgA.getId(), projectA1)
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("Duplicate project role grant returns 409 Conflict")
        void duplicateRole_returns409() throws Exception {
            GrantRoleRequest request = GrantRoleRequest.builder()
                    .userId(leadA.getId())
                    .role("PROJECT_LEAD")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/projects/{projId}/roles",
                            orgA.getId(), projectA1)
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }
    }

    // ── 3. DELETE /api/v1/organizations/{orgId}/projects/{projId}/roles/{id} ──

    @Nested
    @DisplayName("DELETE /api/v1/organizations/{organizationId}/projects/{projectId}/roles/{assignmentId}")
    class RevokeProjectRole {

        @Test
        @DisplayName("Project lead can revoke project role assignment successfully")
        void projectLead_revokesAssignment_success() throws Exception {
            ProjectRoleAssignment memberAssignment = roleAssignmentService.assignProjectRole(
                    orgA.getId(), projectA1, devA.getId(), Role.PROJECT_MEMBER, leadA.getId(), null);
            entityManager.flush();
            entityManager.clear();

            RevokeRoleRequest request = RevokeRoleRequest.builder()
                    .reason("Completed milestone")
                    .build();

            mockMvc.perform(delete("/api/v1/organizations/{orgId}/projects/{projId}/roles/{assignmentId}",
                            orgA.getId(), projectA1, memberAssignment.getId())
                            .with(user(new DevFlowUserDetails(leadA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNoContent());

            entityManager.flush();
            entityManager.clear();

            Set<Role> roles = roleAssignmentService.getActiveProjectRoles(orgA.getId(), projectA1, devA.getId());
            assertThat(roles).doesNotContain(Role.PROJECT_MEMBER);
        }

        @Test
        @DisplayName("Revoking non-existent project assignment ID returns 404 Not Found")
        void nonExistentAssignment_returns404() throws Exception {
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/projects/{projId}/roles/{assignmentId}",
                            orgA.getId(), projectA1, UUID.randomUUID())
                            .with(user(new DevFlowUserDetails(adminA))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }
}

