package com.devflow.role.controller;

import com.devflow.modules.auth.domain.Role;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.domain.OrganizationRoleAssignment;
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

import java.time.Instant;
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
@DisplayName("Stage 6: Organization Role Management REST API Tests")
class OrganizationRoleControllerIntegrationTest {

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
    private User devA;
    private User devOther;
    private User userB;

    private Organization orgA;
    private Organization orgB;

    @BeforeEach
    void setUp() {
        ownerA = createUser("owner-a");
        adminA = createUser("admin-a");
        devA = createUser("dev-a");
        devOther = createUser("dev-other");
        userB = createUser("user-b");

        orgA = organizationRepository.save(Organization.builder()
                .name("Alpha Org")
                .slug("alpha-" + UUID.randomUUID())
                .owner(ownerA)
                .build());

        orgB = organizationRepository.save(Organization.builder()
                .name("Beta Org")
                .slug("beta-" + UUID.randomUUID())
                .owner(userB)
                .build());

        createOrgMember(orgA, ownerA, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);
        createOrgMember(orgA, adminA, OrganizationRole.ADMIN, OrganizationMembershipStatus.ACTIVE);
        createOrgMember(orgA, devA, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.ACTIVE);
        createOrgMember(orgA, devOther, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.ACTIVE);
        createOrgMember(orgB, userB, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);

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

    // ── 1. GET /api/v1/organizations/{orgId}/roles ────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/organizations/{organizationId}/roles")
    class ListOrganizationRoles {

        @Test
        @DisplayName("Admin can list organization role assignments successfully")
        void admin_canListOrganizationRoles() throws Exception {
            roleAssignmentService.assignOrganizationRole(
                    orgA.getId(), devA.getId(), Role.ORGANIZATION_ADMIN, ownerA.getId(), null);
            entityManager.flush();
            entityManager.clear();

            mockMvc.perform(get("/api/v1/organizations/{orgId}/roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(adminA))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].organizationId").value(orgA.getId().toString()))
                    .andExpect(jsonPath("$[0].role").value("ORGANIZATION_ADMIN"))
                    .andExpect(jsonPath("$[0].active").value(true));
        }

        @Test
        @DisplayName("Regular organization developer cannot list roles (403 Forbidden)")
        void developer_cannotListOrganizationRoles() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{orgId}/roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(devA))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("Unauthenticated request returns HTTP 401")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{orgId}/roles", orgA.getId()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("Cross-tenant user from Org B receives HTTP 403")
        void crossTenant_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{orgId}/roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(userB))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // ── 2. POST /api/v1/organizations/{orgId}/roles ───────────────────────────

    @Nested
    @DisplayName("POST /api/v1/organizations/{organizationId}/roles")
    class GrantOrganizationRole {

