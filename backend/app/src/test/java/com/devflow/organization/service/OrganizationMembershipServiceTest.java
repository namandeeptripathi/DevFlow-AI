package com.devflow.organization.service;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.exception.LastOrganizationOwnerRemovalException;
import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationMemberAlreadyExistsException;
import com.devflow.organization.exception.OrganizationMemberNotFoundException;
import com.devflow.organization.exception.OrganizationMembershipRequiredException;
import com.devflow.organization.exception.OrganizationNotFoundException;
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
@DisplayName("OrganizationMembershipService Unit Tests")
class OrganizationMembershipServiceTest {

    @Mock
    private OrganizationMemberRepository organizationMemberRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationAuthorizationService authorizationService;

    private OrganizationMembershipService membershipService;

    private UUID organizationId;
    private UUID targetUserId;
    private UUID callerUserId;
    private Organization testOrg;
    private User targetUser;
    private OrganizationMember activeOwnerMember;
    private OrganizationMember activeDeveloperMember;

    @BeforeEach
    void setUp() {
        membershipService = new OrganizationMembershipService(
                organizationMemberRepository,
                organizationRepository,
                userRepository,
                authorizationService
        );

        organizationId = UUID.randomUUID();
        targetUserId = UUID.randomUUID();
        callerUserId = UUID.randomUUID();

        testOrg = Organization.builder()
                .id(organizationId)
                .name("Acme Corp")
                .slug("acme-corp")
                .build();

        targetUser = User.builder()
                .id(targetUserId)
                .email("developer@devflow.com")
                .username("devuser")
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        activeOwnerMember = OrganizationMember.builder()
                .id(UUID.randomUUID())
                .organization(testOrg)
                .user(targetUser)
                .role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .joinedAt(Instant.now())
                .build();

        activeDeveloperMember = OrganizationMember.builder()
                .id(UUID.randomUUID())
                .organization(testOrg)
                .user(targetUser)
                .role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .joinedAt(Instant.now())
                .build();
    }

    // ── ADD MEMBER ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addMember")
    class AddMemberTests {

