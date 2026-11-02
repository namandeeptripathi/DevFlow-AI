package com.devflow.modules.auth.evaluator;

import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.registry.PermissionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantIsolationTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();
    private static final UUID PROJECT_IN_A = UUID.randomUUID();
    private static final UUID PROJECT_IN_B = UUID.randomUUID();

    @Test
    @DisplayName("Active membership in organization allows access")
    void shouldAllowWhenMembershipIsActive() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> orgId.equals(ORG_A))
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_MEMBER))
                .build();

        AuthorizationRequest request = AuthorizationRequest.organization(
                USER_ID, ORG_A, PermissionRegistry.PROJECT_READ);

        AuthorizationDecision decision = evaluator.authorize(request);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("Inactive, revoked, or missing membership results in DENY MEMBERSHIP_INACTIVE")
    void shouldDenyWhenMembershipIsInactive() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> false) // inactive/revoked
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_MEMBER))
                .build();

        AuthorizationRequest request = AuthorizationRequest.organization(
                USER_ID, ORG_A, PermissionRegistry.PROJECT_READ);

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.MEMBERSHIP_INACTIVE);
    }

    @Test
    @DisplayName("Project belonging to organization allows project-scoped request")
    void shouldAllowWhenProjectBelongsToOrganization() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> orgId.equals(ORG_A))
                .tenantBoundaryValidator(new TenantBoundaryValidator() {
                    @Override
                    public boolean isProjectInOrganization(UUID organizationId, UUID projectId) {
                        return organizationId.equals(ORG_A) && projectId.equals(PROJECT_IN_A);
                    }
                })
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_MEMBER))
                .build();

        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID, ORG_A, PROJECT_IN_A, PermissionRegistry.TASK_CREATE);

        AuthorizationDecision decision = evaluator.authorize(request);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("Project belonging to a different organization results in DENY TENANT_MISMATCH")
    void shouldDenyProjectOrganizationMismatch() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator(new TenantBoundaryValidator() {
                    @Override
                    public boolean isProjectInOrganization(UUID organizationId, UUID projectId) {
                        // Project belongs to ORG_B, not ORG_A
                        return organizationId.equals(ORG_B) && projectId.equals(PROJECT_IN_B);
                    }
                })
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_MEMBER))
                .build();

        // Caller claims PROJECT_IN_B belongs to ORG_A
        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID, ORG_A, PROJECT_IN_B, PermissionRegistry.TASK_CREATE);

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.TENANT_MISMATCH);
        assertThat(decision.message()).contains("does not belong to organization");
    }

    @Test
    @DisplayName("Sub-resource belonging to a different organization results in DENY TENANT_MISMATCH")
    void shouldDenySubResourceTenantMismatch() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator(new TenantBoundaryValidator() {
                    @Override
                    public boolean isProjectInOrganization(UUID organizationId, UUID projectId) {
                        return true;
                    }

                    @Override
                    public Optional<UUID> getResourceOrganizationId(String resourceType, String resourceId) {
                        // Resource belongs to ORG_B
                        return Optional.of(ORG_B);
                    }
                })
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_ADMIN))
                .build();

        // Caller attempts to access resource in ORG_A context
        AuthorizationRequest request = AuthorizationRequest.organization(
                USER_ID, ORG_A, PermissionRegistry.REPO_CONNECT, "foreign-repo-id");

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.TENANT_MISMATCH);
        assertThat(decision.message()).contains("Resource belongs to organization");
    }

    @Test
    @DisplayName("Caller-supplied attributes are never trusted as authoritative membership proof")
    void shouldIgnoreCallerSuppliedAttributesForMembership() {
        // Membership validator rejects user
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> false)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_MEMBER))
                .build();

        // Caller puts fake membership claims in attributes
        AuthorizationRequest request = AuthorizationRequest.organization(
                USER_ID,
                ORG_A,
                PermissionRegistry.PROJECT_READ,
                null,
                Map.of("membershipStatus", "ACTIVE", "isMember", true, "role", "OWNER")
        );

        AuthorizationDecision decision = evaluator.authorize(request);

        // Must still fail closed because trusted port returned false
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.MEMBERSHIP_INACTIVE);
    }

    @Test
    @DisplayName("Missing tenant facts default to fail-closed behavior")
    void shouldFailClosedOnMissingFacts() {
        // No validators provided in builder (default fail-closed stubs)
        AuthorizationEvaluator failClosedEvaluator = AuthorizationEvaluator.builder().build();

        AuthorizationRequest request = AuthorizationRequest.organization(
                USER_ID, ORG_A, PermissionRegistry.PROJECT_READ);

        AuthorizationDecision decision = failClosedEvaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.MEMBERSHIP_INACTIVE);
    }
}

