package com.devflow.modules.auth.api;

import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.RoleScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthApiTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final Permission PERMISSION = Permission.of("project.read");

    @Test
    @DisplayName("Verify default isAuthorized delegates to authorize(request)")
    void shouldDelegateIsAuthorizedToAuthorize() {
        AuthApi allowingApi = request -> AuthorizationDecision.allow();
        AuthApi denyingApi = request -> AuthorizationDecision.deny(DenialReason.INSUFFICIENT_PERMISSIONS);

        AuthorizationRequest request = AuthorizationRequest.organization(USER_ID, ORG_ID, PERMISSION);

        assertThat(allowingApi.isAuthorized(request)).isTrue();
        assertThat(denyingApi.isAuthorized(request)).isFalse();
    }

    @Test
    @DisplayName("Verify platform isAuthorized(userId, permission) constructs platform request correctly")
    void shouldDelegatePlatformIsAuthorized() {
        AuthApi recordingApi = request -> {
            if (request.userId().equals(USER_ID)
                    && request.getScope() == RoleScope.PLATFORM
                    && request.getOrganizationId().isEmpty()
                    && request.permission().equals(PERMISSION)) {
                return AuthorizationDecision.allow();
            }
            return AuthorizationDecision.deny(DenialReason.GENERAL_DENIED);
        };

        assertThat(recordingApi.isAuthorized(USER_ID, PERMISSION)).isTrue();
        assertThat(recordingApi.isAuthorized(UUID.randomUUID(), PERMISSION)).isFalse();
    }

    @Test
    @DisplayName("Verify organization isAuthorized(userId, orgId, permission) constructs organization request correctly")
    void shouldDelegateOrganizationIsAuthorized() {
        AuthApi recordingApi = request -> {
            if (request.userId().equals(USER_ID)
                    && request.getScope() == RoleScope.ORGANIZATION
                    && request.getOrganizationId().filter(ORG_ID::equals).isPresent()
                    && request.permission().equals(PERMISSION)) {
                return AuthorizationDecision.allow();
            }
            return AuthorizationDecision.deny(DenialReason.GENERAL_DENIED);
        };

        assertThat(recordingApi.isAuthorized(USER_ID, ORG_ID, PERMISSION)).isTrue();
        assertThat(recordingApi.isAuthorized(UUID.randomUUID(), ORG_ID, PERMISSION)).isFalse();
        assertThat(recordingApi.isAuthorized(USER_ID, UUID.randomUUID(), PERMISSION)).isFalse();
    }

    @Test
    @DisplayName("Verify project isAuthorized(userId, orgId, projectId, permission) constructs project request correctly")
    void shouldDelegateProjectIsAuthorized() {
        AuthApi recordingApi = request -> {
            if (request.userId().equals(USER_ID)
                    && request.getScope() == RoleScope.PROJECT
                    && request.getOrganizationId().filter(ORG_ID::equals).isPresent()
                    && request.getProjectId().filter(PROJECT_ID::equals).isPresent()
                    && request.permission().equals(PERMISSION)) {
                return AuthorizationDecision.allow();
            }
            return AuthorizationDecision.deny(DenialReason.GENERAL_DENIED);
        };

        assertThat(recordingApi.isAuthorized(USER_ID, ORG_ID, PROJECT_ID, PERMISSION)).isTrue();
        assertThat(recordingApi.isAuthorized(UUID.randomUUID(), ORG_ID, PROJECT_ID, PERMISSION)).isFalse();
        assertThat(recordingApi.isAuthorized(USER_ID, UUID.randomUUID(), PROJECT_ID, PERMISSION)).isFalse();
        assertThat(recordingApi.isAuthorized(USER_ID, ORG_ID, UUID.randomUUID(), PERMISSION)).isFalse();
    }
}
