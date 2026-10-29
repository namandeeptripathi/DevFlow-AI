package com.devflow.organization.service;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationAlreadyExistsException;
import com.devflow.organization.exception.OrganizationMembershipRequiredException;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.organization.repository.OrganizationInvitationRepository;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
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
@DisplayName("OrganizationService Unit Tests")
class OrganizationServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationMemberRepository organizationMemberRepository;

    @Mock
    private OrganizationInvitationRepository organizationInvitationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationAuthorizationService authorizationService;

    private OrganizationService organizationService;

    private UUID organizationId;
    private UUID callerUserId;
    private User testUser;
    private Organization testOrg;

    @BeforeEach
    void setUp() {
        organizationService = new OrganizationService(
                organizationRepository,
                organizationMemberRepository,
                organizationInvitationRepository,
                userRepository,
                authorizationService
        );

        organizationId = UUID.randomUUID();
        callerUserId = UUID.randomUUID();

        testUser = User.builder()
                .id(callerUserId)
                .email("owner@devflow.com")
                .username("owneruser")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();

        testOrg = Organization.builder()
                .id(organizationId)
                .name("Acme Corp")
                .slug("acme-corp")
                .description("Acme software development")
                .owner(testUser)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // ── CREATE ORGANIZATION ───────────────────────────────────────────────────

    @Nested
    @DisplayName("createOrganization")
    class CreateOrganizationTests {

        @Test
        @DisplayName("valid request creates organization and atomically assigns caller as OWNER member")
        void createOrganization_validRequest_succeeds() {
            String name = "Acme Corp";
            String slug = "  ACME-corp  ";
            String description = "  Acme software development  ";

            when(organizationRepository.existsBySlug("acme-corp")).thenReturn(false);
            when(userRepository.findById(callerUserId)).thenReturn(Optional.of(testUser));
            when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> {
                Organization org = inv.getArgument(0);
                org.setId(organizationId);
                return org;
            });

            Organization result = organizationService.createOrganization(name, slug, description, callerUserId);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(organizationId);
            assertThat(result.getName()).isEqualTo("Acme Corp");
            assertThat(result.getSlug()).isEqualTo("acme-corp");
            assertThat(result.getDescription()).isEqualTo("Acme software development");
            assertThat(result.getOwner()).isEqualTo(testUser);

            // Verify atomic creation of OWNER membership
            ArgumentCaptor<OrganizationMember> memberCaptor = ArgumentCaptor.forClass(OrganizationMember.class);
            verify(organizationMemberRepository).save(memberCaptor.capture());
            OrganizationMember createdMember = memberCaptor.getValue();
            assertThat(createdMember.getOrganization().getId()).isEqualTo(organizationId);
            assertThat(createdMember.getUser()).isEqualTo(testUser);
            assertThat(createdMember.getRole()).isEqualTo(OrganizationRole.OWNER);
            assertThat(createdMember.getStatus()).isEqualTo(OrganizationMembershipStatus.ACTIVE);
            assertThat(createdMember.getJoinedAt()).isNotNull();
        }

        @Test
        @DisplayName("duplicate slug throws OrganizationAlreadyExistsException")
        void createOrganization_duplicateSlug_throwsException() {
            when(organizationRepository.existsBySlug("acme-corp")).thenReturn(true);

            assertThatThrownBy(() -> organizationService.createOrganization("Acme", "acme-corp", "desc", callerUserId))
                    .isInstanceOf(OrganizationAlreadyExistsException.class)
                    .hasMessageContaining("acme-corp");

            verify(organizationRepository, never()).save(any());
            verify(organizationMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("non-existent caller user throws OrganizationNotFoundException")
        void createOrganization_userNotFound_throwsException() {
            when(organizationRepository.existsBySlug("acme-corp")).thenReturn(false);
            when(userRepository.findById(callerUserId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.createOrganization("Acme", "acme-corp", "desc", callerUserId))
                    .isInstanceOf(OrganizationNotFoundException.class)
                    .hasMessageContaining(callerUserId.toString());

            verify(organizationRepository, never()).save(any());
            verify(organizationMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("blank description is normalised to null")
        void createOrganization_blankDescription_normalisedToNull() {
            when(organizationRepository.existsBySlug("acme-corp")).thenReturn(false);
            when(userRepository.findById(callerUserId)).thenReturn(Optional.of(testUser));
            when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

            Organization result = organizationService.createOrganization("Acme", "acme-corp", "   ", callerUserId);

            assertThat(result.getDescription()).isNull();
        }

        @Test
        @DisplayName("defensive null checks throw NullPointerException")
        void createOrganization_nullGuards_throwNPE() {
            assertThatThrownBy(() -> organizationService.createOrganization(null, "slug", "desc", callerUserId))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("name");
            assertThatThrownBy(() -> organizationService.createOrganization("Name", null, "desc", callerUserId))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("slug");
            assertThatThrownBy(() -> organizationService.createOrganization("Name", "slug", "desc", null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("callerUserId");
        }
    }

    // ── UPDATE ORGANIZATION ───────────────────────────────────────────────────

    @Nested
    @DisplayName("updateOrganization")
    class UpdateOrganizationTests {

        @Test
        @DisplayName("authorized ADMIN/OWNER can update name and description while slug remains immutable")
        void updateOrganization_validRequest_preservesSlug() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));
            when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

            Organization updated = organizationService.updateOrganization(
                    organizationId, "New Acme Corp", "New Description", callerUserId);

            assertThat(updated.getName()).isEqualTo("New Acme Corp");
            assertThat(updated.getDescription()).isEqualTo("New Description");
            // Invariant: slug must not change
            assertThat(updated.getSlug()).isEqualTo("acme-corp");
            verify(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
        }

        @Test
        @DisplayName("null name or description leaves existing values unchanged")
        void updateOrganization_partialUpdate_leavesExisting() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));
            when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

            Organization updated = organizationService.updateOrganization(
                    organizationId, null, null, callerUserId);

            assertThat(updated.getName()).isEqualTo("Acme Corp");
            assertThat(updated.getDescription()).isEqualTo("Acme software development");
        }

        @Test
        @DisplayName("unauthorized caller propagates OrganizationAccessDeniedException")
        void updateOrganization_unauthorized_throwsException() {
            doThrow(new OrganizationAccessDeniedException("Access denied"))
                    .when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);

            assertThatThrownBy(() -> organizationService.updateOrganization(
                    organizationId, "New Name", "New Desc", callerUserId))
                    .isInstanceOf(OrganizationAccessDeniedException.class);

            verify(organizationRepository, never()).save(any());
        }

        @Test
        @DisplayName("non-existent organization throws OrganizationNotFoundException")
        void updateOrganization_notFound_throwsException() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.updateOrganization(
                    organizationId, "New Name", "New Desc", callerUserId))
                    .isInstanceOf(OrganizationNotFoundException.class)
                    .hasMessageContaining(organizationId.toString());

            verify(organizationRepository, never()).save(any());
        }
    }

    // ── DELETE ORGANIZATION ───────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteOrganization")
    class DeleteOrganizationTests {

        @Test
        @DisplayName("authorized OWNER can delete organization")
        void deleteOrganization_authorizedOwner_succeeds() {
            doNothing().when(authorizationService).requireOwner(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));

            organizationService.deleteOrganization(organizationId, callerUserId);

            verify(authorizationService).requireOwner(organizationId, callerUserId);
            verify(organizationInvitationRepository).deleteByOrganizationId(organizationId);
            verify(organizationMemberRepository).deleteByOrganizationId(organizationId);
            verify(organizationRepository).delete(testOrg);
        }

        @Test
        @DisplayName("unauthorized caller propagates OrganizationAccessDeniedException")
        void deleteOrganization_unauthorized_throwsException() {
            doThrow(new OrganizationAccessDeniedException("Access denied: OWNER role required"))
                    .when(authorizationService).requireOwner(organizationId, callerUserId);

            assertThatThrownBy(() -> organizationService.deleteOrganization(organizationId, callerUserId))
                    .isInstanceOf(OrganizationAccessDeniedException.class);

            verify(organizationRepository, never()).delete(any());
        }

        @Test
        @DisplayName("non-existent organization throws OrganizationNotFoundException")
        void deleteOrganization_notFound_throwsException() {
            doNothing().when(authorizationService).requireOwner(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.deleteOrganization(organizationId, callerUserId))
                    .isInstanceOf(OrganizationNotFoundException.class);

            verify(organizationRepository, never()).delete(any());
        }
    }

    // ── GET ORGANIZATION BY ID ────────────────────────────────────────────────

    @Nested
    @DisplayName("getOrganizationById")
    class GetOrganizationByIdTests {

        @Test
        @DisplayName("active member can retrieve organization by ID")
        void getOrganizationById_activeMember_returnsOrganization() {
            doNothing().when(authorizationService).requireMember(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));

            Organization result = organizationService.getOrganizationById(organizationId, callerUserId);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(organizationId);
            verify(authorizationService).requireMember(organizationId, callerUserId);
        }

        @Test
        @DisplayName("non-member caller propagates OrganizationMembershipRequiredException")
        void getOrganizationById_nonMember_throwsException() {
            doThrow(new OrganizationMembershipRequiredException("Membership required"))
                    .when(authorizationService).requireMember(organizationId, callerUserId);

            assertThatThrownBy(() -> organizationService.getOrganizationById(organizationId, callerUserId))
                    .isInstanceOf(OrganizationMembershipRequiredException.class);

            verify(organizationRepository, never()).findById(any());
        }

        @Test
        @DisplayName("non-existent organization throws OrganizationNotFoundException")
        void getOrganizationById_notFound_throwsException() {
            doNothing().when(authorizationService).requireMember(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.getOrganizationById(organizationId, callerUserId))
                    .isInstanceOf(OrganizationNotFoundException.class);
        }
    }

    // ── GET ORGANIZATION BY SLUG ──────────────────────────────────────────────

    @Nested
    @DisplayName("getOrganizationBySlug")
    class GetOrganizationBySlugTests {

        @Test
        @DisplayName("active member can retrieve organization by normalized slug")
        void getOrganizationBySlug_activeMember_returnsOrganization() {
            String slug = "  ACME-corp  ";
            when(organizationRepository.findBySlug("acme-corp")).thenReturn(Optional.of(testOrg));
            doNothing().when(authorizationService).requireMember(organizationId, callerUserId);

            Organization result = organizationService.getOrganizationBySlug(slug, callerUserId);

            assertThat(result).isNotNull();
            assertThat(result.getSlug()).isEqualTo("acme-corp");
            verify(authorizationService).requireMember(organizationId, callerUserId);
        }

        @Test
        @DisplayName("non-existent slug throws OrganizationNotFoundException")
        void getOrganizationBySlug_notFound_throwsException() {
            when(organizationRepository.findBySlug("unknown-slug")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.getOrganizationBySlug("unknown-slug", callerUserId))
                    .isInstanceOf(OrganizationNotFoundException.class)
                    .hasMessageContaining("unknown-slug");

            verify(authorizationService, never()).requireMember(any(), any());
        }

        @Test
        @DisplayName("non-member caller propagates OrganizationMembershipRequiredException")
        void getOrganizationBySlug_nonMember_throwsException() {
            when(organizationRepository.findBySlug("acme-corp")).thenReturn(Optional.of(testOrg));
            doThrow(new OrganizationMembershipRequiredException("Membership required"))
                    .when(authorizationService).requireMember(organizationId, callerUserId);

            assertThatThrownBy(() -> organizationService.getOrganizationBySlug("acme-corp", callerUserId))
                    .isInstanceOf(OrganizationMembershipRequiredException.class);
        }
    }
}
