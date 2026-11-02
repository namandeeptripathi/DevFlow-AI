package com.devflow.modules.auth.registry;

import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.domain.RoleScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.devflow.modules.auth.registry.PermissionRegistry.AI_CHAT;
import static com.devflow.modules.auth.registry.PermissionRegistry.AI_CONFIGURE;
import static com.devflow.modules.auth.registry.PermissionRegistry.AI_REVIEW;
import static com.devflow.modules.auth.registry.PermissionRegistry.AI_REVIEW_APPROVE;
import static com.devflow.modules.auth.registry.PermissionRegistry.ANALYTICS_CONFIGURE;
import static com.devflow.modules.auth.registry.PermissionRegistry.ANALYTICS_EXPORT;
import static com.devflow.modules.auth.registry.PermissionRegistry.ANALYTICS_VIEW;
import static com.devflow.modules.auth.registry.PermissionRegistry.AUTOMATION_CONFIGURE;
import static com.devflow.modules.auth.registry.PermissionRegistry.AUTOMATION_DELETE;
import static com.devflow.modules.auth.registry.PermissionRegistry.AUTOMATION_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.BILLING_MANAGE;
import static com.devflow.modules.auth.registry.PermissionRegistry.BILLING_VIEW;
import static com.devflow.modules.auth.registry.PermissionRegistry.COMMENT_CREATE;
import static com.devflow.modules.auth.registry.PermissionRegistry.COMMENT_DELETE;
import static com.devflow.modules.auth.registry.PermissionRegistry.COMMENT_EDIT;
import static com.devflow.modules.auth.registry.PermissionRegistry.COMMENT_MODERATE;
import static com.devflow.modules.auth.registry.PermissionRegistry.CYCLE_MANAGE;
import static com.devflow.modules.auth.registry.PermissionRegistry.KB_DELETE;
import static com.devflow.modules.auth.registry.PermissionRegistry.KB_MANAGE;
import static com.devflow.modules.auth.registry.PermissionRegistry.KB_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.KB_WRITE;
import static com.devflow.modules.auth.registry.PermissionRegistry.MEMBER_INVITE;
import static com.devflow.modules.auth.registry.PermissionRegistry.MEMBER_REMOVE;
import static com.devflow.modules.auth.registry.PermissionRegistry.NOTIFICATION_BROADCAST;
import static com.devflow.modules.auth.registry.PermissionRegistry.NOTIFICATION_CONFIGURE;
import static com.devflow.modules.auth.registry.PermissionRegistry.ORG_DELETE;
import static com.devflow.modules.auth.registry.PermissionRegistry.ORG_MANAGE;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_AUDIT_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_EMERGENCY_ACCESS_EXECUTE;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_FEATUREFLAG_MANAGE;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_OBSERVABILITY_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_SUPPORT_CASE_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_SUPPORT_TENANT_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_TENANT_DEPROVISION;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_TENANT_PROVISION;
import static com.devflow.modules.auth.registry.PermissionRegistry.PROJECT_DELETE;
import static com.devflow.modules.auth.registry.PermissionRegistry.PROJECT_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.PROJECT_WRITE;
import static com.devflow.modules.auth.registry.PermissionRegistry.REPO_CONFIGURE;
import static com.devflow.modules.auth.registry.PermissionRegistry.REPO_CONNECT;
import static com.devflow.modules.auth.registry.PermissionRegistry.REPO_DISCONNECT;
import static com.devflow.modules.auth.registry.PermissionRegistry.REPO_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.REPO_SCAN;
import static com.devflow.modules.auth.registry.PermissionRegistry.TASK_ASSIGN;
import static com.devflow.modules.auth.registry.PermissionRegistry.TASK_CREATE;
import static com.devflow.modules.auth.registry.PermissionRegistry.TASK_DELETE;
import static com.devflow.modules.auth.registry.PermissionRegistry.TASK_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.TASK_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

class RolePermissionMappingTest {

    private final RolePermissionMapping mapping = RolePermissionMapping.canonical();

