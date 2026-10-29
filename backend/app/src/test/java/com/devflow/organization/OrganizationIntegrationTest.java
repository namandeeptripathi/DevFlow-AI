package com.devflow.organization;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationInvitation;
import com.devflow.organization.domain.OrganizationInvitationStatus;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.dto.CreateOrganizationRequest;
import com.devflow.organization.exception.LastOrganizationOwnerRemovalException;
import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.repository.OrganizationInvitationRepository;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.organization.service.OrganizationInvitationService;
import com.devflow.organization.service.OrganizationMembershipService;
import com.devflow.organization.service.OrganizationService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Organization Module End-to-End Integration Tests")
class OrganizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private OrganizationInvitationRepository organizationInvitationRepository;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private OrganizationMembershipService organizationMembershipService;

    @Autowired
    private OrganizationInvitationService organizationInvitationService;

    private User ownerA;
    private User ownerB;
    private User devUser;
    private Organization orgA;
    private Organization orgB;

    @BeforeEach
    void setUp() {
        // Create isolated test users with randomized identifiers
        ownerA = userRepository.save(User.builder()
                .email("owner-a-" + UUID.randomUUID() + "@devflow.com")
                .username("ownera_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        ownerB = userRepository.save(User.builder()
                .email("owner-b-" + UUID.randomUUID() + "@devflow.com")
                .username("ownerb_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        devUser = userRepository.save(User.builder()
                .email("dev-" + UUID.randomUUID() + "@devflow.com")
                .username("dev_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        // Create initial organizations via service to test real creation behavior
        orgA = organizationService.createOrganization(
                "Org Alpha",
                "org-alpha-" + UUID.randomUUID().toString().substring(0, 8),
                "Alpha organization",
                ownerA.getId()
        );

        orgB = organizationService.createOrganization(
                "Org Beta",
                "org-beta-" + UUID.randomUUID().toString().substring(0, 8),
                "Beta organization",
                ownerB.getId()
        );

        entityManager.flush();
    }

    // ── 1. LIFECYCLE & PERSISTENCE INTEGRATION ────────────────────────────────

    @Nested
    @DisplayName("Lifecycle & Persistence")
    class LifecyclePersistenceTests {

        @Test
        @DisplayName("createOrganization persists organization and atomic OWNER membership in PostgreSQL")
        void createOrganization_persistsEntities() {
            String slug = "org-test-" + UUID.randomUUID().toString().substring(0, 8);
            Organization created = organizationService.createOrganization(
                    "Test Corp", slug, "Test description", devUser.getId());

            entityManager.flush();
            entityManager.clear();

            Optional<Organization> persistedOrg = organizationRepository.findById(created.getId());
            assertThat(persistedOrg).isPresent();
            assertThat(persistedOrg.get().getName()).isEqualTo("Test Corp");
            assertThat(persistedOrg.get().getSlug()).isEqualTo(slug);
            assertThat(persistedOrg.get().getOwner().getId()).isEqualTo(devUser.getId());
            assertThat(persistedOrg.get().getCreatedAt()).isNotNull();
            assertThat(persistedOrg.get().getUpdatedAt()).isNotNull();

            Optional<OrganizationMember> ownerMember = organizationMemberRepository
                    .findByOrganizationIdAndUserId(created.getId(), devUser.getId());
            assertThat(ownerMember).isPresent();
            assertThat(ownerMember.get().getRole()).isEqualTo(OrganizationRole.OWNER);
            assertThat(ownerMember.get().getStatus()).isEqualTo(OrganizationMembershipStatus.ACTIVE);
        }

        @Test
        @DisplayName("database unique constraint uq_organizations_slug rejects duplicate slug")
        void duplicateSlug_rejectedAtDatabaseLevel() {
            Organization duplicate = Organization.builder()
                    .name("Duplicate Slug Corp")
                    .slug(orgA.getSlug())
                    .owner(devUser)
                    .build();

            assertThatThrownBy(() -> {
                organizationRepository.save(duplicate);
                entityManager.flush();
            }).hasMessageContaining("uk_organizations_slug");
        }

        @Test
        @DisplayName("updateOrganization updates mutable fields while preserving immutable slug")
        void updateOrganization_preservesSlugInDb() {
            String originalSlug = orgA.getSlug();

            Organization updated = organizationService.updateOrganization(
                    orgA.getId(), "Updated Org Alpha", "Updated Description", ownerA.getId());

            entityManager.flush();
            entityManager.clear();

            Organization reloaded = organizationRepository.findById(orgA.getId()).orElseThrow();
            assertThat(reloaded.getName()).isEqualTo("Updated Org Alpha");
            assertThat(reloaded.getDescription()).isEqualTo("Updated Description");
            assertThat(reloaded.getSlug()).isEqualTo(originalSlug);
        }

        @Test
        @DisplayName("deleteOrganization removes organization and dependent records from database")
        void deleteOrganization_removesEntity() {
            // Create a dependent invitation to verify full aggregate cleanup
            organizationInvitationService.inviteMember(
                    orgA.getId(), "delete-test-" + UUID.randomUUID() + "@devflow.com",
                    OrganizationRole.DEVELOPER, ownerA.getId());
            entityManager.flush();

            organizationService.deleteOrganization(orgA.getId(), ownerA.getId());

            entityManager.flush();
            entityManager.clear();

            assertThat(organizationRepository.findById(orgA.getId())).isEmpty();
            assertThat(organizationMemberRepository.findByOrganizationIdAndStatus(
                    orgA.getId(), OrganizationMembershipStatus.ACTIVE)).isEmpty();
            assertThat(organizationInvitationRepository.findByOrganizationIdAndStatus(
                    orgA.getId(), OrganizationInvitationStatus.PENDING)).isEmpty();
        }
    }

    // ── 2. MEMBERSHIP INTEGRATION & LAST-OWNER PROTECTION ─────────────────────

    @Nested
    @DisplayName("Membership & Owner Protection")
    class MembershipIntegrationTests {

        @Test
        @DisplayName("addMember, changeMemberRole, and removeMember execute against PostgreSQL")
        void membershipLifecycle_succeeds() {
            // Add member
            OrganizationMember added = organizationMembershipService.addMember(
                    orgA.getId(), devUser.getId(), OrganizationRole.DEVELOPER, ownerA.getId());

            entityManager.flush();
            assertThat(organizationMemberRepository.existsByOrganizationIdAndUserId(orgA.getId(), devUser.getId()))
                    .isTrue();

            // Promote to ADMIN
            organizationMembershipService.changeMemberRole(
                    orgA.getId(), devUser.getId(), OrganizationRole.ADMIN, ownerA.getId());

            entityManager.flush();
            entityManager.clear();

            OrganizationMember reloaded = organizationMemberRepository
                    .findByOrganizationIdAndUserId(orgA.getId(), devUser.getId()).orElseThrow();
            assertThat(reloaded.getRole()).isEqualTo(OrganizationRole.ADMIN);

            // Remove member
            organizationMembershipService.removeMember(orgA.getId(), devUser.getId(), ownerA.getId());

            entityManager.flush();
            assertThat(organizationMemberRepository.existsByOrganizationIdAndUserId(orgA.getId(), devUser.getId()))
                    .isFalse();
        }

        @Test
        @DisplayName("last active owner removal protection prevents removing sole OWNER")
        void removeMember_lastOwner_rejectedAndStatePreserved() {
            assertThatThrownBy(() -> organizationMembershipService.removeMember(
                    orgA.getId(), ownerA.getId(), ownerA.getId()))
                    .isInstanceOf(LastOrganizationOwnerRemovalException.class);

            entityManager.flush();
            entityManager.clear();

            // Verify owner member is still active in database
            Optional<OrganizationMember> member = organizationMemberRepository
                    .findByOrganizationIdAndUserId(orgA.getId(), ownerA.getId());
            assertThat(member).isPresent();
            assertThat(member.get().getRole()).isEqualTo(OrganizationRole.OWNER);
        }

        @Test
        @DisplayName("last active owner demotion protection prevents demoting sole OWNER")
        void changeMemberRole_demoteLastOwner_rejectedAndRolePreserved() {
            assertThatThrownBy(() -> organizationMembershipService.changeMemberRole(
                    orgA.getId(), ownerA.getId(), OrganizationRole.ADMIN, ownerA.getId()))
                    .isInstanceOf(LastOrganizationOwnerRemovalException.class);

            entityManager.flush();
            entityManager.clear();

            OrganizationMember member = organizationMemberRepository
                    .findByOrganizationIdAndUserId(orgA.getId(), ownerA.getId()).orElseThrow();
            assertThat(member.getRole()).isEqualTo(OrganizationRole.OWNER);
        }
    }

    // ── 3. INVITATION FLOW INTEGRATION ────────────────────────────────────────

    @Nested
    @DisplayName("Invitation Flow")
    class InvitationIntegrationTests {

        @Test
        @DisplayName("inviteMember, acceptInvitation creates active membership in PostgreSQL")
        void inviteAndAccept_succeeds() {
            String inviteeEmail = "newinvitee-" + UUID.randomUUID() + "@devflow.com";
            User inviteeUser = userRepository.save(User.builder()
                    .email(inviteeEmail)
                    .username("invitee_" + UUID.randomUUID().toString().substring(0, 8))
                    .passwordHash("hashed_pw")
                    .accountStatus(AccountStatus.ACTIVE)
                    .emailVerified(true)
                    .build());

            OrganizationInvitation invitation = organizationInvitationService.inviteMember(
                    orgA.getId(), inviteeEmail, OrganizationRole.DEVELOPER, ownerA.getId());

            entityManager.flush();
            entityManager.clear();

            // Accept invitation
            OrganizationMember acceptedMember = organizationInvitationService.acceptInvitation(
                    invitation.getToken(), inviteeUser.getId());

            entityManager.flush();
            entityManager.clear();

            assertThat(acceptedMember).isNotNull();
            assertThat(acceptedMember.getRole()).isEqualTo(OrganizationRole.DEVELOPER);

            OrganizationInvitation reloadedInvitation = organizationInvitationRepository
                    .findById(invitation.getId()).orElseThrow();
            assertThat(reloadedInvitation.getStatus()).isEqualTo(OrganizationInvitationStatus.ACCEPTED);
            assertThat(reloadedInvitation.getAcceptedAt()).isNotNull();

            assertThat(organizationMemberRepository.existsByOrganizationIdAndUserId(orgA.getId(), inviteeUser.getId()))
                    .isTrue();
        }

        @Test
        @DisplayName("acceptInvitation with mismatched email is rejected and member is not created")
        void acceptInvitation_mismatchedEmail_rejected() {
            String inviteeEmail = "target-" + UUID.randomUUID() + "@devflow.com";
            OrganizationInvitation invitation = organizationInvitationService.inviteMember(
                    orgA.getId(), inviteeEmail, OrganizationRole.DEVELOPER, ownerA.getId());

            entityManager.flush();

            // devUser email does not match target email
            assertThatThrownBy(() -> organizationInvitationService.acceptInvitation(
                    invitation.getToken(), devUser.getId()))
                    .isInstanceOf(OrganizationAccessDeniedException.class);

            entityManager.flush();
            entityManager.clear();

            // Member should not have been created
            assertThat(organizationMemberRepository.existsByOrganizationIdAndUserId(orgA.getId(), devUser.getId()))
                    .isFalse();
        }

        @Test
        @DisplayName("expirePendingInvitations transitions past-due invitations to EXPIRED")
        void expirePendingInvitations_updatesDatabase() {
            OrganizationInvitation expiredInvitation = OrganizationInvitation.builder()
                    .organization(orgA)
                    .email("expired-" + UUID.randomUUID() + "@devflow.com")
                    .role(OrganizationRole.DEVELOPER)
                    .token(UUID.randomUUID().toString())
                    .invitedBy(ownerA)
                    .status(OrganizationInvitationStatus.PENDING)
                    .expiresAt(Instant.now().minus(2, ChronoUnit.DAYS))
                    .build();

            organizationInvitationRepository.save(expiredInvitation);
            entityManager.flush();

            int expiredCount = organizationInvitationService.expirePendingInvitations();

            entityManager.flush();
            entityManager.clear();

            assertThat(expiredCount).isGreaterThanOrEqualTo(1);
            OrganizationInvitation reloaded = organizationInvitationRepository
                    .findById(expiredInvitation.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(OrganizationInvitationStatus.EXPIRED);
        }
    }

    // ── 4. TENANT ISOLATION REPOSITORY INTEGRATION ────────────────────────────

    @Nested
    @DisplayName("Tenant Isolation Repository Queries")
    class TenantIsolationRepositoryTests {

        @Test
        @DisplayName("findByIdAndOrganizationId isolates members between tenants")
        void memberRepository_tenantIsolation() {
            OrganizationMember memberA = organizationMemberRepository
                    .findByOrganizationIdAndUserId(orgA.getId(), ownerA.getId()).orElseThrow();

            // Querying with matching orgId succeeds
            assertThat(organizationMemberRepository.findByIdAndOrganizationId(memberA.getId(), orgA.getId()))
                    .isPresent();

            // Cross-tenant querying with orgB ID fails closed
            assertThat(organizationMemberRepository.findByIdAndOrganizationId(memberA.getId(), orgB.getId()))
                    .isEmpty();
            assertThat(organizationMemberRepository.existsByIdAndOrganizationId(memberA.getId(), orgB.getId()))
                    .isFalse();
        }

        @Test
        @DisplayName("findByIdAndOrganizationId isolates invitations between tenants")
        void invitationRepository_tenantIsolation() {
            OrganizationInvitation invitationA = organizationInvitationService.inviteMember(
                    orgA.getId(), "isolate-" + UUID.randomUUID() + "@devflow.com",
                    OrganizationRole.DEVELOPER, ownerA.getId());

            entityManager.flush();

            // Matching orgId succeeds
            assertThat(organizationInvitationRepository.findByIdAndOrganizationId(invitationA.getId(), orgA.getId()))
                    .isPresent();

            // Cross-tenant querying with orgB ID fails closed
            assertThat(organizationInvitationRepository.findByIdAndOrganizationId(invitationA.getId(), orgB.getId()))
                    .isEmpty();
            assertThat(organizationInvitationRepository.existsByIdAndOrganizationId(invitationA.getId(), orgB.getId()))
                    .isFalse();
        }
    }

    // ── 5. REST API CONTROLLER END-TO-END ─────────────────────────────────────

    @Nested
    @DisplayName("REST API Controller Integration")
    class RestApiControllerIntegrationTests {

        @Test
        @DisplayName("POST /api/v1/organizations creates organization and returns 201 Created")
        void postOrganization_createsAndReturns201() throws Exception {
            DevFlowUserDetails userDetails = new DevFlowUserDetails(devUser);
            String slug = "org-rest-" + UUID.randomUUID().toString().substring(0, 8);

            CreateOrganizationRequest request = CreateOrganizationRequest.builder()
                    .name("REST Created Org")
                    .slug(slug)
                    .description("Created via REST API")
                    .build();

            mockMvc.perform(post("/api/v1/organizations")
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("REST Created Org"))
                    .andExpect(jsonPath("$.slug").value(slug))
                    .andExpect(jsonPath("$.ownerId").value(devUser.getId().toString()));

            entityManager.flush();
            assertThat(organizationRepository.findBySlug(slug)).isPresent();
        }

        @Test
        @DisplayName("GET /api/v1/organizations/{id} returns 200 for member, 403 for cross-tenant user")
        void getOrganization_tenantEnforcement() throws Exception {
            DevFlowUserDetails ownerDetailsA = new DevFlowUserDetails(ownerA);
            DevFlowUserDetails ownerDetailsB = new DevFlowUserDetails(ownerB);

            // Member of Org A can access Org A
            mockMvc.perform(get("/api/v1/organizations/{id}", orgA.getId())
                            .with(user(ownerDetailsA)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(orgA.getId().toString()));

            // User B (only in Org B) cannot access Org A
            mockMvc.perform(get("/api/v1/organizations/{id}", orgA.getId())
                            .with(user(ownerDetailsB)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }
}
