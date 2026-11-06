package com.devflow.organization.service;

import com.devflow.modules.auth.api.AuthApi;
import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.context.OrganizationAuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.evaluator.MembershipValidator;
import com.devflow.modules.auth.evaluator.RoleResolver;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.organization.domain.OrganizationMember;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationAuthorizationService Unit Tests")
class OrganizationAuthorizationServiceTest {

    @Mock
    private AuthApi authApi;

    @Mock
    private MembershipValidator membershipValidator;

    @Mock
    private RoleResolver roleResolver;

    @Mock
    private OrganizationMemberRepository organizationMemberRepository;

    private OrganizationAuthorizationService authorizationService;

    private UUID organizationId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        authorizationService = new OrganizationAuthorizationService(authApi, membershipValidator, roleResolver);
        organizationId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    // ── Boolean queries: isMember ─────────────────────────────────────────────

    @Nested
    @DisplayName("isMember")
    class IsMemberTests {

        @Test
        @DisplayName("returns true when active membership exists and user holds active organization role")
        void isMember_activeMember_returnsTrue() {
            when(membershipValidator.isActiveMember(userId, organizationId)).thenReturn(true);
            when(roleResolver.resolveRoles(userId, OrganizationAuthorizationContext.of(organizationId)))
                    .thenReturn(Set.of(Role.ORGANIZATION_MEMBER));

            assertThat(authorizationService.isMember(organizationId, userId)).isTrue();
        }

        @Test
        @DisplayName("returns false when membership does not exist or is inactive")
        void isMember_noMembership_returnsFalse() {
            when(membershipValidator.isActiveMember(userId, organizationId)).thenReturn(false);

            assertThat(authorizationService.isMember(organizationId, userId)).isFalse();
            verify(roleResolver, never()).resolveRoles(any(), any());
        }

        @Test
        @DisplayName("returns false when member has no active roles")
        void isMember_noRoles_returnsFalse() {
            when(membershipValidator.isActiveMember(userId, organizationId)).thenReturn(true);
            when(roleResolver.resolveRoles(userId, OrganizationAuthorizationContext.of(organizationId)))
                    .thenReturn(Collections.emptySet());

            assertThat(authorizationService.isMember(organizationId, userId)).isFalse();
        }

        @Test
        @DisplayName("returns false defensively when organizationId is null")
        void isMember_nullOrgId_returnsFalse() {
            assertThat(authorizationService.isMember(null, userId)).isFalse();
            verify(membershipValidator, never()).isActiveMember(any(), any());
        }

        @Test
        @DisplayName("returns false defensively when userId is null")
        void isMember_nullUserId_returnsFalse() {
            assertThat(authorizationService.isMember(organizationId, null)).isFalse();
            verify(membershipValidator, never()).isActiveMember(any(), any());
        }
    }

    // ── Boolean queries: isOwner ──────────────────────────────────────────────

    @Nested
    @DisplayName("isOwner")
    class IsOwnerTests {

        @Test
        @DisplayName("returns true when centralized AuthApi authorizes ORG_DELETE permission")
        void isOwner_activeOwner_returnsTrue() {
            when(authApi.authorize(any(AuthorizationRequest.class)))
                    .thenReturn(AuthorizationDecision.allow());

            assertThat(authorizationService.isOwner(organizationId, userId)).isTrue();

            ArgumentCaptor<AuthorizationRequest> captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
            verify(authApi).authorize(captor.capture());
            AuthorizationRequest req = captor.getValue();
            assertThat(req.userId()).isEqualTo(userId);
            assertThat(req.context().getOrganizationId()).contains(organizationId);
            assertThat(req.permission()).isEqualTo(PermissionRegistry.ORG_DELETE);
        }

        @Test
        @DisplayName("returns false when centralized AuthApi denies ORG_DELETE")
        void isOwner_notOwner_returnsFalse() {
            when(authApi.authorize(any(AuthorizationRequest.class)))
                    .thenReturn(AuthorizationDecision.deny(DenialReason.INSUFFICIENT_PERMISSIONS, "Denied"));

            assertThat(authorizationService.isOwner(organizationId, userId)).isFalse();
        }

        @Test
        @DisplayName("returns false defensively when organizationId is null")
        void isOwner_nullOrgId_returnsFalse() {
            assertThat(authorizationService.isOwner(null, userId)).isFalse();
            verify(authApi, never()).authorize(any(AuthorizationRequest.class));
        }

        @Test
        @DisplayName("returns false defensively when userId is null")
        void isOwner_nullUserId_returnsFalse() {
            assertThat(authorizationService.isOwner(organizationId, null)).isFalse();
            verify(authApi, never()).authorize(any(AuthorizationRequest.class));
        }
    }

    // ── Boolean queries: isAdminOrOwner ───────────────────────────────────────

    @Nested
    @DisplayName("isAdminOrOwner")
    class IsAdminOrOwnerTests {

        @Test
        @DisplayName("returns true when centralized AuthApi authorizes MEMBER_INVITE permission")
        void isAdminOrOwner_activeAdminOrOwner_returnsTrue() {
            when(authApi.authorize(any(AuthorizationRequest.class)))
                    .thenReturn(AuthorizationDecision.allow());

            assertThat(authorizationService.isAdminOrOwner(organizationId, userId)).isTrue();

            ArgumentCaptor<AuthorizationRequest> captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
            verify(authApi).authorize(captor.capture());
            AuthorizationRequest req = captor.getValue();
            assertThat(req.userId()).isEqualTo(userId);
            assertThat(req.context().getOrganizationId()).contains(organizationId);
            assertThat(req.permission()).isEqualTo(PermissionRegistry.MEMBER_INVITE);
        }