    @Test
    @DisplayName("Verify ORGANIZATION_VIEWER grants (§5.2, §6.5, §9, ADR-001)")
    void shouldMapOrganizationViewerPermissions() {
        Set<Permission> permissions = mapping.getPermissions(Role.ORGANIZATION_VIEWER);

        assertThat(permissions).containsExactlyInAnyOrder(
                PROJECT_READ,
                TASK_READ,
                REPO_READ,
                KB_READ,
                ANALYTICS_VIEW
        );
    }

    @Test
    @DisplayName("Verify ORGANIZATION_MEMBER grants (inherits VIEWER)")
    void shouldMapOrganizationMemberPermissions() {
        Set<Permission> permissions = mapping.getPermissions(Role.ORGANIZATION_MEMBER);

        assertThat(permissions).contains(
                PROJECT_READ,
                TASK_READ,
                REPO_READ,
                KB_READ,
                ANALYTICS_VIEW,
                PROJECT_WRITE,
                TASK_CREATE,
                TASK_WRITE,
                TASK_ASSIGN,
                REPO_SCAN,
                AI_CHAT,
                AI_REVIEW,
                KB_WRITE,
                AUTOMATION_READ,
                COMMENT_CREATE
        );
    }

    @Test
    @DisplayName("Verify ORGANIZATION_ADMIN grants (inherits MEMBER, excludes billing and org deletion)")
    void shouldMapOrganizationAdminPermissions() {
        Set<Permission> permissions = mapping.getPermissions(Role.ORGANIZATION_ADMIN);

        assertThat(permissions).contains(
                PROJECT_DELETE,
                TASK_DELETE,
                CYCLE_MANAGE,
                REPO_CONNECT,
                REPO_DISCONNECT,
                REPO_CONFIGURE,
                AI_REVIEW_APPROVE,
                AI_CONFIGURE,
                KB_MANAGE,
                KB_DELETE,
                ANALYTICS_EXPORT,
                ANALYTICS_CONFIGURE,
                AUTOMATION_CONFIGURE,
                AUTOMATION_DELETE,
                COMMENT_MODERATE,
                MEMBER_INVITE,
                MEMBER_REMOVE,
                NOTIFICATION_CONFIGURE,
                NOTIFICATION_BROADCAST
        );

        // Crucial security invariant: Admin does not receive owner-exclusive billing or org deletion
        assertThat(permissions).doesNotContain(ORG_MANAGE, ORG_DELETE, BILLING_VIEW, BILLING_MANAGE);
    }

    @Test
    @DisplayName("Verify ORGANIZATION_OWNER grants (inherits ADMIN + org governance & billing)")
    void shouldMapOrganizationOwnerPermissions() {
        Set<Permission> permissions = mapping.getPermissions(Role.ORGANIZATION_OWNER);

        assertThat(permissions).contains(
                ORG_MANAGE,
                ORG_DELETE,
                BILLING_VIEW,
                BILLING_MANAGE,
                PROJECT_DELETE,
                MEMBER_INVITE,
                MEMBER_REMOVE
        );
    }

    @Test
    @DisplayName("Verify ORGANIZATION_BILLING isolation: billing view/manage ONLY, no engineering grants")
    void shouldEnforceOrganizationBillingRoleIsolation() {
        Set<Permission> permissions = mapping.getPermissions(Role.ORGANIZATION_BILLING);

        assertThat(permissions).containsExactlyInAnyOrder(
                BILLING_VIEW,
                BILLING_MANAGE
        );

        // Security check: must not contain any engineering or platform permissions
        assertThat(permissions).doesNotContain(
                PROJECT_READ, PROJECT_WRITE, TASK_CREATE, REPO_CONNECT, AI_CHAT, ORG_MANAGE, ORG_DELETE
        );
    }

    @Test
    @DisplayName("Verify PLATFORM_SUPPORT grants: read-only support cases and tenant inspection only")
    void shouldMapPlatformSupportPermissions() {
        Set<Permission> permissions = mapping.getPermissions(Role.PLATFORM_SUPPORT);

        assertThat(permissions).containsExactlyInAnyOrder(
                PLATFORM_SUPPORT_CASE_READ,
                PLATFORM_SUPPORT_TENANT_READ
        );

        // Must remain strictly read-only and contain no modification or emergency permissions
        assertThat(permissions).doesNotContain(
                PLATFORM_TENANT_PROVISION,
                PLATFORM_TENANT_DEPROVISION,
                PLATFORM_FEATUREFLAG_MANAGE,
                PLATFORM_EMERGENCY_ACCESS_EXECUTE
        );
    }

