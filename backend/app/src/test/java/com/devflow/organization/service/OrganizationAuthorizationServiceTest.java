package com.devflow.organization.service;

import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationMembershipRequiredException;
import com.devflow.organization.repository.OrganizationMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationAuthorizationService Unit Tests")
class OrganizationAuthorizationServiceTest {

    @Mock
    private OrganizationMemberRepository organizationMemberRepository;

    private OrganizationAuthorizationService authorizationService;

    private UUID organizationId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        authorizationService = new OrganizationAuthorizationService(organizationMemberRepository);
        organizationId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    // ── Boolean queries: isMember ─────────────────────────────────────────────

    @Nested
    @DisplayName("isMember")
    class IsMemberTests {

        @Test
        @DisplayName("returns true when active membership exists")
        void isMember_activeMember_returnsTrue() {
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndStatus(
                    organizationId, userId, OrganizationMembershipStatus.ACTIVE))
                    .thenReturn(true);

            assertThat(authorizationService.isMember(organizationId, userId)).isTrue();
        }

        @Test
        @DisplayName("returns false when membership does not exist")
        void isMember_noMembership_returnsFalse() {
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndStatus(
                    organizationId, userId, OrganizationMembershipStatus.ACTIVE))
                    .thenReturn(false);

            assertThat(authorizationService.isMember(organizationId, userId)).isFalse();
        }

        @Test
        @DisplayName("returns false defensively when organizationId is null")
        void isMember_nullOrgId_returnsFalse() {
            assertThat(authorizationService.isMember(null, userId)).isFalse();
            verify(organizationMemberRepository, never()).existsByOrganizationIdAndUserIdAndStatus(any(), any(), any());
        }

        @Test
        @DisplayName("returns false defensively when userId is null")
        void isMember_nullUserId_returnsFalse() {
            assertThat(authorizationService.isMember(organizationId, null)).isFalse();
            verify(organizationMemberRepository, never()).existsByOrganizationIdAndUserIdAndStatus(any(), any(), any());
        }
    }

    // ── Boolean queries: isOwner ──────────────────────────────────────────────

    @Nested
    @DisplayName("isOwner")
    class IsOwnerTests {

        @Test
        @DisplayName("returns true when active OWNER membership exists")
        void isOwner_activeOwner_returnsTrue() {
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndRoleInAndStatus(
                    eq(organizationId), eq(userId), eq(Set.of(OrganizationRole.OWNER)), eq(OrganizationMembershipStatus.ACTIVE)))
                    .thenReturn(true);

            assertThat(authorizationService.isOwner(organizationId, userId)).isTrue();
        }

        @Test
        @DisplayName("returns false when user is not active OWNER")
        void isOwner_notOwner_returnsFalse() {
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndRoleInAndStatus(
                    eq(organizationId), eq(userId), eq(Set.of(OrganizationRole.OWNER)), eq(OrganizationMembershipStatus.ACTIVE)))
                    .thenReturn(false);

            assertThat(authorizationService.isOwner(organizationId, userId)).isFalse();
        }

        @Test
        @DisplayName("returns false defensively when organizationId is null")
        void isOwner_nullOrgId_returnsFalse() {
            assertThat(authorizationService.isOwner(null, userId)).isFalse();
            verify(organizationMemberRepository, never()).existsByOrganizationIdAndUserIdAndRoleInAndStatus(any(), any(), any(), any());
        }

        @Test
        @DisplayName("returns false defensively when userId is null")
        void isOwner_nullUserId_returnsFalse() {
            assertThat(authorizationService.isOwner(organizationId, null)).isFalse();
            verify(organizationMemberRepository, never()).existsByOrganizationIdAndUserIdAndRoleInAndStatus(any(), any(), any(), any());
        }
    }

    // ── Boolean queries: isAdminOrOwner ───────────────────────────────────────

    @Nested
    @DisplayName("isAdminOrOwner")
    class IsAdminOrOwnerTests {

        @Test
        @DisplayName("returns true when user has active OWNER or ADMIN role")
        void isAdminOrOwner_activeAdminOrOwner_returnsTrue() {
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndRoleInAndStatus(
                    eq(organizationId), eq(userId),
                    eq(Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN)),
                    eq(OrganizationMembershipStatus.ACTIVE)))
                    .thenReturn(true);

            assertThat(authorizationService.isAdminOrOwner(organizationId, userId)).isTrue();
        }

        @Test
        @DisplayName("returns false when user lacks ADMIN or OWNER role")
        void isAdminOrOwner_regularMember_returnsFalse() {
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndRoleInAndStatus(
                    eq(organizationId), eq(userId),
                    eq(Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN)),
                    eq(OrganizationMembershipStatus.ACTIVE)))
                    .thenReturn(false);

            assertThat(authorizationService.isAdminOrOwner(organizationId, userId)).isFalse();
        }

        @Test
        @DisplayName("returns false defensively when organizationId is null")
        void isAdminOrOwner_nullOrgId_returnsFalse() {
            assertThat(authorizationService.isAdminOrOwner(null, userId)).isFalse();
            verify(organizationMemberRepository, never()).existsByOrganizationIdAndUserIdAndRoleInAndStatus(any(), any(), any(), any());
        }

        @Test
        @DisplayName("returns false defensively when userId is null")
        void isAdminOrOwner_nullUserId_returnsFalse() {
            assertThat(authorizationService.isAdminOrOwner(organizationId, null)).isFalse();
            verify(organizationMemberRepository, never()).existsByOrganizationIdAndUserIdAndRoleInAndStatus(any(), any(), any(), any());
        }
    }

    // ── Imperative checks: requireMember ──────────────────────────────────────

    @Nested
    @DisplayName("requireMember")
    class RequireMemberTests {

        @Test
        @DisplayName("succeeds when user is an active member")
        void requireMember_activeMember_succeeds() {
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndStatus(
                    organizationId, userId, OrganizationMembershipStatus.ACTIVE))
                    .thenReturn(true);

            assertThatCode(() -> authorizationService.requireMember(organizationId, userId))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws OrganizationMembershipRequiredException when user is not an active member")
        void requireMember_notActiveMember_throwsException() {
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndStatus(
                    organizationId, userId, OrganizationMembershipStatus.ACTIVE))
                    .thenReturn(false);

            assertThatThrownBy(() -> authorizationService.requireMember(organizationId, userId))
                    .isInstanceOf(OrganizationMembershipRequiredException.class)
                    .hasMessageContaining(organizationId.toString());
        }

        @Test
        @DisplayName("fails closed with OrganizationMembershipRequiredException when null arguments provided")
        void requireMember_nullArguments_throwsException() {
            assertThatThrownBy(() -> authorizationService.requireMember(null, userId))
                    .isInstanceOf(OrganizationMembershipRequiredException.class);
        }
    }

    // ── Imperative checks: requireAdminOrOwner ─────────────────────────────────

    @Nested
    @DisplayName("requireAdminOrOwner")
    class RequireAdminOrOwnerTests {

        @Test
        @DisplayName("succeeds when user is active ADMIN or OWNER")
        void requireAdminOrOwner_adminOrOwner_succeeds() {
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndRoleInAndStatus(
                    eq(organizationId), eq(userId),
                    eq(Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN)),
                    eq(OrganizationMembershipStatus.ACTIVE)))
                    .thenReturn(true);

            assertThatCode(() -> authorizationService.requireAdminOrOwner(organizationId, userId))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws OrganizationAccessDeniedException when user lacks ADMIN or OWNER role")
        void requireAdminOrOwner_regularMember_throwsException() {
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndRoleInAndStatus(
                    eq(organizationId), eq(userId),
                    eq(Set.of(OrganizationRole.OWNER, OrganizationRole.ADMIN)),
                    eq(OrganizationMembershipStatus.ACTIVE)))
                    .thenReturn(false);

            assertThatThrownBy(() -> authorizationService.requireAdminOrOwner(organizationId, userId))
                    .isInstanceOf(OrganizationAccessDeniedException.class)
                    .hasMessageContaining("ADMIN or OWNER role required");
        }

        @Test
        @DisplayName("fails closed with OrganizationAccessDeniedException when null arguments provided")
        void requireAdminOrOwner_nullArguments_throwsException() {
            assertThatThrownBy(() -> authorizationService.requireAdminOrOwner(organizationId, null))
                    .isInstanceOf(OrganizationAccessDeniedException.class);
        }
    }

    // ── Imperative checks: requireOwner ───────────────────────────────────────

    @Nested
    @DisplayName("requireOwner")
    class RequireOwnerTests {

        @Test
        @DisplayName("succeeds when user is active OWNER")
        void requireOwner_activeOwner_succeeds() {
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndRoleInAndStatus(
                    eq(organizationId), eq(userId), eq(Set.of(OrganizationRole.OWNER)), eq(OrganizationMembershipStatus.ACTIVE)))
                    .thenReturn(true);

            assertThatCode(() -> authorizationService.requireOwner(organizationId, userId))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws OrganizationAccessDeniedException when user is not active OWNER")
        void requireOwner_notOwner_throwsException() {
            when(organizationMemberRepository.existsByOrganizationIdAndUserIdAndRoleInAndStatus(
                    eq(organizationId), eq(userId), eq(Set.of(OrganizationRole.OWNER)), eq(OrganizationMembershipStatus.ACTIVE)))
                    .thenReturn(false);

            assertThatThrownBy(() -> authorizationService.requireOwner(organizationId, userId))
                    .isInstanceOf(OrganizationAccessDeniedException.class)
                    .hasMessageContaining("OWNER role required");
        }

        @Test
        @DisplayName("fails closed with OrganizationAccessDeniedException when null arguments provided")
        void requireOwner_nullArguments_throwsException() {
            assertThatThrownBy(() -> authorizationService.requireOwner(null, null))
                    .isInstanceOf(OrganizationAccessDeniedException.class);
        }
    }
}
