package com.devflow.role.controller;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.domain.CustomRoleAssignment;
import com.devflow.role.domain.CustomRoleEntity;
import com.devflow.role.dto.CreateCustomRoleRequest;
import com.devflow.role.dto.UpdateCustomRoleRequest;
import com.devflow.role.repository.CustomRoleRepository;
import com.devflow.role.service.CustomRoleService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Stage 7: Custom Role Management REST API Integration Tests")
class CustomRoleControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomRoleService customRoleService;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private CustomRoleRepository customRoleRepository;

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
    private User userB;

    private Organization orgA;
    private Organization orgB;

    @BeforeEach
    void setUp() {
        ownerA = createUser("owner-a");
        adminA = createUser("admin-a");
        devA = createUser("dev-a");
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

    @Nested
    @DisplayName("GET /api/v1/organizations/{organizationId}/custom-roles")
    class ListCustomRolesTests {

        @Test
        @DisplayName("Admin can list organization custom roles successfully")
        void adminCanListCustomRoles() throws Exception {
            CreateCustomRoleRequest request = CreateCustomRoleRequest.builder()
                    .name("QA_ENGINEER")
                    .displayName("QA Engineer")
                    .permissions(Set.of("project.read", "task.read"))
                    .build();
            customRoleService.createCustomRole(orgA.getId(), adminA.getId(), request);

            entityManager.flush();
            entityManager.clear();

            mockMvc.perform(get("/api/v1/organizations/{orgId}/custom-roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(adminA))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].name").value("QA_ENGINEER"))
                    .andExpect(jsonPath("$[0].displayName").value("QA Engineer"))
                    .andExpect(jsonPath("$[0].organizationId").value(orgA.getId().toString()))
                    .andExpect(jsonPath("$[0].permissions").isArray());
        }

        @Test
        @DisplayName("Unauthenticated request returns 401 Unauthorized")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{orgId}/custom-roles", orgA.getId()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Regular developer member cannot list custom roles (403 Forbidden)")
        void developerCannotListCustomRoles() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{orgId}/custom-roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(devA))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Cross-tenant user from Org B receives 403 Forbidden")
        void crossTenantUserReceives403() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{orgId}/custom-roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(userB))))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/organizations/{organizationId}/custom-roles")
    class CreateCustomRoleTests {

        @Test
        @DisplayName("Admin can create a custom role with delegable permissions (201 Created)")
        void adminCanCreateCustomRole() throws Exception {
            CreateCustomRoleRequest request = CreateCustomRoleRequest.builder()
                    .name("RELEASE_MANAGER")
                    .displayName("Release Manager")
                    .description("Oversees production releases")
                    .permissions(Set.of("project.read", "cycle.manage"))
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/custom-roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("RELEASE_MANAGER"))
                    .andExpect(jsonPath("$.displayName").value("Release Manager"))
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.organizationId").value(orgA.getId().toString()))
                    .andExpect(jsonPath("$.permissions").isArray());
        }

        @Test
        @DisplayName("Duplicate custom role name returns 409 Conflict")
        void duplicateRoleNameReturns409() throws Exception {
            CreateCustomRoleRequest request = CreateCustomRoleRequest.builder()
                    .name("SECURITY_REVIEWER")
                    .displayName("Security Reviewer")
                    .permissions(Set.of("project.read"))
                    .build();

            customRoleService.createCustomRole(orgA.getId(), adminA.getId(), request);

            mockMvc.perform(post("/api/v1/organizations/{orgId}/custom-roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("Unknown canonical permission returns 400 Bad Request")
        void unknownPermissionReturns400() throws Exception {
            CreateCustomRoleRequest request = CreateCustomRoleRequest.builder()
                    .name("HACKER_ROLE")
                    .displayName("Hacker Role")
                    .permissions(Set.of("nonexistent.perm"))
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/custom-roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("Platform-scoped permission returns 400 Bad Request")
        void platformPermissionReturns400() throws Exception {
            CreateCustomRoleRequest request = CreateCustomRoleRequest.builder()
                    .name("PLATFORM_ESCALATION")
                    .displayName("Platform Escalation")
                    .permissions(Set.of("platform.audit.read"))
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/custom-roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("Admin attempting to delegate unpossessed permission returns 403 Forbidden")
        void adminDelegatingUnpossessedPermissionReturns403() throws Exception {
            CreateCustomRoleRequest request = CreateCustomRoleRequest.builder()
                    .name("BILLING_ESCALATION")
                    .displayName("Billing Escalation")
                    .permissions(Set.of("billing.manage"))
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/custom-roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("Owner can create custom role delegating billing.manage (201 Created)")
        void ownerCanDelegateBillingManage() throws Exception {
            CreateCustomRoleRequest request = CreateCustomRoleRequest.builder()
                    .name("BILLING_COORDINATOR")
                    .displayName("Billing Coordinator")
                    .permissions(Set.of("billing.view", "billing.manage"))
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{orgId}/custom-roles", orgA.getId())
                            .with(user(new DevFlowUserDetails(ownerA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("BILLING_COORDINATOR"));
        }
    }

    @Nested
    @DisplayName("GET & PUT /api/v1/organizations/{organizationId}/custom-roles/{roleId}")
    class GetAndUpdateCustomRoleTests {

        @Test
        @DisplayName("Admin can retrieve custom role details (200 OK)")
        void adminCanGetCustomRole() throws Exception {
            CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                    .name("CODE_AUDITOR")
                    .displayName("Code Auditor")
                    .permissions(Set.of("repo.scan", "repo.read"))
                    .build();
            CustomRoleEntity role = customRoleService.createCustomRole(orgA.getId(), adminA.getId(), createRequest);

            mockMvc.perform(get("/api/v1/organizations/{orgId}/custom-roles/{roleId}", orgA.getId(), role.getId())
                            .with(user(new DevFlowUserDetails(adminA))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(role.getId().toString()))
                    .andExpect(jsonPath("$.name").value("CODE_AUDITOR"));
        }

        @Test
        @DisplayName("Admin can update custom role display name, active state, and permissions (200 OK)")
        void adminCanUpdateCustomRole() throws Exception {
            CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                    .name("OPS_LEAD")
                    .displayName("Ops Lead")
                    .permissions(Set.of("project.read"))
                    .build();
            CustomRoleEntity role = customRoleService.createCustomRole(orgA.getId(), adminA.getId(), createRequest);

            UpdateCustomRoleRequest updateRequest = UpdateCustomRoleRequest.builder()
                    .displayName("Senior Ops Lead")
                    .description("Updated description")
                    .active(false)
                    .permissions(Set.of("project.read", "project.write"))
                    .build();

            mockMvc.perform(put("/api/v1/organizations/{orgId}/custom-roles/{roleId}", orgA.getId(), role.getId())
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.displayName").value("Senior Ops Lead"))
                    .andExpect(jsonPath("$.active").value(false));
        }

        @Test
        @DisplayName("Retrieving non-existent role ID returns 404 Not Found")
        void nonExistentRoleReturns404() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{orgId}/custom-roles/{roleId}", orgA.getId(), UUID.randomUUID())
                            .with(user(new DevFlowUserDetails(adminA))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/organizations/{organizationId}/custom-roles/{roleId}")
    class DeleteCustomRoleTests {

        @Test
        @DisplayName("Admin can delete unused custom role (204 No Content)")
        void adminCanDeleteUnusedRole() throws Exception {
            CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                    .name("TO_BE_DELETED")
                    .displayName("To Be Deleted")
                    .permissions(Set.of("project.read"))
                    .build();
            CustomRoleEntity role = customRoleService.createCustomRole(orgA.getId(), adminA.getId(), createRequest);

            mockMvc.perform(delete("/api/v1/organizations/{orgId}/custom-roles/{roleId}", orgA.getId(), role.getId())
                            .with(user(new DevFlowUserDetails(adminA))))
                    .andExpect(status().isNoContent());

            assertThat(customRoleRepository.findById(role.getId())).isEmpty();
        }

        @Test
        @DisplayName("Deleting role with active assignments returns 409 Conflict")
        void deleteRoleWithActiveAssignmentsReturns409() throws Exception {
            CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                    .name("ACTIVE_ROLE")
                    .displayName("Active Role")
                    .permissions(Set.of("project.read"))
                    .build();
            CustomRoleEntity role = customRoleService.createCustomRole(orgA.getId(), adminA.getId(), createRequest);

            roleAssignmentService.assignCustomRole(orgA.getId(), devA.getId(), role.getId(), adminA.getId(), null);

            mockMvc.perform(delete("/api/v1/organizations/{orgId}/custom-roles/{roleId}", orgA.getId(), role.getId())
                            .with(user(new DevFlowUserDetails(adminA))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("Deleting role with only historical assignments safely deactivates it (204 No Content)")
        void deleteRoleWithHistoricalAssignmentsSafelyDeactivates() throws Exception {
            CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                    .name("HISTORICAL_ROLE")
                    .displayName("Historical Role")
                    .permissions(Set.of("project.read"))
                    .build();
            CustomRoleEntity role = customRoleService.createCustomRole(orgA.getId(), adminA.getId(), createRequest);

            CustomRoleAssignment assignment = roleAssignmentService.assignCustomRole(
                    orgA.getId(), devA.getId(), role.getId(), adminA.getId(), null
            );
            roleAssignmentService.revokeCustomRoleAssignment(orgA.getId(), assignment.getId(), adminA.getId(), "Done");

            entityManager.flush();
            entityManager.clear();

            mockMvc.perform(delete("/api/v1/organizations/{orgId}/custom-roles/{roleId}", orgA.getId(), role.getId())
                            .with(user(new DevFlowUserDetails(adminA))))
                    .andExpect(status().isNoContent());

            CustomRoleEntity deactivated = customRoleRepository.findById(role.getId()).orElseThrow();
            assertThat(deactivated.isActive()).isFalse();
        }
    }
}