    @Test
    @DisplayName("Verify PLATFORM_ADMIN grants all 8 platform permissions")
    void shouldMapPlatformAdminPermissions() {
        Set<Permission> permissions = mapping.getPermissions(Role.PLATFORM_ADMIN);

        assertThat(permissions).containsExactlyInAnyOrder(
                PLATFORM_TENANT_PROVISION,
                PLATFORM_TENANT_DEPROVISION,
                PLATFORM_FEATUREFLAG_MANAGE,
                PLATFORM_OBSERVABILITY_READ,
                PLATFORM_AUDIT_READ,
                PLATFORM_SUPPORT_CASE_READ,
                PLATFORM_SUPPORT_TENANT_READ,
                PLATFORM_EMERGENCY_ACCESS_EXECUTE
        );
    }

    @Test
    @DisplayName("Verify PROJECT_VIEWER grants (§5.3, §9.1, §9.2)")
    void shouldMapProjectViewerPermissions() {
        Set<Permission> permissions = mapping.getPermissions(Role.PROJECT_VIEWER);

        assertThat(permissions).containsExactlyInAnyOrder(
                PROJECT_READ,
                TASK_READ,
                KB_READ,
                ANALYTICS_VIEW
        );
    }

    @Test
    @DisplayName("Verify PROJECT_MEMBER grants (inherits VIEWER)")
    void shouldMapProjectMemberPermissions() {
        Set<Permission> permissions = mapping.getPermissions(Role.PROJECT_MEMBER);

        assertThat(permissions).contains(
                PROJECT_READ,
                TASK_READ,
                TASK_CREATE,
                TASK_WRITE,
                REPO_SCAN,
                REPO_READ,
                AI_CHAT,
                AI_REVIEW,
                KB_WRITE,
                AUTOMATION_READ,
                COMMENT_CREATE
        );
    }

    @Test
    @DisplayName("Verify PROJECT_LEAD grants (inherits MEMBER)")
    void shouldMapProjectLeadPermissions() {
        Set<Permission> permissions = mapping.getPermissions(Role.PROJECT_LEAD);

        assertThat(permissions).contains(
                PROJECT_WRITE,
                PROJECT_DELETE,
                TASK_ASSIGN,
                TASK_DELETE,
                CYCLE_MANAGE,
                AI_REVIEW_APPROVE,
                KB_MANAGE,
                KB_DELETE,
                ANALYTICS_EXPORT,
                AUTOMATION_CONFIGURE,
                COMMENT_MODERATE
        );
    }

    @Test
    @DisplayName("Verify Organization-to-Project scope inheritance rules (ADR-001)")
    void shouldValidateOrganizationToProjectInheritance() {
        // Owner and Admin inherit project administrative rights
        Set<Permission> ownerProjectInherited = mapping.getInheritedProjectPermissions(Role.ORGANIZATION_OWNER);
        assertThat(ownerProjectInherited).contains(PROJECT_WRITE, PROJECT_DELETE, TASK_CREATE, REPO_CONNECT);
        assertThat(ownerProjectInherited).doesNotContain(ORG_MANAGE, ORG_DELETE, BILLING_MANAGE);

        Set<Permission> adminProjectInherited = mapping.getInheritedProjectPermissions(Role.ORGANIZATION_ADMIN);
        assertThat(adminProjectInherited).contains(PROJECT_WRITE, PROJECT_DELETE, TASK_CREATE, REPO_CONNECT);
        assertThat(adminProjectInherited).doesNotContain(ORG_MANAGE, ORG_DELETE, BILLING_MANAGE);

        // Viewer inherits read-only rights
        Set<Permission> viewerProjectInherited = mapping.getInheritedProjectPermissions(Role.ORGANIZATION_VIEWER);
        assertThat(viewerProjectInherited).containsExactlyInAnyOrder(
                PROJECT_READ, TASK_READ, REPO_READ, KB_READ, ANALYTICS_VIEW
        );
        assertThat(viewerProjectInherited).doesNotContain(PROJECT_WRITE, TASK_CREATE);

        // Member and Billing do NOT inherit project rights automatically
        assertThat(mapping.getInheritedProjectPermissions(Role.ORGANIZATION_MEMBER)).isEmpty();
        assertThat(mapping.getInheritedProjectPermissions(Role.ORGANIZATION_BILLING)).isEmpty();
    }

