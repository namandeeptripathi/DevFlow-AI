package com.devflow.organization.service;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationInvitation;
import com.devflow.organization.domain.OrganizationInvitationStatus;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationInvitationAlreadyExistsException;
import com.devflow.organization.exception.OrganizationInvitationAlreadyProcessedException;
import com.devflow.organization.exception.OrganizationInvitationExpiredException;
import com.devflow.organization.exception.OrganizationInvitationNotFoundException;
import com.devflow.organization.exception.OrganizationMemberAlreadyExistsException;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
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
@DisplayName("OrganizationInvitationService Unit Tests")
class OrganizationInvitationServiceTest {

    @Mock
    private OrganizationInvitationRepository organizationInvitationRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationMemberRepository organizationMemberRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationAuthorizationService authorizationService;

    private OrganizationInvitationService invitationService;

    private UUID organizationId;
    private UUID callerUserId;
    private UUID recipientUserId;
    private Organization testOrg;
    private User inviterUser;
    private User recipientUser;
    private OrganizationInvitation pendingInvitation;

    @BeforeEach
    void setUp() {
        invitationService = new OrganizationInvitationService(
                organizationInvitationRepository,
                organizationRepository,
                organizationMemberRepository,
                userRepository,
                authorizationService
        );

        organizationId = UUID.randomUUID();
        callerUserId = UUID.randomUUID();
        recipientUserId = UUID.randomUUID();

        testOrg = Organization.builder()
                .id(organizationId)
                .name("Acme Corp")
                .slug("acme-corp")
                .build();

        inviterUser = User.builder()
                .id(callerUserId)
                .email("admin@devflow.com")
                .username("adminuser")
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        recipientUser = User.builder()
                .id(recipientUserId)
                .email("invitee@devflow.com")
                .username("inviteeuser")
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        pendingInvitation = OrganizationInvitation.builder()
                .id(UUID.randomUUID())
                .organization(testOrg)
                .email("invitee@devflow.com")
                .role(OrganizationRole.DEVELOPER)
                .token(UUID.randomUUID().toString())
                .invitedBy(inviterUser)
                .status(OrganizationInvitationStatus.PENDING)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .build();
    }

    // ── INVITE MEMBER ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("inviteMember")
    class InviteMemberTests {

        @Test
        @DisplayName("authorized ADMIN can create invitation with 7-day validity and secure token")
        void inviteMember_validRequest_succeeds() {
            String email = "  INVITEE@devflow.com  ";
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));
            when(userRepository.findById(callerUserId)).thenReturn(Optional.of(inviterUser));
            when(userRepository.findByEmail("invitee@devflow.com")).thenReturn(Optional.empty());
            when(organizationInvitationRepository.existsByOrganizationIdAndEmailAndStatus(
                    organizationId, "invitee@devflow.com", OrganizationInvitationStatus.PENDING))
                    .thenReturn(false);
            when(organizationInvitationRepository.save(any(OrganizationInvitation.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            OrganizationInvitation created = invitationService.inviteMember(
                    organizationId, email, OrganizationRole.DEVELOPER, callerUserId);

            assertThat(created).isNotNull();
            assertThat(created.getEmail()).isEqualTo("invitee@devflow.com");
            assertThat(created.getRole()).isEqualTo(OrganizationRole.DEVELOPER);
            assertThat(created.getStatus()).isEqualTo(OrganizationInvitationStatus.PENDING);
            assertThat(created.getToken()).isNotBlank();
            assertThat(created.getExpiresAt()).isAfter(Instant.now().plus(6, ChronoUnit.DAYS));
            verify(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
        }

        @Test
        @DisplayName("user already active member throws OrganizationMemberAlreadyExistsException")
        void inviteMember_alreadyActiveMember_throwsException() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));
            when(userRepository.findById(callerUserId)).thenReturn(Optional.of(inviterUser));
            when(userRepository.findByEmail("invitee@devflow.com")).thenReturn(Optional.of(recipientUser));
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndStatus(
                    organizationId, recipientUserId, OrganizationMembershipStatus.ACTIVE))
                    .thenReturn(true);

