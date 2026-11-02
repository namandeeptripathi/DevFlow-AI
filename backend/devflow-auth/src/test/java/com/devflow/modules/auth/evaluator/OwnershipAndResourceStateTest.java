package com.devflow.modules.auth.evaluator;

import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.registry.PermissionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OwnershipAndResourceStateTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();

    @Test
    @DisplayName("Owner-gated operation (project.delete by PROJECT_LEAD) succeeds when caller is verified owner")
    void shouldAllowOwnerGatedOperationWhenCallerIsOwner() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_LEAD))
                .resourceOwnershipValidator((userId, context, type, id) -> userId.equals(USER_ID))
                .build();

        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.PROJECT_DELETE, PROJECT_ID.toString());

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("Owner-gated operation (project.delete by PROJECT_LEAD) fails when caller is NOT verified owner")
    void shouldDenyOwnerGatedOperationWhenCallerIsNotOwner() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_LEAD))
                .resourceOwnershipValidator((userId, context, type, id) -> false) // not the owner
                .build();

        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.PROJECT_DELETE, PROJECT_ID.toString());

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.OWNERSHIP_REQUIRED);
    }

    @Test
    @DisplayName("Missing or unverified ownership information fails closed")
    void shouldFailClosedWhenOwnershipCannotBeVerified() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_LEAD))
                .resourceOwnershipValidator(null) // no ownership validator -> default fails closed
                .build();

        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.PROJECT_DELETE, PROJECT_ID.toString());

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.OWNERSHIP_REQUIRED);
    }

    @Test
    @DisplayName("Ownership is not inferred from caller-supplied request attributes")
    void shouldIgnoreCallerSuppliedOwnershipAttributes() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_LEAD))
                .resourceOwnershipValidator((userId, context, type, id) -> false) // authoritative port says false
                .build();

        // Caller attempts to spoof ownership in request attributes
        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID,
                ORG_ID,
                PROJECT_ID,
                PermissionRegistry.PROJECT_DELETE,
                PROJECT_ID.toString(),
                Map.of("isOwner", true, "creatorId", USER_ID, "owner", "true")
        );

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.OWNERSHIP_REQUIRED);
    }

    @Test
    @DisplayName("Ownership does not grant unrelated permissions (e.g. project owner cannot repo.connect without role)")
    void ownershipMustNotGrantUnrelatedPermissions() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_MEMBER))
                .resourceOwnershipValidator((userId, context, type, id) -> true) // is owner
                .build();

        // repo.connect is an ADMIN/OWNER permission, not granted to PROJECT_MEMBER
        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.REPO_CONNECT);

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.INSUFFICIENT_PERMISSIONS);
    }

    @Test
    @DisplayName("Ownership cannot bypass tenant boundary isolation")
    void ownershipCannotBypassTenantBoundary() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                // Tenant check fails: project does not belong to organization
                .tenantBoundaryValidator((orgId, projId) -> false)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_LEAD))
                .resourceOwnershipValidator((userId, context, type, id) -> true) // is owner
                .build();

        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.PROJECT_DELETE, PROJECT_ID.toString());

        AuthorizationDecision decision = evaluator.authorize(request);

        // Tenant check precedes ownership check!
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.TENANT_MISMATCH);
    }

    @Test
    @DisplayName("Resource-state restrictions block operation even for ORGANIZATION_OWNER: DENY RESOURCE_STATE_CONFLICT")
    void resourceStateRestrictionsMustOverrideRoles() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_OWNER))
                // Resource is archived / locked
                .resourceStateValidator((context, type, id, perm) -> false)
                .build();

        AuthorizationRequest request = AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.PROJECT_WRITE, PROJECT_ID.toString());

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.RESOURCE_STATE_CONFLICT);
    }

    @Test
    @DisplayName("Resource-state permits operation when state check returns true")
    void resourceStatePermitsOperationWhenValid() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_OWNER))
                .resourceStateValidator((context, type, id, perm) -> true)
                .build();

        AuthorizationRequest request = AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.PROJECT_WRITE, PROJECT_ID.toString());

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("Ownership alternative: task.delete allowed for task creator with PROJECT_MEMBER role")
    void shouldAllowTaskDeleteByCreator() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_MEMBER))
                .resourceOwnershipValidator((userId, context, type, id) -> "task".equals(type) && userId.equals(USER_ID))
                .build();

        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.TASK_DELETE, "task-123");

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("Ownership alternative: task.delete denied when PROJECT_MEMBER is not the task creator")
    void shouldDenyTaskDeleteWhenNotCreator() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_MEMBER))
                .resourceOwnershipValidator((userId, context, type, id) -> false)
                .build();

        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.TASK_DELETE, "task-123");

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.OWNERSHIP_REQUIRED);
    }

    @Test
    @DisplayName("Ownership alternative: comment.edit and comment.delete allowed for comment author")
    void shouldAllowCommentEditAndDeleteByAuthor() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_MEMBER))
                .resourceOwnershipValidator((userId, context, type, id) -> "comment".equals(type) && userId.equals(USER_ID))
                .build();

        AuthorizationRequest editRequest = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.COMMENT_EDIT, "comment-456");
        AuthorizationRequest deleteRequest = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.COMMENT_DELETE, "comment-456");

        assertThat(evaluator.authorize(editRequest).allowed()).isTrue();
        assertThat(evaluator.authorize(deleteRequest).allowed()).isTrue();
    }

    @Test
    @DisplayName("Ownership alternative: kb.delete allowed for article author with PROJECT_MEMBER role")
    void shouldAllowKbDeleteByAuthor() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_MEMBER))
                .resourceOwnershipValidator((userId, context, type, id) -> "kb".equals(type) && userId.equals(USER_ID))
                .build();

        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.KB_DELETE, "kb-doc-789");

        assertThat(evaluator.authorize(request).allowed()).isTrue();
    }

    @Test
    @DisplayName("Ownership alternative: repo.disconnect allowed for connection owner with PROJECT_MEMBER role")
    void shouldAllowRepoDisconnectByConnectionOwner() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_MEMBER))
                .resourceOwnershipValidator((userId, context, type, id) -> "repo".equals(type) && userId.equals(USER_ID))
                .build();

        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.REPO_DISCONNECT, "repo-101");

        assertThat(evaluator.authorize(request).allowed()).isTrue();
    }

    @Test
    @DisplayName("Ownership alternative: automation.delete allowed for rule owner with PROJECT_MEMBER role")
    void shouldAllowAutomationDeleteByRuleOwner() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_MEMBER))
                .resourceOwnershipValidator((userId, context, type, id) -> "automation".equals(type) && userId.equals(USER_ID))
                .build();

        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.AUTOMATION_DELETE, "auto-202");

        assertThat(evaluator.authorize(request).allowed()).isTrue();
    }
}