        @Test
        @DisplayName("returns false when user lacks ADMIN or OWNER role")
        void isAdminOrOwner_regularMember_returnsFalse() {
            when(authApi.authorize(any(AuthorizationRequest.class)))
                    .thenReturn(AuthorizationDecision.deny(DenialReason.INSUFFICIENT_PERMISSIONS, "Denied"));

            assertThat(authorizationService.isAdminOrOwner(organizationId, userId)).isFalse();
        }

        @Test
        @DisplayName("returns false defensively when organizationId is null")
        void isAdminOrOwner_nullOrgId_returnsFalse() {
            assertThat(authorizationService.isAdminOrOwner(null, userId)).isFalse();
            verify(authApi, never()).authorize(any(AuthorizationRequest.class));
        }

        @Test
        @DisplayName("returns false defensively when userId is null")
        void isAdminOrOwner_nullUserId_returnsFalse() {
            assertThat(authorizationService.isAdminOrOwner(organizationId, null)).isFalse();
            verify(authApi, never()).authorize(any(AuthorizationRequest.class));
        }
    }

    // ── Imperative checks: requireMember ──────────────────────────────────────

    @Nested
    @DisplayName("requireMember")
    class RequireMemberTests {

        @Test
        @DisplayName("succeeds when user is an active member")
        void requireMember_activeMember_succeeds() {
            when(membershipValidator.isActiveMember(userId, organizationId)).thenReturn(true);
            when(roleResolver.resolveRoles(userId, OrganizationAuthorizationContext.of(organizationId)))
                    .thenReturn(Set.of(Role.ORGANIZATION_MEMBER));

            assertThatCode(() -> authorizationService.requireMember(organizationId, userId))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws OrganizationMembershipRequiredException when user is not an active member")
        void requireMember_notActiveMember_throwsException() {
            when(membershipValidator.isActiveMember(userId, organizationId)).thenReturn(false);

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
            when(authApi.authorize(any(AuthorizationRequest.class)))
                    .thenReturn(AuthorizationDecision.allow());

            assertThatCode(() -> authorizationService.requireAdminOrOwner(organizationId, userId))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws OrganizationAccessDeniedException when user lacks ADMIN or OWNER role")
        void requireAdminOrOwner_regularMember_throwsException() {
            when(authApi.authorize(any(AuthorizationRequest.class)))
                    .thenReturn(AuthorizationDecision.deny(DenialReason.INSUFFICIENT_PERMISSIONS, "Denied"));

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
            when(authApi.authorize(any(AuthorizationRequest.class)))
                    .thenReturn(AuthorizationDecision.allow());

            assertThatCode(() -> authorizationService.requireOwner(organizationId, userId))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws OrganizationAccessDeniedException when user is not active OWNER")
        void requireOwner_notOwner_throwsException() {
            when(authApi.authorize(any(AuthorizationRequest.class)))
                    .thenReturn(AuthorizationDecision.deny(DenialReason.INSUFFICIENT_PERMISSIONS, "Denied"));

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

    // ── Canonical permission checks ───────────────────────────────────────────

    @Nested
    @DisplayName("Canonical Permission Queries & Assertions")
    class CanonicalPermissionTests {

        @Test
        @DisplayName("hasPermission delegates to AuthApi with correct context and permission")
        void hasPermission_delegatesToAuthApi() {
            Permission permission = PermissionRegistry.BILLING_VIEW;
            when(authApi.authorize(any(AuthorizationRequest.class)))
                    .thenReturn(AuthorizationDecision.allow());

            assertThat(authorizationService.hasPermission(organizationId, userId, permission)).isTrue();

            ArgumentCaptor<AuthorizationRequest> captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
            verify(authApi).authorize(captor.capture());
            assertThat(captor.getValue().permission()).isEqualTo(permission);
        }

        @Test
        @DisplayName("requirePermission throws OrganizationAccessDeniedException on AuthApi denial")
        void requirePermission_denied_throwsException() {
            Permission permission = PermissionRegistry.BILLING_MANAGE;
            when(authApi.authorize(any(AuthorizationRequest.class)))
                    .thenReturn(AuthorizationDecision.deny(DenialReason.INSUFFICIENT_PERMISSIONS, "Denied"));

            assertThatThrownBy(() -> authorizationService.requirePermission(organizationId, userId, permission))
                    .isInstanceOf(OrganizationAccessDeniedException.class)
                    .hasMessageContaining("billing.manage");
        }
    }

    // ── Compatibility Constructor ─────────────────────────────────────────────

    @Nested
    @DisplayName("Compatibility Constructor")
    class CompatibilityConstructorTests {

        @Test
        @DisplayName("constructs functional service from repository")
        void compatibilityConstructor_worksWithRepository() {
            OrganizationAuthorizationService compatService =
                    new OrganizationAuthorizationService(organizationMemberRepository);

            OrganizationMember member = OrganizationMember.builder()
                    .role(OrganizationRole.OWNER)
                    .status(OrganizationMembershipStatus.ACTIVE)
                    .build();

            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, userId))
                    .thenReturn(Optional.of(member));

            assertThat(compatService.isMember(organizationId, userId)).isTrue();
            assertThat(compatService.isOwner(organizationId, userId)).isTrue();
            assertThat(compatService.isAdminOrOwner(organizationId, userId)).isTrue();
        }
    }
}