        @Test
        @DisplayName("authorized ADMIN can add user to organization with specified role")
        void addMember_validRequest_succeeds() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
            when(organizationMemberRepository.existsByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(false);
            when(organizationMemberRepository.save(any(OrganizationMember.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            OrganizationMember result = membershipService.addMember(
                    organizationId, targetUserId, OrganizationRole.DEVELOPER, callerUserId);

            assertThat(result).isNotNull();
            assertThat(result.getOrganization()).isEqualTo(testOrg);
            assertThat(result.getUser()).isEqualTo(targetUser);
            assertThat(result.getRole()).isEqualTo(OrganizationRole.DEVELOPER);
            assertThat(result.getStatus()).isEqualTo(OrganizationMembershipStatus.ACTIVE);
            assertThat(result.getJoinedAt()).isNotNull();

            verify(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
        }

        @Test
        @DisplayName("caller lacking ADMIN or OWNER role propagates OrganizationAccessDeniedException")
        void addMember_unauthorized_throwsException() {
            doThrow(new OrganizationAccessDeniedException("Access denied"))
                    .when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);

            assertThatThrownBy(() -> membershipService.addMember(
                    organizationId, targetUserId, OrganizationRole.DEVELOPER, callerUserId))
                    .isInstanceOf(OrganizationAccessDeniedException.class);

            verify(organizationMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("non-existent organization throws OrganizationNotFoundException")
        void addMember_orgNotFound_throwsException() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> membershipService.addMember(
                    organizationId, targetUserId, OrganizationRole.DEVELOPER, callerUserId))
                    .isInstanceOf(OrganizationNotFoundException.class)
                    .hasMessageContaining(organizationId.toString());

            verify(organizationMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("non-existent target user throws OrganizationNotFoundException")
        void addMember_userNotFound_throwsException() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));
            when(userRepository.findById(targetUserId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> membershipService.addMember(
                    organizationId, targetUserId, OrganizationRole.DEVELOPER, callerUserId))
                    .isInstanceOf(OrganizationNotFoundException.class)
                    .hasMessageContaining(targetUserId.toString());

            verify(organizationMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("user already member of organization throws OrganizationMemberAlreadyExistsException")
        void addMember_alreadyMember_throwsException() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
            when(organizationMemberRepository.existsByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(true);

            assertThatThrownBy(() -> membershipService.addMember(
                    organizationId, targetUserId, OrganizationRole.DEVELOPER, callerUserId))
                    .isInstanceOf(OrganizationMemberAlreadyExistsException.class)
                    .hasMessageContaining(targetUserId.toString());

            verify(organizationMemberRepository, never()).save(any());
        }
    }

    // ── REMOVE MEMBER ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("removeMember")
    class RemoveMemberTests {

        @Test
        @DisplayName("authorized ADMIN can remove non-owner member")
        void removeMember_nonOwner_succeeds() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(activeDeveloperMember));

            membershipService.removeMember(organizationId, targetUserId, callerUserId);

            verify(organizationMemberRepository).delete(activeDeveloperMember);
        }

        @Test
        @DisplayName("authorized ADMIN can remove an OWNER when multiple active owners exist")
        void removeMember_coOwner_succeeds() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(activeOwnerMember));
            when(organizationMemberRepository.countByOrganizationIdAndRoleAndStatus(
                    organizationId, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE))
                    .thenReturn(2L);

            membershipService.removeMember(organizationId, targetUserId, callerUserId);

            verify(organizationMemberRepository).delete(activeOwnerMember);
        }

        @Test
        @DisplayName("attempting to remove last active OWNER throws LastOrganizationOwnerRemovalException")
        void removeMember_lastOwner_throwsException() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(activeOwnerMember));
            when(organizationMemberRepository.countByOrganizationIdAndRoleAndStatus(
                    organizationId, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE))
                    .thenReturn(1L);

            assertThatThrownBy(() -> membershipService.removeMember(organizationId, targetUserId, callerUserId))
                    .isInstanceOf(LastOrganizationOwnerRemovalException.class)
                    .hasMessageContaining("Cannot remove the last active OWNER");

            verify(organizationMemberRepository, never()).delete(any());
        }