            assertThatThrownBy(() -> invitationService.inviteMember(
                    organizationId, "invitee@devflow.com", OrganizationRole.DEVELOPER, callerUserId))
                    .isInstanceOf(OrganizationMemberAlreadyExistsException.class)
                    .hasMessageContaining("already a member");

            verify(organizationInvitationRepository, never()).save(any());
        }

        @Test
        @DisplayName("pending invitation already exists for email throws OrganizationInvitationAlreadyExistsException")
        void inviteMember_pendingInvitationExists_throwsException() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));
            when(userRepository.findById(callerUserId)).thenReturn(Optional.of(inviterUser));
            when(userRepository.findByEmail("invitee@devflow.com")).thenReturn(Optional.empty());
            when(organizationInvitationRepository.existsByOrganizationIdAndEmailAndStatus(
                    organizationId, "invitee@devflow.com", OrganizationInvitationStatus.PENDING))
                    .thenReturn(true);

            assertThatThrownBy(() -> invitationService.inviteMember(
                    organizationId, "invitee@devflow.com", OrganizationRole.DEVELOPER, callerUserId))
                    .isInstanceOf(OrganizationInvitationAlreadyExistsException.class)
                    .hasMessageContaining("Pending invitation already exists");

            verify(organizationInvitationRepository, never()).save(any());
        }

        @Test
        @DisplayName("caller lacking ADMIN or OWNER role propagates OrganizationAccessDeniedException")
        void inviteMember_unauthorized_throwsException() {
            doThrow(new OrganizationAccessDeniedException("Access denied"))
                    .when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);

            assertThatThrownBy(() -> invitationService.inviteMember(
                    organizationId, "invitee@devflow.com", OrganizationRole.DEVELOPER, callerUserId))
                    .isInstanceOf(OrganizationAccessDeniedException.class);
        }
    }

    // ── ACCEPT INVITATION ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("acceptInvitation")
    class AcceptInvitationTests {

        @Test
        @DisplayName("valid token and matching recipient email successfully accepts invitation and creates member")
        void acceptInvitation_validToken_succeeds() {
            String token = pendingInvitation.getToken();
            when(organizationInvitationRepository.findByToken(token)).thenReturn(Optional.of(pendingInvitation));
            when(userRepository.findById(recipientUserId)).thenReturn(Optional.of(recipientUser));
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndStatus(
                    organizationId, recipientUserId, OrganizationMembershipStatus.ACTIVE))
                    .thenReturn(false);
            when(organizationMemberRepository.save(any(OrganizationMember.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(organizationInvitationRepository.save(any(OrganizationInvitation.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            OrganizationMember createdMember = invitationService.acceptInvitation(token, recipientUserId);

            assertThat(createdMember).isNotNull();
            assertThat(createdMember.getOrganization()).isEqualTo(testOrg);
            assertThat(createdMember.getUser()).isEqualTo(recipientUser);
            assertThat(createdMember.getRole()).isEqualTo(OrganizationRole.DEVELOPER);
            assertThat(createdMember.getStatus()).isEqualTo(OrganizationMembershipStatus.ACTIVE);

            assertThat(pendingInvitation.getStatus()).isEqualTo(OrganizationInvitationStatus.ACCEPTED);
            assertThat(pendingInvitation.getAcceptedAt()).isNotNull();
        }

        @Test
        @DisplayName("invalid or unknown token throws OrganizationInvitationNotFoundException")
        void acceptInvitation_unknownToken_throwsException() {
            when(organizationInvitationRepository.findByToken("invalid-token")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> invitationService.acceptInvitation("invalid-token", recipientUserId))
                    .isInstanceOf(OrganizationInvitationNotFoundException.class)
                    .hasMessageContaining("Invalid or non-existent invitation token");
        }

        @Test
        @DisplayName("already ACCEPTED or REVOKED invitation throws OrganizationInvitationAlreadyProcessedException")
        void acceptInvitation_alreadyProcessed_throwsException() {
            pendingInvitation.setStatus(OrganizationInvitationStatus.ACCEPTED);
            when(organizationInvitationRepository.findByToken("token")).thenReturn(Optional.of(pendingInvitation));

            assertThatThrownBy(() -> invitationService.acceptInvitation("token", recipientUserId))
                    .isInstanceOf(OrganizationInvitationAlreadyProcessedException.class)
                    .hasMessageContaining("already been accepted");

            pendingInvitation.setStatus(OrganizationInvitationStatus.REVOKED);
            assertThatThrownBy(() -> invitationService.acceptInvitation("token", recipientUserId))
                    .isInstanceOf(OrganizationInvitationAlreadyProcessedException.class)
                    .hasMessageContaining("already been revoked");
        }

        @Test
        @DisplayName("expired status throws OrganizationInvitationExpiredException")
        void acceptInvitation_statusExpired_throwsException() {
            pendingInvitation.setStatus(OrganizationInvitationStatus.EXPIRED);
            when(organizationInvitationRepository.findByToken("token")).thenReturn(Optional.of(pendingInvitation));

            assertThatThrownBy(() -> invitationService.acceptInvitation("token", recipientUserId))
                    .isInstanceOf(OrganizationInvitationExpiredException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("past expiresAt timestamp marks invitation EXPIRED and throws OrganizationInvitationExpiredException")
        void acceptInvitation_pastExpiryTimestamp_marksExpiredAndThrows() {
            pendingInvitation.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
            when(organizationInvitationRepository.findByToken("token")).thenReturn(Optional.of(pendingInvitation));

            assertThatThrownBy(() -> invitationService.acceptInvitation("token", recipientUserId))
                    .isInstanceOf(OrganizationInvitationExpiredException.class)
                    .hasMessageContaining("expired");

            // Interaction assertion: repository saves status transition to EXPIRED
            ArgumentCaptor<OrganizationInvitation> captor = ArgumentCaptor.forClass(OrganizationInvitation.class);
            verify(organizationInvitationRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(OrganizationInvitationStatus.EXPIRED);
            verify(organizationMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("authenticated user email mismatch throws OrganizationAccessDeniedException (security invariant)")
        void acceptInvitation_emailMismatch_throwsException() {
            User differentUser = User.builder()
                    .id(recipientUserId)
                    .email("different@devflow.com")
                    .username("differentuser")
                    .build();

            when(organizationInvitationRepository.findByToken("token")).thenReturn(Optional.of(pendingInvitation));
            when(userRepository.findById(recipientUserId)).thenReturn(Optional.of(differentUser));

            assertThatThrownBy(() -> invitationService.acceptInvitation("token", recipientUserId))
                    .isInstanceOf(OrganizationAccessDeniedException.class)
                    .hasMessageContaining("does not match invitation recipient email");

            verify(organizationMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("accepting user already active member updates invitation to ACCEPTED and throws OrganizationMemberAlreadyExistsException")
        void acceptInvitation_alreadyActiveMember_updatesStatusAndThrows() {
            when(organizationInvitationRepository.findByToken("token")).thenReturn(Optional.of(pendingInvitation));
            when(userRepository.findById(recipientUserId)).thenReturn(Optional.of(recipientUser));
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndStatus(
                    organizationId, recipientUserId, OrganizationMembershipStatus.ACTIVE))
                    .thenReturn(true);

            assertThatThrownBy(() -> invitationService.acceptInvitation("token", recipientUserId))
                    .isInstanceOf(OrganizationMemberAlreadyExistsException.class)
                    .hasMessageContaining("already an active member");

            // Interaction assertion: invitation was updated to ACCEPTED before exception thrown
            ArgumentCaptor<OrganizationInvitation> captor = ArgumentCaptor.forClass(OrganizationInvitation.class);
            verify(organizationInvitationRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(OrganizationInvitationStatus.ACCEPTED);
            assertThat(captor.getValue().getAcceptedAt()).isNotNull();
        }
    }

    // ── REVOKE INVITATION ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("revokeInvitation")
    class RevokeInvitationTests {

        @Test
        @DisplayName("authorized ADMIN can revoke pending invitation")
        void revokeInvitation_pending_succeeds() {
            UUID invitationId = pendingInvitation.getId();
            when(organizationInvitationRepository.findById(invitationId)).thenReturn(Optional.of(pendingInvitation));
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationInvitationRepository.save(any(OrganizationInvitation.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            OrganizationInvitation revoked = invitationService.revokeInvitation(invitationId, callerUserId);

            assertThat(revoked.getStatus()).isEqualTo(OrganizationInvitationStatus.REVOKED);
            verify(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
        }

        @Test
        @DisplayName("revoking non-pending invitation throws OrganizationInvitationAlreadyProcessedException")
        void revokeInvitation_alreadyAccepted_throwsException() {
            UUID invitationId = pendingInvitation.getId();
            pendingInvitation.setStatus(OrganizationInvitationStatus.ACCEPTED);
            when(organizationInvitationRepository.findById(invitationId)).thenReturn(Optional.of(pendingInvitation));
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);

            assertThatThrownBy(() -> invitationService.revokeInvitation(invitationId, callerUserId))
                    .isInstanceOf(OrganizationInvitationAlreadyProcessedException.class)
                    .hasMessageContaining("Cannot revoke invitation in status: ACCEPTED");
        }

        @Test
        @DisplayName("revoking non-existent invitation throws OrganizationInvitationNotFoundException")
        void revokeInvitation_notFound_throwsException() {
            UUID invitationId = UUID.randomUUID();
            when(organizationInvitationRepository.findById(invitationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> invitationService.revokeInvitation(invitationId, callerUserId))
                    .isInstanceOf(OrganizationInvitationNotFoundException.class);
        }
    }

    // ── LIST PENDING INVITATIONS ──────────────────────────────────────────────

    @Nested
    @DisplayName("listPendingInvitations")
    class ListPendingInvitationsTests {

        @Test
        @DisplayName("authorized ADMIN can list pending invitations")
        void listPendingInvitations_authorizedAdmin_returnsList() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationRepository.existsById(organizationId)).thenReturn(true);
            when(organizationInvitationRepository.findByOrganizationIdAndStatus(
                    organizationId, OrganizationInvitationStatus.PENDING))
                    .thenReturn(List.of(pendingInvitation));

            List<OrganizationInvitation> result = invitationService.listPendingInvitations(organizationId, callerUserId);

            assertThat(result).hasSize(1).contains(pendingInvitation);
            verify(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
        }

        @Test
        @DisplayName("non-existent organization throws OrganizationNotFoundException")
        void listPendingInvitations_orgNotFound_throwsException() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationRepository.existsById(organizationId)).thenReturn(false);

            assertThatThrownBy(() -> invitationService.listPendingInvitations(organizationId, callerUserId))
                    .isInstanceOf(OrganizationNotFoundException.class);
        }
    }

    // ── EXPIRE PENDING INVITATIONS ────────────────────────────────────────────

    @Nested
    @DisplayName("expirePendingInvitations")
    class ExpirePendingInvitationsTests {

        @Test
        @DisplayName("transitions past-due pending invitations to EXPIRED status in batch")
        void expirePendingInvitations_withExpiredRecords_updatesAndReturnsCount() {
            pendingInvitation.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
            when(organizationInvitationRepository.findByExpiresAtBeforeAndStatus(
                    any(Instant.class), eq(OrganizationInvitationStatus.PENDING)))
                    .thenReturn(List.of(pendingInvitation));

            int count = invitationService.expirePendingInvitations();

            assertThat(count).isEqualTo(1);
            assertThat(pendingInvitation.getStatus()).isEqualTo(OrganizationInvitationStatus.EXPIRED);
            verify(organizationInvitationRepository).saveAll(List.of(pendingInvitation));
        }

        @Test
        @DisplayName("returns 0 when no expired pending invitations exist")
        void expirePendingInvitations_noneExpired_returnsZero() {
            when(organizationInvitationRepository.findByExpiresAtBeforeAndStatus(
                    any(Instant.class), eq(OrganizationInvitationStatus.PENDING)))
                    .thenReturn(List.of());

            int count = invitationService.expirePendingInvitations();

            assertThat(count).isEqualTo(0);
            verify(organizationInvitationRepository, never()).saveAll(any());
        }
    }
}