        @Test
        @DisplayName("Admin can grant ORGANIZATION_ADMIN to an active member")
        void admin_grantsOrgAdmin_success() throws Exception {
            GrantRoleRequest request = GrantRoleRequest.builder()
                    .userId(devA.getId())
                    .role("ORGANIZATION_ADMIN")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.role").value("ORGANIZATION_ADMIN"))
                    .andExpect(jsonPath("$.userId").value(devA.getId().toString()))
                    .andExpect(jsonPath("$.organizationId").value(orgA.getId().toString()))
                    .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        @DisplayName("Owner can grant ORGANIZATION_BILLING to an active member")
        void owner_grantsOrgBilling_success() throws Exception {
            GrantRoleRequest request = GrantRoleRequest.builder()
                    .userId(devA.getId())
                    .role("ORGANIZATION_BILLING")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(ownerA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.role").value("ORGANIZATION_BILLING"))
                    .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        @DisplayName("Admin attempting to grant ORGANIZATION_BILLING is strictly denied (403 Forbidden)")
        void admin_cannotGrantOrgBilling() throws Exception {
            GrantRoleRequest request = GrantRoleRequest.builder()
                    .userId(devA.getId())
                    .role("ORGANIZATION_BILLING")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Only ORGANIZATION_OWNER can grant ORGANIZATION_BILLING")));
        }

        @Test
        @DisplayName("Admin attempting to grant ORGANIZATION_OWNER is strictly denied (403 Forbidden)")
        void admin_cannotGrantOrgOwner() throws Exception {
            GrantRoleRequest request = GrantRoleRequest.builder()
                    .userId(devA.getId())
                    .role("ORGANIZATION_OWNER")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("Platform role cannot be assigned through organization role API (400 Bad Request)")
        void platformRole_rejected() throws Exception {
            GrantRoleRequest request = GrantRoleRequest.builder()
                    .userId(devA.getId())
                    .role("PLATFORM_ADMIN")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(ownerA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Platform roles cannot be assigned")));
        }

        @Test
        @DisplayName("Project role cannot be assigned through organization role API (400 Bad Request)")
        void projectRole_rejected() throws Exception {
            GrantRoleRequest request = GrantRoleRequest.builder()
                    .userId(devA.getId())
                    .role("PROJECT_LEAD")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(ownerA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("Duplicate active role grant returns 409 Conflict")
        void duplicateRoleGrant_returns409() throws Exception {
            roleAssignmentService.assignOrganizationRole(
                    orgA.getId(), devA.getId(), Role.ORGANIZATION_ADMIN, ownerA.getId(), null);
            entityManager.flush();
            entityManager.clear();

            GrantRoleRequest request = GrantRoleRequest.builder()
                    .userId(devA.getId())
                    .role("ORGANIZATION_ADMIN")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(ownerA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("Granting role to non-member user returns 400 Bad Request")
        void nonMember_returns400() throws Exception {
            GrantRoleRequest request = GrantRoleRequest.builder()
                    .userId(userB.getId()) // member of orgB, not orgA
                    .role("ORGANIZATION_ADMIN")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(ownerA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    // ── 3. DELETE /api/v1/organizations/{orgId}/roles/{assignmentId} ──────────

    @Nested
    @DisplayName("DELETE /api/v1/organizations/{organizationId}/roles/{assignmentId}")
    class RevokeOrganizationRole {

        @Test
        @DisplayName("Admin can revoke an active role assignment successfully")
        void admin_revokesRoleAssignment_success() throws Exception {
            OrganizationRoleAssignment assignment = roleAssignmentService.assignOrganizationRole(
                    orgA.getId(), devA.getId(), Role.ORGANIZATION_ADMIN, ownerA.getId(), null);
            entityManager.flush();
            entityManager.clear();

            RevokeRoleRequest request = RevokeRoleRequest.builder()
                    .reason("Quarterly access review")
                    .build();

            mockMvc.perform(delete("/api/v1/organizations/{orgId}/roles/{assignmentId}",
                            orgA.getId(), assignment.getId())
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNoContent());

            entityManager.flush();
            entityManager.clear();

            Set<Role> roles = roleAssignmentService.getActiveOrganizationRoles(orgA.getId(), devA.getId());
            assertThat(roles).doesNotContain(Role.ORGANIZATION_ADMIN);
        }

        @Test
        @DisplayName("Attempting to revoke sole active owner returns 403 Forbidden")
        void revokeSoleOwner_returns403() throws Exception {
            OrganizationRoleAssignment ownerAssignment = roleAssignmentService.assignOrganizationRole(
                    orgA.getId(), ownerA.getId(), Role.ORGANIZATION_OWNER, ownerA.getId(), null);
            entityManager.flush();
            entityManager.clear();

            mockMvc.perform(delete("/api/v1/organizations/{orgId}/roles/{assignmentId}",
                            orgA.getId(), ownerAssignment.getId())
                            .with(user(new DevFlowUserDetails(ownerA))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Cannot revoke sole active ORGANIZATION_OWNER")));
        }

        @Test
        @DisplayName("Revoking non-existent assignment ID returns 404 Not Found")
        void nonExistentAssignment_returns404() throws Exception {
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/roles/{assignmentId}",
                            orgA.getId(), UUID.randomUUID())
                            .with(user(new DevFlowUserDetails(ownerA))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }
}
