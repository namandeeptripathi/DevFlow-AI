package com.devflow.organization.service;

import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.domain.RoleScope;
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
import com.devflow.role.domain.OrganizationRoleAssignment;
import com.devflow.role.domain.RoleEntity;
import com.devflow.role.repository.OrganizationRoleAssignmentRepository;
import com.devflow.role.repository.RoleRepository;
import com.devflow.security.authorization.cache.AuthorizationCacheService;
import com.devflow.team.domain.TeamRole;
import com.devflow.team.domain.TeamStatus;
import com.devflow.team.exception.InvalidTeamOperationException;
import com.devflow.team.repository.TeamMemberRepository;
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

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private OrganizationRoleAssignmentRepository orgAssignmentRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private AuthorizationCacheService cacheService;

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
                authorizationService,
                teamMemberRepository
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

        @Test
        @DisplayName("attempting to remove active team lead throws InvalidTeamOperationException")
        void removeMember_activeTeamLead_throwsInvalidTeamOperationException() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(activeDeveloperMember));
            when(teamMemberRepository.existsByUserIdAndRoleAndOrganizationIdAndTeamStatus(
                    targetUserId, TeamRole.LEAD, organizationId, TeamStatus.ACTIVE))
                    .thenReturn(true);

            assertThatThrownBy(() -> membershipService.removeMember(organizationId, targetUserId, callerUserId))
                    .isInstanceOf(InvalidTeamOperationException.class)
                    .hasMessageContaining("user is currently an active team lead");

            verify(organizationMemberRepository, never()).delete(any());
        }

        @Test
        @DisplayName("removing non-lead cleans up team memberships in the organization")
        void removeMember_nonLead_cleansUpTeamMemberships() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(activeDeveloperMember));
            when(teamMemberRepository.existsByUserIdAndRoleAndOrganizationIdAndTeamStatus(
                    targetUserId, TeamRole.LEAD, organizationId, TeamStatus.ACTIVE))
                    .thenReturn(false);

            membershipService.removeMember(organizationId, targetUserId, callerUserId);

            verify(teamMemberRepository).deleteByUserIdAndOrganizationId(targetUserId, organizationId);
            verify(organizationMemberRepository).delete(activeDeveloperMember);
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

    // ── MEMBERSHIP ROLE SYNCHRONIZATION & OWNER PROTECTION TESTS ─────────────

    @Nested
    @DisplayName("Membership Role Synchronization & Protection Tests")
    class MembershipRoleSyncAndProtectionTests {

        private OrganizationMembershipService syncMembershipService;
        private RoleEntity ownerRoleEntity;
        private RoleEntity adminRoleEntity;
        private RoleEntity memberRoleEntity;

        @BeforeEach
        void setUpSync() {
            syncMembershipService = new OrganizationMembershipService(
                    organizationMemberRepository,
                    organizationRepository,
                    userRepository,
                    authorizationService,
                    teamMemberRepository,
                    cacheService,
                    orgAssignmentRepository,
                    roleRepository
            );

            ownerRoleEntity = RoleEntity.builder()
                    .id(UUID.randomUUID())
                    .name("ORGANIZATION_OWNER")
                    .scope(RoleScope.ORGANIZATION)
                    .build();

            adminRoleEntity = RoleEntity.builder()
                    .id(UUID.randomUUID())
                    .name("ORGANIZATION_ADMIN")
                    .scope(RoleScope.ORGANIZATION)
                    .build();

            memberRoleEntity = RoleEntity.builder()
                    .id(UUID.randomUUID())
                    .name("ORGANIZATION_MEMBER")
                    .scope(RoleScope.ORGANIZATION)
                    .build();
        }

        @Test
        @DisplayName("addMember synchronizes canonical role assignment to OrganizationRoleAssignment")
        void addMember_synchronizesCanonicalRoleAssignment() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(testOrg));
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
            when(organizationMemberRepository.existsByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(false);
            when(organizationMemberRepository.save(any(OrganizationMember.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(roleRepository.findByName("ORGANIZATION_MEMBER")).thenReturn(Optional.of(memberRoleEntity));
            when(orgAssignmentRepository.hasActiveAssignment(eq(organizationId), eq(targetUserId), eq(memberRoleEntity.getId()), any(Instant.class)))
                    .thenReturn(false);
            when(userRepository.findById(callerUserId)).thenReturn(Optional.of(targetUser));

            OrganizationMember result = syncMembershipService.addMember(
                    organizationId, targetUserId, OrganizationRole.DEVELOPER, callerUserId);

            assertThat(result).isNotNull();
            ArgumentCaptor<OrganizationRoleAssignment> captor = ArgumentCaptor.forClass(OrganizationRoleAssignment.class);
            verify(orgAssignmentRepository).save(captor.capture());
            OrganizationRoleAssignment assignment = captor.getValue();
            assertThat(assignment.getRole()).isEqualTo(memberRoleEntity);
            assertThat(assignment.getOrganization()).isEqualTo(testOrg);
            assertThat(assignment.getUser()).isEqualTo(targetUser);

            verify(cacheService).evictUser(organizationId, targetUserId);
        }

        @Test
        @DisplayName("removeMember deletes role assignments for the removed member")
        void removeMember_deletesRoleAssignmentsForMember() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(activeDeveloperMember));
            when(orgAssignmentRepository.findActiveByOrganizationIdAndUserId(eq(organizationId), eq(targetUserId), any(Instant.class)))
                    .thenReturn(List.of());
            when(orgAssignmentRepository.hasAnyAssignments(organizationId, targetUserId)).thenReturn(true);
            when(teamMemberRepository.existsByUserIdAndRoleAndOrganizationIdAndTeamStatus(
                    targetUserId, TeamRole.LEAD, organizationId, TeamStatus.ACTIVE)).thenReturn(false);

            syncMembershipService.removeMember(organizationId, targetUserId, callerUserId);

            verify(orgAssignmentRepository).deleteByOrganizationIdAndUserId(organizationId, targetUserId);
            verify(organizationMemberRepository).delete(activeDeveloperMember);
            verify(cacheService).evictUser(organizationId, targetUserId);
        }

        @Test
        @DisplayName("removeMember rejects removing sole owner when another owner assignment is expired")
        void removeMember_soleOwner_rejectedWhenAnotherOwnerAssignmentIsExpired() {
            doNothing().when(authorizationService).requireAdminOrOwner(organizationId, callerUserId);
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(activeOwnerMember));

            // targetUser holds active OWNER assignment
            OrganizationRoleAssignment targetAssignment = OrganizationRoleAssignment.builder()
                    .role(ownerRoleEntity)
                    .build();
            when(orgAssignmentRepository.findActiveByOrganizationIdAndUserId(eq(organizationId), eq(targetUserId), any(Instant.class)))
                    .thenReturn(List.of(targetAssignment));

            // countActiveOwners returns 1 because the second owner's assignment has expired
            when(orgAssignmentRepository.hasAnyAssignments(organizationId, targetUserId)).thenReturn(true);
            when(orgAssignmentRepository.countActiveOwners(eq(organizationId), any(Instant.class))).thenReturn(1L);
            when(organizationMemberRepository.findByOrganizationIdAndStatus(organizationId, OrganizationMembershipStatus.ACTIVE))
                    .thenReturn(List.of(activeOwnerMember));

            assertThatThrownBy(() -> syncMembershipService.removeMember(organizationId, targetUserId, callerUserId))
                    .isInstanceOf(LastOrganizationOwnerRemovalException.class)
                    .hasMessageContaining("Cannot remove the last active OWNER");

            verify(organizationMemberRepository, never()).delete(any());
            verify(orgAssignmentRepository, never()).deleteByOrganizationIdAndUserId(any(), any());
        }

        @Test
        @DisplayName("changeMemberRole synchronizes canonical role assignments (revokes old, assigns new)")
        void changeMemberRole_synchronizesRoleAssignments() {
            doNothing().when(authorizationService).requireOwner(organizationId, callerUserId);
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(activeDeveloperMember));
            when(orgAssignmentRepository.findActiveByOrganizationIdAndUserId(eq(organizationId), eq(targetUserId), any(Instant.class)))
                    .thenReturn(List.of());
            when(orgAssignmentRepository.hasAnyAssignments(organizationId, targetUserId)).thenReturn(true);
            when(organizationMemberRepository.save(any(OrganizationMember.class))).thenAnswer(inv -> inv.getArgument(0));

            // Old role DEVELOPER maps to ORGANIZATION_MEMBER
            OrganizationRoleAssignment oldAssignment = OrganizationRoleAssignment.builder()
                    .id(UUID.randomUUID())
                    .organization(testOrg)
                    .user(targetUser)
                    .role(memberRoleEntity)
                    .assignedBy(targetUser)
                    .assignedAt(Instant.now().minusSeconds(3600))
                    .build();

            when(roleRepository.findByName("ORGANIZATION_MEMBER")).thenReturn(Optional.of(memberRoleEntity));
            when(roleRepository.findByName("ORGANIZATION_ADMIN")).thenReturn(Optional.of(adminRoleEntity));
            when(orgAssignmentRepository.findActiveByOrganizationIdAndUserIdAndRoleId(
                    eq(organizationId), eq(targetUserId), eq(memberRoleEntity.getId()), any(Instant.class)))
                    .thenReturn(Optional.of(oldAssignment));
            when(orgAssignmentRepository.hasActiveAssignment(
                    eq(organizationId), eq(targetUserId), eq(adminRoleEntity.getId()), any(Instant.class)))
                    .thenReturn(false);
            when(userRepository.findById(callerUserId)).thenReturn(Optional.of(targetUser));

            OrganizationMember result = syncMembershipService.changeMemberRole(
                    organizationId, targetUserId, OrganizationRole.ADMIN, callerUserId);

            assertThat(result.getRole()).isEqualTo(OrganizationRole.ADMIN);

            // Verify old assignment was revoked and new assignment was saved
            assertThat(oldAssignment.getRevokedAt()).isNotNull();
            assertThat(oldAssignment.getRevocationReason()).isEqualTo("ROLE_CHANGED");
            ArgumentCaptor<OrganizationRoleAssignment> saveCaptor = ArgumentCaptor.forClass(OrganizationRoleAssignment.class);
            verify(orgAssignmentRepository, org.mockito.Mockito.times(2)).save(saveCaptor.capture());
            List<OrganizationRoleAssignment> savedAssignments = saveCaptor.getAllValues();
            assertThat(savedAssignments).contains(oldAssignment);
            assertThat(savedAssignments.stream().anyMatch(a -> a.getRole().equals(adminRoleEntity))).isTrue();

            verify(cacheService).evictUser(organizationId, targetUserId);
        }

        @Test
        @DisplayName("changeMemberRole demote sole owner rejected when another owner assignment is expired")
        void changeMemberRole_demoteSoleOwner_rejectedWhenAnotherOwnerAssignmentIsExpired() {
            doNothing().when(authorizationService).requireOwner(organizationId, callerUserId);
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(activeOwnerMember));

            OrganizationRoleAssignment targetAssignment = OrganizationRoleAssignment.builder()
                    .role(ownerRoleEntity)
                    .build();
            when(orgAssignmentRepository.findActiveByOrganizationIdAndUserId(eq(organizationId), eq(targetUserId), any(Instant.class)))
                    .thenReturn(List.of(targetAssignment));

            // Second owner's assignment expired -> only 1 active owner
            when(orgAssignmentRepository.hasAnyAssignments(organizationId, targetUserId)).thenReturn(true);
            when(orgAssignmentRepository.countActiveOwners(eq(organizationId), any(Instant.class))).thenReturn(1L);
            when(organizationMemberRepository.findByOrganizationIdAndStatus(organizationId, OrganizationMembershipStatus.ACTIVE))
                    .thenReturn(List.of(activeOwnerMember));

            assertThatThrownBy(() -> syncMembershipService.changeMemberRole(
                    organizationId, targetUserId, OrganizationRole.DEVELOPER, callerUserId))
                    .isInstanceOf(LastOrganizationOwnerRemovalException.class)
                    .hasMessageContaining("Cannot demote the last active OWNER");

            verify(organizationMemberRepository, never()).save(any());
        }
    }
}
