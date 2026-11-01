package com.devflow.modules.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class RoleTest {

    @Test
    @DisplayName("Verify platform roles classification")
    void shouldClassifyPlatformRoles() {
        assertThat(Role.PLATFORM_ADMIN.getScope()).isEqualTo(RoleScope.PLATFORM);
        assertThat(Role.PLATFORM_ADMIN.isPlatformRole()).isTrue();
        assertThat(Role.PLATFORM_ADMIN.isOrganizationRole()).isFalse();
        assertThat(Role.PLATFORM_ADMIN.isProjectRole()).isFalse();

        assertThat(Role.PLATFORM_SUPPORT.getScope()).isEqualTo(RoleScope.PLATFORM);
        assertThat(Role.PLATFORM_SUPPORT.isPlatformRole()).isTrue();
        assertThat(Role.PLATFORM_SUPPORT.isOrganizationRole()).isFalse();
        assertThat(Role.PLATFORM_SUPPORT.isProjectRole()).isFalse();
    }

    @Test
    @DisplayName("Verify organization roles classification")
    void shouldClassifyOrganizationRoles() {
        Role[] orgRoles = {
                Role.ORGANIZATION_OWNER,
                Role.ORGANIZATION_ADMIN,
                Role.ORGANIZATION_MEMBER,
                Role.ORGANIZATION_VIEWER,
                Role.ORGANIZATION_BILLING
        };

        for (Role role : orgRoles) {
            assertThat(role.getScope()).isEqualTo(RoleScope.ORGANIZATION);
            assertThat(role.isOrganizationRole()).isTrue();
            assertThat(role.isPlatformRole()).isFalse();
            assertThat(role.isProjectRole()).isFalse();
        }
    }

    @Test
    @DisplayName("Verify project roles classification")
    void shouldClassifyProjectRoles() {
        Role[] projectRoles = {
                Role.PROJECT_LEAD,
                Role.PROJECT_MEMBER,
                Role.PROJECT_VIEWER
        };

        for (Role role : projectRoles) {
            assertThat(role.getScope()).isEqualTo(RoleScope.PROJECT);
            assertThat(role.isProjectRole()).isTrue();
            assertThat(role.isPlatformRole()).isFalse();
            assertThat(role.isOrganizationRole()).isFalse();
        }
    }

    @Test
    @DisplayName("Verify total canonical roles count matches Authorization Model §5")
    void shouldContainExactlyTenCanonicalRoles() {
        assertThat(Role.values()).hasSize(10);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("Every role must have a non-null scope")
    void everyRoleMustHaveNonNullScope(Role role) {
        assertThat(role.getScope()).isNotNull();
    }
}