    @Test
    @DisplayName("Role hierarchy never crosses scope boundaries")
    void roleHierarchyMustNeverCrossScopeBoundaries() {
        for (Role role : Role.values()) {
            Set<Role> inheritedRoles = mapping.getInheritedRoles(role);
            for (Role inherited : inheritedRoles) {
                assertThat(inherited.getScope())
                        .as("Inherited role %s must have same scope as parent %s", inherited, role)
                        .isEqualTo(role.getScope());
            }
        }
    }

    @Test
    @DisplayName("Multiple roles assigned to a user combine via permission union")
    void shouldCombineMultipleRolesViaUnion() {
        Set<Permission> union = mapping.getPermissionsForRoles(
                List.of(Role.ORGANIZATION_VIEWER, Role.ORGANIZATION_BILLING),
                RoleScope.ORGANIZATION
        );

        assertThat(union).contains(PROJECT_READ, ANALYTICS_VIEW, BILLING_VIEW, BILLING_MANAGE);
    }

    @Test
    @DisplayName("A role from one scope never grants permissions in another scope")
    void crossScopeRolesMustBeExcludedFromGrants() {
        assertThat(mapping.getPermissionsForRoles(List.of(Role.ORGANIZATION_OWNER), RoleScope.PLATFORM)).isEmpty();
        assertThat(mapping.getPermissionsForRoles(List.of(Role.PROJECT_LEAD), RoleScope.ORGANIZATION)).isEmpty();
        assertThat(mapping.getPermissionsForRoles(List.of(Role.PLATFORM_ADMIN), RoleScope.ORGANIZATION)).isEmpty();
    }

    @Test
    @DisplayName("Verify ownership-eligible permissions and base resource access rules")
    void shouldValidateOwnershipEligibilityAndBaseAccess() {
        assertThat(mapping.isOwnershipEligible(PROJECT_DELETE)).isTrue();
        assertThat(mapping.isOwnershipEligible(TASK_DELETE)).isTrue();
        assertThat(mapping.isOwnershipEligible(REPO_DISCONNECT)).isTrue();
        assertThat(mapping.isOwnershipEligible(KB_DELETE)).isTrue();
        assertThat(mapping.isOwnershipEligible(AUTOMATION_DELETE)).isTrue();
        assertThat(mapping.isOwnershipEligible(COMMENT_EDIT)).isTrue();
        assertThat(mapping.isOwnershipEligible(COMMENT_DELETE)).isTrue();
        assertThat(mapping.isOwnershipEligible(ORG_MANAGE)).isFalse();

        // Base resource access check
        assertThat(mapping.hasBaseResourceAccess(List.of(Role.PROJECT_MEMBER), TASK_DELETE)).isTrue();
        assertThat(mapping.hasBaseResourceAccess(List.of(Role.ORGANIZATION_VIEWER), TASK_DELETE)).isFalse();
    }

    @Test
    @DisplayName("Every registered canonical permission (all 48) has an explicit role or ownership path in the policy matrix")
    void everyPermissionMustHaveDocumentedPolicyPath() {
        PermissionRegistry registry = PermissionRegistry.canonical();
        assertThat(registry.size()).isEqualTo(48);

        for (Permission perm : registry.getAll()) {
            boolean hasGrant = false;
            for (Role role : Role.values()) {
                if (mapping.getPermissions(role).contains(perm)) {
                    hasGrant = true;
                    break;
                }
            }
            boolean isOwnershipAlternative = mapping.isOwnershipEligible(perm);

            assertThat(hasGrant || isOwnershipAlternative)
                    .as("Permission %s must have a defined role grant or ownership alternative", perm.value())
                    .isTrue();
        }
    }
}
