package com.devflow.modules.auth.evaluator;

import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.CompositeAuthorizationRequest;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.registry.PermissionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompositePermissionTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();

    private AuthorizationEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_ADMIN))
                .build();
    }

    @Test
    @DisplayName("Composite check succeeds when user holds all required permissions (AND semantics)")
    void shouldAuthorizeWhenAllPermissionsGranted() {
        // ORGANIZATION_ADMIN holds both repo.connect AND analytics.export
        CompositeAuthorizationRequest request = CompositeAuthorizationRequest.organization(
                USER_ID, ORG_ID, Set.of(PermissionRegistry.REPO_CONNECT, PermissionRegistry.ANALYTICS_EXPORT));

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isTrue();
        assertThat(evaluator.isAuthorized(request)).isTrue();
        assertThat(evaluator.isAuthorizedAll(USER_ID, ORG_ID, List.of(
                PermissionRegistry.REPO_CONNECT, PermissionRegistry.ANALYTICS_EXPORT))).isTrue();
    }

    @Test
    @DisplayName("Composite check fails when even ONE required permission is missing (strict AND, not OR)")
    void shouldDenyWhenAnyRequiredPermissionIsMissing() {
        // ORGANIZATION_ADMIN holds repo.connect, but DOES NOT hold org.manage (OWNER only)
        CompositeAuthorizationRequest request = CompositeAuthorizationRequest.organization(
                USER_ID, ORG_ID, Set.of(PermissionRegistry.REPO_CONNECT, PermissionRegistry.ORG_MANAGE));

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.INSUFFICIENT_PERMISSIONS);
        assertThat(evaluator.isAuthorized(request)).isFalse();
        assertThat(evaluator.isAuthorizedAll(USER_ID, ORG_ID, List.of(
                PermissionRegistry.REPO_CONNECT, PermissionRegistry.ORG_MANAGE))).isFalse();
    }

    @Test
    @DisplayName("Composite check fails closed when an unknown permission is requested")
    void shouldDenyWhenUnknownPermissionInCompositeRequest() {
        Permission unknownPermission = Permission.of("custom", "unregistered");
        CompositeAuthorizationRequest request = CompositeAuthorizationRequest.organization(
                USER_ID, ORG_ID, Set.of(PermissionRegistry.REPO_CONNECT, unknownPermission));

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.UNKNOWN_PERMISSION);
    }

    @Test
    @DisplayName("Composite check with empty permissions list fails closed with GENERAL_DENIED")
    void shouldDenyWhenPermissionsListIsEmpty() {
        AuthorizationDecision decision = evaluator.authorizeAll(
                USER_ID,
                AuthorizationContext.organization(ORG_ID),
                Collections.emptyList()
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.GENERAL_DENIED);
        assertThat(decision.message()).contains("No permissions specified");
    }

    @Test
    @DisplayName("Composite check fails if resource state blocks any single required permission")
    void shouldDenyWhenResourceStateBlocksOnePermissionInComposite() {
        evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_ADMIN))
                .resourceStateValidator((context, type, id, perm) -> {
                    // Block only repo.connect due to maintenance lock
                    return !PermissionRegistry.REPO_CONNECT.equals(perm);
                })
                .build();

        CompositeAuthorizationRequest request = CompositeAuthorizationRequest.organization(
                USER_ID, ORG_ID, Set.of(PermissionRegistry.PROJECT_READ, PermissionRegistry.REPO_CONNECT));

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.RESOURCE_STATE_CONFLICT);
    }
}

