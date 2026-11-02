package com.devflow.modules.auth.registry;

import com.devflow.modules.auth.domain.Permission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionRegistryTest {

    @Test
    @DisplayName("Canonical registry contains exactly the 48 permissions defined in Authorization Model and ADR-001")
    void shouldContainAllCanonicalPermissions() {
        PermissionRegistry registry = PermissionRegistry.canonical();

        assertThat(registry.size()).isEqualTo(48);
        assertThat(registry.getAll()).hasSize(48);

        // Verify key domains are populated
        assertThat(registry.getByResource("org")).containsExactlyInAnyOrder(
                PermissionRegistry.ORG_MANAGE,
                PermissionRegistry.ORG_DELETE
        );

        assertThat(registry.getByResource("member")).containsExactlyInAnyOrder(
                PermissionRegistry.MEMBER_INVITE,
                PermissionRegistry.MEMBER_REMOVE
        );

        assertThat(registry.getByResource("project")).containsExactlyInAnyOrder(
                PermissionRegistry.PROJECT_READ,
                PermissionRegistry.PROJECT_WRITE,
                PermissionRegistry.PROJECT_DELETE
        );

        assertThat(registry.getByResource("task")).containsExactlyInAnyOrder(
                PermissionRegistry.TASK_READ,
                PermissionRegistry.TASK_CREATE,
                PermissionRegistry.TASK_WRITE,
                PermissionRegistry.TASK_ASSIGN,
                PermissionRegistry.TASK_DELETE
        );

        assertThat(registry.getByResource("cycle")).containsExactlyInAnyOrder(
                PermissionRegistry.CYCLE_MANAGE
        );

        assertThat(registry.getByResource("repo")).containsExactlyInAnyOrder(
                PermissionRegistry.REPO_READ,
                PermissionRegistry.REPO_CONNECT,
                PermissionRegistry.REPO_DISCONNECT,
                PermissionRegistry.REPO_SCAN,
                PermissionRegistry.REPO_CONFIGURE
        );

        assertThat(registry.getByResource("ai")).containsExactlyInAnyOrder(
                PermissionRegistry.AI_CHAT,
                PermissionRegistry.AI_REVIEW,
                PermissionRegistry.AI_REVIEW_APPROVE,
                PermissionRegistry.AI_CONFIGURE
        );

        assertThat(registry.getByResource("kb")).containsExactlyInAnyOrder(
                PermissionRegistry.KB_READ,
                PermissionRegistry.KB_WRITE,
                PermissionRegistry.KB_MANAGE,
                PermissionRegistry.KB_DELETE
        );

        assertThat(registry.getByResource("analytics")).containsExactlyInAnyOrder(
                PermissionRegistry.ANALYTICS_VIEW,
                PermissionRegistry.ANALYTICS_EXPORT,
                PermissionRegistry.ANALYTICS_CONFIGURE
        );

        assertThat(registry.getByResource("automation")).containsExactlyInAnyOrder(
                PermissionRegistry.AUTOMATION_READ,
                PermissionRegistry.AUTOMATION_CONFIGURE,
                PermissionRegistry.AUTOMATION_DELETE
        );

        assertThat(registry.getByResource("comment")).containsExactlyInAnyOrder(
                PermissionRegistry.COMMENT_CREATE,
                PermissionRegistry.COMMENT_EDIT,
                PermissionRegistry.COMMENT_DELETE,
                PermissionRegistry.COMMENT_MODERATE
        );

        assertThat(registry.getByResource("notification")).containsExactlyInAnyOrder(
                PermissionRegistry.NOTIFICATION_CONFIGURE,
                PermissionRegistry.NOTIFICATION_BROADCAST
        );

        assertThat(registry.getByResource("billing")).containsExactlyInAnyOrder(
                PermissionRegistry.BILLING_VIEW,
                PermissionRegistry.BILLING_MANAGE
        );

        assertThat(registry.getByResource("platform")).containsExactlyInAnyOrder(
                PermissionRegistry.PLATFORM_TENANT_PROVISION,
                PermissionRegistry.PLATFORM_TENANT_DEPROVISION,
                PermissionRegistry.PLATFORM_FEATUREFLAG_MANAGE,
                PermissionRegistry.PLATFORM_OBSERVABILITY_READ,
                PermissionRegistry.PLATFORM_AUDIT_READ,
                PermissionRegistry.PLATFORM_SUPPORT_CASE_READ,
                PermissionRegistry.PLATFORM_SUPPORT_TENANT_READ,
                PermissionRegistry.PLATFORM_EMERGENCY_ACCESS_EXECUTE
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "project.read", "project.write", "project.delete",
            "task.create", "task.assign", "repo.connect", "repo.scan",
            "ai.chat", "ai.review", "ai.review.approve",
            "kb.write", "kb.manage", "analytics.view", "analytics.export",
            "automation.configure", "member.invite", "member.remove",
            "org.manage", "billing.manage", "billing.view",
            "platform.tenant.provision", "platform.tenant.deprovision",
            "platform.featureflag.manage", "platform.observability.read",
            "platform.audit.read", "platform.support.case.read",
            "platform.support.tenant.read", "platform.emergency_access.execute"
    })
    @DisplayName("Lookup canonical permissions by string value")
    void shouldLookupCanonicalPermissionsByString(String permissionString) {
        PermissionRegistry registry = PermissionRegistry.canonical();

        assertThat(registry.isRegistered(permissionString)).isTrue();
        assertThat(registry.find(permissionString)).isPresent();
        assertThat(registry.get(permissionString).value()).isEqualTo(permissionString);
    }

    @Test
    @DisplayName("Prevent accidental duplicate permission registration")
    void shouldRejectDuplicateRegistration() {
        PermissionRegistry registry = PermissionRegistry.empty();
        Permission permission = Permission.of("custom", "action");

        registry.register(permission);
        assertThat(registry.isRegistered(permission)).isTrue();

        assertThatThrownBy(() -> registry.register(permission))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Permission already registered: custom.action");

        // Attempt duplicate with identical string value
        assertThatThrownBy(() -> registry.register(Permission.of("custom.action")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Permission already registered: custom.action");
    }

    @Test
    @DisplayName("Empty registry behaves as expected for isolation")
    void shouldSupportEmptyRegistry() {
        PermissionRegistry registry = PermissionRegistry.empty();
        assertThat(registry.size()).isEqualTo(0);
        assertThat(registry.getAll()).isEmpty();
        assertThat(registry.isRegistered("project.read")).isFalse();
        assertThat(registry.find("project.read")).isEmpty();

        assertThatThrownBy(() -> registry.get("project.read"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown permission: project.read");
    }

    @Test
    @DisplayName("Reject null permission registration")
    void shouldRejectNullRegistration() {
        PermissionRegistry registry = PermissionRegistry.empty();
        assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Handle blank or null search strings gracefully")
    void shouldHandleBlankSearchStringsGracefully() {
        PermissionRegistry registry = PermissionRegistry.canonical();

        assertThat(registry.isRegistered((String) null)).isFalse();
        assertThat(registry.isRegistered("   ")).isFalse();
        assertThat(registry.isRegistered((Permission) null)).isFalse();
        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.find("")).isEmpty();
        assertThat(registry.getByResource(null)).isEmpty();
        assertThat(registry.getByResource("  ")).isEmpty();
        assertThat(registry.getByResource("nonexistent")).isEmpty();
    }
}

