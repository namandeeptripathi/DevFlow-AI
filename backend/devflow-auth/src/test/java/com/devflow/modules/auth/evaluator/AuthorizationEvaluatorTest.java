package com.devflow.modules.auth.evaluator;

import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.api.CompositeAuthorizationRequest;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.registry.PermissionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationEvaluatorTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();

    private AuthorizationEvaluator evaluator;
    private RoleResolver roleResolver;
    private MembershipValidator membershipValidator;
    private TenantBoundaryValidator tenantBoundaryValidator;

    @BeforeEach
    void setUp() {
        membershipValidator = (userId, orgId) -> true;
        tenantBoundaryValidator = (orgId, projId) -> true;
        roleResolver = (userId, context) -> Set.of(Role.ORGANIZATION_MEMBER);

        evaluator = AuthorizationEvaluator.builder()
                .membershipValidator(membershipValidator)
                .tenantBoundaryValidator(tenantBoundaryValidator)
                .roleResolver(roleResolver)
                .build();
    }

    @Test
    @DisplayName("Evaluate organization request with permitted role: ALLOW")
    void shouldAuthorizePermittedOrganizationRequest() {
        AuthorizationRequest request = AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.PROJECT_READ);

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.denialReason()).isNull();
        assertThat(decision.message()).isEqualTo("Access granted");
        assertThat(evaluator.isAuthorized(request)).isTrue();
    }

    @Test
    @DisplayName("Evaluate organization request with insufficient role: DENY INSUFFICIENT_PERMISSIONS")
    void shouldDenyWhenRoleLacksPermission() {
        // ORGANIZATION_MEMBER does not hold member.invite
        AuthorizationRequest request = AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.MEMBER_INVITE);

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.INSUFFICIENT_PERMISSIONS);
        assertThat(evaluator.isAuthorized(request)).isFalse();
    }

    @Test
    @DisplayName("Reject unknown permission: DENY UNKNOWN_PERMISSION")
    void shouldDenyUnknownPermission() {
        Permission unregisteredPermission = Permission.of("nonexistent", "action");
        AuthorizationRequest request = AuthorizationRequest.organization(
                USER_ID, ORG_ID, unregisteredPermission);

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.UNKNOWN_PERMISSION);
        assertThat(decision.message()).contains("nonexistent.action").contains("not registered");
    }

    @Test
    @DisplayName("Default deny behavior when user has no roles assigned: DENY INSUFFICIENT_PERMISSIONS")
    void shouldDenyWhenNoRolesAssigned() {
        evaluator = AuthorizationEvaluator.builder()
                .membershipValidator(membershipValidator)
                .tenantBoundaryValidator(tenantBoundaryValidator)
                .roleResolver((userId, context) -> Set.of())
                .build();

        AuthorizationRequest request = AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.PROJECT_READ);

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.INSUFFICIENT_PERMISSIONS);
        assertThat(decision.message()).contains("no assigned roles");
    }

    @Test
    @DisplayName("Cross-scope role escalation prevention: role scope mismatch results in DENY SCOPE_MISMATCH")
    void shouldPreventCrossScopeRoleEscalation() {
        // User holds only PROJECT_LEAD (PROJECT scope), but attempts ORGANIZATION scoped operation
        evaluator = AuthorizationEvaluator.builder()
                .membershipValidator(membershipValidator)
                .tenantBoundaryValidator(tenantBoundaryValidator)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_LEAD))
                .build();

        AuthorizationRequest request = AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.PROJECT_READ);

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.SCOPE_MISMATCH);
        assertThat(decision.message()).contains("do not match the required authorization scope");
    }

    @Test
    @DisplayName("Multiple roles assigned to user combine via permission union to grant access")
    void shouldGrantAccessViaMultipleRoleUnion() {
        evaluator = AuthorizationEvaluator.builder()
                .membershipValidator(membershipValidator)
                .tenantBoundaryValidator(tenantBoundaryValidator)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_VIEWER, Role.ORGANIZATION_MEMBER))
                .build();

        // task.create is not in VIEWER, but is in MEMBER
        AuthorizationRequest request = AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.TASK_CREATE);

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("Scope hierarchy: ORGANIZATION_OWNER inherits all lower organization roles")
    void shouldInheritPermissionsWithinScope() {
        evaluator = AuthorizationEvaluator.builder()
                .membershipValidator(membershipValidator)
                .tenantBoundaryValidator(tenantBoundaryValidator)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_OWNER))
                .build();

        // Check grants from MEMBER (project.write), ADMIN (repo.connect), and OWNER (org.manage)
        assertThat(evaluator.isAuthorized(AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.PROJECT_WRITE))).isTrue();
        assertThat(evaluator.isAuthorized(AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.REPO_CONNECT))).isTrue();
        assertThat(evaluator.isAuthorized(AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.ORG_MANAGE))).isTrue();
    }

    @Test
    @DisplayName("Organization billing role isolation: grants billing view/manage, denies engineering operations")
    void shouldEnforceOrganizationBillingIsolation() {
        evaluator = AuthorizationEvaluator.builder()
                .membershipValidator(membershipValidator)
                .tenantBoundaryValidator(tenantBoundaryValidator)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_BILLING))
                .build();

        // Billing operations allowed
        assertThat(evaluator.isAuthorized(AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.BILLING_VIEW))).isTrue();
        assertThat(evaluator.isAuthorized(AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.BILLING_MANAGE))).isTrue();

        // Engineering and governance operations denied
        assertThat(evaluator.isAuthorized(AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.PROJECT_READ))).isFalse();
        assertThat(evaluator.isAuthorized(AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.ORG_MANAGE))).isFalse();
    }

    @Test
    @DisplayName("Platform support security gate: succeeds when support case is verified for tenant")
    void shouldAuthorizePlatformSupportWhenCaseIsVerified() {
        String caseId = "CASE-12345";
        evaluator = AuthorizationEvaluator.builder()
                .roleResolver((userId, context) -> Set.of(Role.PLATFORM_SUPPORT))
                .platformSupportValidator((userId, targetOrgId, supportCaseId) ->
                        targetOrgId.equals(ORG_ID) && caseId.equals(supportCaseId))
                .build();

        AuthorizationRequest request = AuthorizationRequest.platform(
                USER_ID,
                PermissionRegistry.PLATFORM_SUPPORT_CASE_READ,
                Map.of("targetOrganizationId", ORG_ID, "supportCaseId", caseId)
        );

        AuthorizationDecision decision = evaluator.authorize(request);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("Platform support security gate: fails closed when support case is invalid, unverified, or missing validator")
    void shouldDenyPlatformSupportWhenCaseUnverified() {
        evaluator = AuthorizationEvaluator.builder()
                .roleResolver((userId, context) -> Set.of(Role.PLATFORM_SUPPORT))
                .platformSupportValidator((userId, targetOrgId, supportCaseId) -> false) // unverified
                .build();

        AuthorizationRequest request = AuthorizationRequest.platform(
                USER_ID,
                PermissionRegistry.PLATFORM_SUPPORT_CASE_READ,
                Map.of("targetOrganizationId", ORG_ID, "supportCaseId", "CASE-UNKNOWN")
        );

        AuthorizationDecision decision = evaluator.authorize(request);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.SUPPORT_CASE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("Emergency access security gate: succeeds when out-of-band approval is verified within 4-hour window")
    void shouldAuthorizeEmergencyAccessWhenVerified() {
        evaluator = AuthorizationEvaluator.builder()
                .roleResolver((userId, context) -> Set.of(Role.PLATFORM_ADMIN))
                .emergencyAccessValidator((userId, evalTime) -> true)
                .build();

        AuthorizationRequest request = AuthorizationRequest.platform(
                USER_ID, PermissionRegistry.PLATFORM_EMERGENCY_ACCESS_EXECUTE);

        AuthorizationDecision decision = evaluator.authorize(request);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("Emergency access security gate: fails closed when approval is missing, expired, or validator missing")
    void shouldDenyEmergencyAccessWhenUnapprovedOrExpired() {
        evaluator = AuthorizationEvaluator.builder()
                .roleResolver((userId, context) -> Set.of(Role.PLATFORM_ADMIN))
                .emergencyAccessValidator((userId, evalTime) -> false) // window expired / unapproved
                .build();

        AuthorizationRequest request = AuthorizationRequest.platform(
                USER_ID, PermissionRegistry.PLATFORM_EMERGENCY_ACCESS_EXECUTE);

        AuthorizationDecision decision = evaluator.authorize(request);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.EMERGENCY_ACCESS_NOT_APPROVED);
    }

    @Test
    @DisplayName("Organization owner and admin inherit project administration into all projects in organization")
    void shouldInheritProjectAdministrationForOwnerAndAdmin() {
        // Test Owner inheritance
        AuthorizationEvaluator ownerEvaluator = AuthorizationEvaluator.builder()
                .membershipValidator(membershipValidator)
                .tenantBoundaryValidator(tenantBoundaryValidator)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_OWNER))
                .build();

        AuthorizationRequest projectWriteReq = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.PROJECT_WRITE);
        assertThat(ownerEvaluator.isAuthorized(projectWriteReq)).isTrue();

        // Test Admin inheritance
        AuthorizationEvaluator adminEvaluator = AuthorizationEvaluator.builder()
                .membershipValidator(membershipValidator)
                .tenantBoundaryValidator(tenantBoundaryValidator)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_ADMIN))
                .build();

        assertThat(adminEvaluator.isAuthorized(projectWriteReq)).isTrue();
    }

    @Test
    @DisplayName("Organization viewer inherits read-only access into projects, cannot modify project resources")
    void shouldInheritReadOnlyProjectAccessForViewer() {
        evaluator = AuthorizationEvaluator.builder()
                .membershipValidator(membershipValidator)
                .tenantBoundaryValidator(tenantBoundaryValidator)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_VIEWER))
                .build();

        // Read operations permitted
        assertThat(evaluator.isAuthorized(AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.PROJECT_READ))).isTrue();
        assertThat(evaluator.isAuthorized(AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.TASK_READ))).isTrue();

        // Write operations denied
        AuthorizationDecision writeDecision = evaluator.authorize(AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.PROJECT_WRITE));
        assertThat(writeDecision.allowed()).isFalse();
        assertThat(writeDecision.denialReason()).isEqualTo(DenialReason.INSUFFICIENT_PERMISSIONS);
    }

    @Test
    @DisplayName("Organization member without project assignment is denied project access: DENY PROJECT_ASSIGNMENT_REQUIRED")
    void shouldDenyOrganizationMemberWithoutProjectAssignment() {
        evaluator = AuthorizationEvaluator.builder()
                .membershipValidator(membershipValidator)
                .tenantBoundaryValidator(tenantBoundaryValidator)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_MEMBER)) // no project role
                .projectAssignmentValidator((userId, orgId, projId) -> false) // not assigned
                .build();

        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.TASK_CREATE);

        AuthorizationDecision decision = evaluator.authorize(request);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.PROJECT_ASSIGNMENT_REQUIRED);
    }

    @Test
    @DisplayName("Organization member with verified project assignment or project role is allowed access")
    void shouldAllowOrganizationMemberWithProjectAssignment() {
        // Case 1: Via explicit project role
        AuthorizationEvaluator roleEvaluator = AuthorizationEvaluator.builder()
                .membershipValidator(membershipValidator)
                .tenantBoundaryValidator(tenantBoundaryValidator)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_MEMBER, Role.PROJECT_MEMBER))
                .build();

        assertThat(roleEvaluator.isAuthorized(AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.TASK_CREATE))).isTrue();

        // Case 2: Via project assignment validator port
        AuthorizationEvaluator assignmentEvaluator = AuthorizationEvaluator.builder()
                .membershipValidator(membershipValidator)
                .tenantBoundaryValidator(tenantBoundaryValidator)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_MEMBER))
                .projectAssignmentValidator((userId, orgId, projId) -> true)
                .build();

        assertThat(assignmentEvaluator.isAuthorized(AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.TASK_CREATE))).isTrue();
    }

    @Test
    @DisplayName("Handle null request gracefully: fails closed with GENERAL_DENIED")
    void shouldHandleNullRequest() {
        AuthorizationDecision decision = evaluator.authorize((AuthorizationRequest) null);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.GENERAL_DENIED);
        assertThat(decision.message()).contains("must not be null");

        AuthorizationDecision compositeDecision = evaluator.authorize((CompositeAuthorizationRequest) null);
        assertThat(compositeDecision.allowed()).isFalse();
        assertThat(compositeDecision.denialReason()).isEqualTo(DenialReason.GENERAL_DENIED);
    }

    @Test
    @DisplayName("Convenience isAuthorized overloads delegate accurately")
    void shouldSupportConvenienceOverloads() {
        assertThat(evaluator.isAuthorized(USER_ID, ORG_ID, PermissionRegistry.PROJECT_READ)).isTrue();
        assertThat(evaluator.isAuthorized(USER_ID, ORG_ID, PermissionRegistry.ORG_MANAGE)).isFalse();
    }
}