        @Test
        @DisplayName("target user not found in organization throws OrganizationMemberNotFoundException")
        void removeMember_memberNotFound_throwsException() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> membershipService.removeMember(organizationId, targetUserId, callerUserId))
                    .isInstanceOf(OrganizationMemberNotFoundException.class);

            verify(organizationMemberRepository, never()).delete(any());
        }

        @Test
        @DisplayName("unauthorized caller propagates OrganizationAccessDeniedException")
        void removeMember_unauthorized_throwsException() {
            doThrow(new OrganizationAccessDeniedException("Access denied"))
                    .when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);

            assertThatThrownBy(() -> membershipService.removeMember(organizationId, targetUserId, callerUserId))
                    .isInstanceOf(OrganizationAccessDeniedException.class);
        }
    }

    // ── CHANGE MEMBER ROLE ────────────────────────────────────────────────────

    @Nested
    @DisplayName("changeMemberRole")
    class ChangeMemberRoleTests {

        @Test
        @DisplayName("authorized OWNER can promote member to ADMIN or OWNER")
        void changeMemberRole_promote_succeeds() {
            doNothing().when(authorizationService).requireOwner(organizationId, callerUserId);
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(activeDeveloperMember));
            when(organizationMemberRepository.save(any(OrganizationMember.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            OrganizationMember updated = membershipService.changeMemberRole(
                    organizationId, targetUserId, OrganizationRole.ADMIN, callerUserId);

            assertThat(updated.getRole()).isEqualTo(OrganizationRole.ADMIN);
            verify(authorizationService).requireOwner(organizationId, callerUserId);
        }

        @Test
        @DisplayName("authorized OWNER can demote an OWNER when multiple active owners exist")
        void changeMemberRole_demoteWithMultipleOwners_succeeds() {
            doNothing().when(authorizationService).requireOwner(organizationId, callerUserId);
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(activeOwnerMember));
            when(organizationMemberRepository.countByOrganizationIdAndRoleAndStatus(
                    organizationId, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE))
                    .thenReturn(2L);
            when(organizationMemberRepository.save(any(OrganizationMember.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            OrganizationMember updated = membershipService.changeMemberRole(
                    organizationId, targetUserId, OrganizationRole.ADMIN, callerUserId);

            assertThat(updated.getRole()).isEqualTo(OrganizationRole.ADMIN);
        }

        @Test
        @DisplayName("attempting to demote last active OWNER throws LastOrganizationOwnerRemovalException")
        void changeMemberRole_demoteLastOwner_throwsException() {
            doNothing().when(authorizationService).requireOwner(organizationId, callerUserId);
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(activeOwnerMember));
            when(organizationMemberRepository.countByOrganizationIdAndRoleAndStatus(
                    organizationId, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE))
                    .thenReturn(1L);

            assertThatThrownBy(() -> membershipService.changeMemberRole(
                    organizationId, targetUserId, OrganizationRole.ADMIN, callerUserId))
                    .isInstanceOf(LastOrganizationOwnerRemovalException.class)
                    .hasMessageContaining("Cannot demote the last active OWNER");

            verify(organizationMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("caller lacking OWNER role propagates OrganizationAccessDeniedException")
        void changeMemberRole_nonOwnerCaller_throwsException() {
            doThrow(new OrganizationAccessDeniedException("Access denied: OWNER role required"))
                    .when(authorizationService).requireOwner(organizationId, callerUserId);

            assertThatThrownBy(() -> membershipService.changeMemberRole(
                    organizationId, targetUserId, OrganizationRole.ADMIN, callerUserId))
                    .isInstanceOf(OrganizationAccessDeniedException.class);

            verify(organizationMemberRepository, never()).save(any());
        }
    }

    // ── LIST ACTIVE MEMBERS ───────────────────────────────────────────────────

    @Nested
    @DisplayName("listActiveMembers")
    class ListActiveMembersTests {

        @Test
        @DisplayName("active member can list all active organization members")
        void listActiveMembers_activeMember_returnsList() {
            doNothing().when(authorizationService).requireMember(organizationId, callerUserId);
            when(organizationRepository.existsById(organizationId)).thenReturn(true);
            when(organizationMemberRepository.findByOrganizationIdAndStatus(
                    organizationId, OrganizationMembershipStatus.ACTIVE))
                    .thenReturn(List.of(activeOwnerMember, activeDeveloperMember));

            List<OrganizationMember> members = membershipService.listActiveMembers(organizationId, callerUserId);

            assertThat(members).hasSize(2).contains(activeOwnerMember, activeDeveloperMember);
            verify(authorizationService).requireMember(organizationId, callerUserId);
        }

        @Test
        @DisplayName("non-member caller propagates OrganizationMembershipRequiredException")
        void listActiveMembers_nonMember_throwsException() {
            doThrow(new OrganizationMembershipRequiredException("Membership required"))
                    .when(authorizationService).requireMember(organizationId, callerUserId);

            assertThatThrownBy(() -> membershipService.listActiveMembers(organizationId, callerUserId))
                    .isInstanceOf(OrganizationMembershipRequiredException.class);
        }

        @Test
        @DisplayName("non-existent organization throws OrganizationNotFoundException")
        void listActiveMembers_orgNotFound_throwsException() {
            doNothing().when(authorizationService).requireMember(organizationId, callerUserId);
            when(organizationRepository.existsById(organizationId)).thenReturn(false);

            assertThatThrownBy(() -> membershipService.listActiveMembers(organizationId, callerUserId))
                    .isInstanceOf(OrganizationNotFoundException.class)
                    .hasMessageContaining(organizationId.toString());
        }
    }
}
