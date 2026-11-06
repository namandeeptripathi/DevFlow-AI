package com.devflow.authorization;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.dto.UpdateOrganizationRequest;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.security.authorization.DevFlowSecurityService;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 5 integration test suite verifying Spring Security integration with the centralized
 * authorization architecture.
 *
 * <h2>Coverage Invariants</h2>
 * <ul>
 *   <li>Unauthenticated requests receive HTTP 401 with standard error JSON.</li>
 *   <li>Authenticated permitted requests receive HTTP 200/204.</li>
 *   <li>Authenticated denied requests receive HTTP 403 Forbidden with standard error JSON.</li>
 *   <li>Client-forged user IDs or role headers are strictly ignored in favor of the trusted principal.</li>
 *   <li>Cross-tenant requests fail closed and receive HTTP 403 without data leakage.</li>
 *   <li>Declarative {@code @PreAuthorize("@authz.hasOrgPermission(...)")} enforces authorization.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(SpringSecurityAuthorizationIntegrationTest.TestSecurityMethodService.class)
@DisplayName("Stage 5: Spring Security Authorization Integration Tests")
class SpringSecurityAuthorizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private DevFlowSecurityService devFlowSecurityService;

    @Autowired
    private TestSecurityMethodService testSecurityMethodService;

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
                .name("Tenant Alpha")
                .slug("tenant-alpha-" + UUID.randomUUID())
                .owner(ownerA)
                .build());

        orgB = organizationRepository.save(Organization.builder()
                .name("Tenant Beta")
                .slug("tenant-beta-" + UUID.randomUUID())
                .owner(userB)
                .build());

        createOrgMembership(orgA, ownerA, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);
        createOrgMembership(orgA, adminA, OrganizationRole.ADMIN, OrganizationMembershipStatus.ACTIVE);
        createOrgMembership(orgA, devA, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.ACTIVE);
        createOrgMembership(orgB, userB, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);

        entityManager.flush();
        entityManager.clear();
    }

    private User createUser(String prefix) {
        return userRepository.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@devflow.io")
                .username(prefix + "_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_password")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());
    }

    private OrganizationMember createOrgMembership(
            Organization organization, User user, OrganizationRole role, OrganizationMembershipStatus status) {
        return organizationMemberRepository.save(OrganizationMember.builder()
                .organization(organization)
                .user(user)
                .role(role)
                .status(status)
                .build());
    }

    // ── 1. Unauthenticated Requests ───────────────────────────────────────────

    @Nested
    @DisplayName("Unauthenticated Requests")
    class UnauthenticatedRequests {

        @Test
        @DisplayName("Unauthenticated request returns HTTP 401 with standard error JSON")
        void unauthenticatedRequest_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{id}", orgA.getId())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.error").value("Unauthorized"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.path").value("/api/v1/organizations/" + orgA.getId()));
        }
    }

    // ── 2. Authenticated Permitted Requests ───────────────────────────────────

    @Nested
    @DisplayName("Authenticated Permitted Requests")
    class AuthenticatedPermittedRequests {

        @Test
        @DisplayName("Active organization member can read organization details")
        void activeMember_canReadOrganization_returns200() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{id}", orgA.getId())
                            .with(user(new DevFlowUserDetails(devA)))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(orgA.getId().toString()))
                    .andExpect(jsonPath("$.name").value("Tenant Alpha"));
        }

        @Test
        @DisplayName("Organization admin can update organization details")
        void admin_canUpdateOrganization_returns200() throws Exception {
            UpdateOrganizationRequest request = UpdateOrganizationRequest.builder()
                    .name("Tenant Alpha Updated")
                    .description("Updated description")
                    .build();

            mockMvc.perform(patch("/api/v1/organizations/{id}", orgA.getId())
                            .with(user(new DevFlowUserDetails(adminA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Tenant Alpha Updated"));
        }

        @Test
        @DisplayName("Organization owner can delete organization")
        void owner_canDeleteOrganization_returns204() throws Exception {
            mockMvc.perform(delete("/api/v1/organizations/{id}", orgA.getId())
                            .with(user(new DevFlowUserDetails(ownerA))))
                    .andExpect(status().isNoContent());
        }
    }

    // ── 3. Authenticated Denied Requests ──────────────────────────────────────

    @Nested
    @DisplayName("Authenticated Denied Requests")
    class AuthenticatedDeniedRequests {

        @Test
        @DisplayName("Organization ADMIN is denied deleting organization (owner-only permission) - returns HTTP 403")
        void admin_cannotDeleteOrganization_returns403() throws Exception {
            mockMvc.perform(delete("/api/v1/organizations/{id}", orgA.getId())
                            .with(user(new DevFlowUserDetails(adminA))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"))
                    .andExpect(jsonPath("$.path").value("/api/v1/organizations/" + orgA.getId()));
        }

        @Test
        @DisplayName("Organization DEVELOPER is denied updating organization (admin-only permission) - returns HTTP 403")
        void developer_cannotUpdateOrganization_returns403() throws Exception {
            UpdateOrganizationRequest request = UpdateOrganizationRequest.builder()
                    .name("Hacked Name")
                    .build();

            mockMvc.perform(patch("/api/v1/organizations/{id}", orgA.getId())
                            .with(user(new DevFlowUserDetails(devA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }
    }

    // ── 4. Forged Caller Attributes ───────────────────────────────────────────

    @Nested
    @DisplayName("Caller-Supplied Attribute Tampering Prevention")
    class AttributeTamperingPrevention {

        @Test
        @DisplayName("Client header claiming to be OWNER is ignored; authenticated DEVELOPER is rejected")
        void forgedRoleHeader_isIgnored() throws Exception {
            mockMvc.perform(delete("/api/v1/organizations/{id}", orgA.getId())
                            .with(user(new DevFlowUserDetails(devA)))
                            .header("X-Role", "OWNER")
                            .header("X-User-Id", ownerA.getId().toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("Security context subject is strictly derived from DevFlowUserDetails")
        void securitySubject_derivedExclusivelyFromPrincipal() {
            DevFlowUserDetails userDetails = new DevFlowUserDetails(devA);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

            try {
                // devA does not hold org.manage or member.invite
                boolean canManage = devFlowSecurityService.hasOrgPermission(orgA.getId(), "member.invite");
                assertThat(canManage).isFalse();

                // switch context to ownerA
                DevFlowUserDetails ownerDetails = new DevFlowUserDetails(ownerA);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(ownerDetails, null, ownerDetails.getAuthorities()));

                boolean ownerCanManage = devFlowSecurityService.hasOrgPermission(orgA.getId(), "member.invite");
                assertThat(ownerCanManage).isTrue();
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    // ── 5. Cross-Tenant Request Rejection ─────────────────────────────────────

    @Nested
    @DisplayName("Cross-Tenant Isolation")
    class CrossTenantIsolation {

        @Test
        @DisplayName("User from Tenant Beta attempting to access Tenant Alpha receives HTTP 403")
        void crossTenantAccess_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{id}", orgA.getId())
                            .with(user(new DevFlowUserDetails(userB)))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }
    }

    // ── 6. Declarative Method Security (@PreAuthorize("@authz...")) ───────────

    @Nested
    @DisplayName("Declarative Method Security Integration")
    class DeclarativeMethodSecurity {

        @Test
        @DisplayName("@PreAuthorize allows execution when user holds canonical permission")
        void preAuthorize_allowsPermittedUser() {
            DevFlowUserDetails adminDetails = new DevFlowUserDetails(adminA);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(adminDetails, null, adminDetails.getAuthorities()));

            try {
                String result = testSecurityMethodService.executeAdminAction(orgA.getId());
                assertThat(result).isEqualTo("admin-success");
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        @Test
        @DisplayName("@PreAuthorize throws AccessDeniedException when user lacks canonical permission")
        void preAuthorize_deniesUnauthorizedUser() {
            DevFlowUserDetails devDetails = new DevFlowUserDetails(devA);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(devDetails, null, devDetails.getAuthorities()));

            try {
                assertThatThrownBy(() -> testSecurityMethodService.executeAdminAction(orgA.getId()))
                        .isInstanceOf(AccessDeniedException.class);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Service
    static class TestSecurityMethodService {

        @PreAuthorize("@authz.hasOrgPermission(#orgId, 'member.invite')")
        public String executeAdminAction(UUID orgId) {
            return "admin-success";
        }
    }
}
