package com.devflow.modules.auth.api;

import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.RoleScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeAuthorizationRequestTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final Permission P1 = Permission.of("project.read");
    private static final Permission P2 = Permission.of("task.create");

    @Test
    @DisplayName("Create platform composite authorization request")
    void shouldCreatePlatformCompositeRequest() {
        CompositeAuthorizationRequest request = CompositeAuthorizationRequest.platform(USER_ID, List.of(P1, P2));

        assertThat(request.userId()).isEqualTo(USER_ID);
        assertThat(request.getScope()).isEqualTo(RoleScope.PLATFORM);
        assertThat(request.getOrganizationId()).isEmpty();
        assertThat(request.getProjectId()).isEmpty();
        assertThat(request.permissions()).containsExactlyInAnyOrder(P1, P2);
        assertThat(request.attributes()).isEmpty();
    }

    @Test
    @DisplayName("Create organization composite authorization request")
    void shouldCreateOrganizationCompositeRequest() {
        CompositeAuthorizationRequest request = CompositeAuthorizationRequest.organization(
                USER_ID, ORG_ID, Set.of(P1, P2), "res-123");

        assertThat(request.userId()).isEqualTo(USER_ID);
        assertThat(request.getScope()).isEqualTo(RoleScope.ORGANIZATION);
        assertThat(request.getOrganizationId()).contains(ORG_ID);
        assertThat(request.getProjectId()).isEmpty();
        assertThat(request.getResourceId()).contains("res-123");
        assertThat(request.resourceId()).isEqualTo("res-123");
        assertThat(request.permissions()).containsExactlyInAnyOrder(P1, P2);
    }

    @Test
    @DisplayName("Create project composite authorization request")
    void shouldCreateProjectCompositeRequest() {
        CompositeAuthorizationRequest request = CompositeAuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, Set.of(P1, P2), "task-456");

        assertThat(request.userId()).isEqualTo(USER_ID);
        assertThat(request.getScope()).isEqualTo(RoleScope.PROJECT);
        assertThat(request.getOrganizationId()).contains(ORG_ID);
        assertThat(request.getProjectId()).contains(PROJECT_ID);
        assertThat(request.getResourceId()).contains("task-456");
        assertThat(request.permissions()).containsExactlyInAnyOrder(P1, P2);
    }

    @Test
    @DisplayName("Create composite request using explicit AuthorizationContext")
    void shouldCreateRequestWithExplicitContext() {
        CompositeAuthorizationRequest request = CompositeAuthorizationRequest.of(
                USER_ID, AuthorizationContext.organization(ORG_ID), List.of(P1));

        assertThat(request.getScope()).isEqualTo(RoleScope.ORGANIZATION);
        assertThat(request.getOrganizationId()).contains(ORG_ID);
        assertThat(request.permissions()).containsExactly(P1);
    }

    @Test
    @DisplayName("Reject null or empty permissions set in composite request")
    void shouldRejectNullOrEmptyPermissions() {
        assertThatThrownBy(() -> new CompositeAuthorizationRequest(
                USER_ID, AuthorizationContext.platform(), null, null, Collections.emptyMap()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("permissions must not be null");

        assertThatThrownBy(() -> new CompositeAuthorizationRequest(
                USER_ID, AuthorizationContext.platform(), Collections.emptySet(), null, Collections.emptyMap()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permissions set must not be empty");
    }

    @Test
    @DisplayName("Reject null permission elements in permissions set")
    void shouldRejectNullPermissionElement() {
        Set<Permission> setWithNull = new HashSet<>();
        setWithNull.add(P1);
        setWithNull.add(null);

        assertThatThrownBy(() -> new CompositeAuthorizationRequest(
                USER_ID, AuthorizationContext.platform(), setWithNull, null, Collections.emptyMap()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Individual permission elements in composite request must not be null");
    }

    @Test
    @DisplayName("Ensure defensive copy of permissions set provides strict immutability")
    void shouldDefensivelyCopyPermissions() {
        Set<Permission> mutable = new HashSet<>();
        mutable.add(P1);

        CompositeAuthorizationRequest request = new CompositeAuthorizationRequest(
                USER_ID, AuthorizationContext.platform(), mutable, null, Map.of("key", "value"));

        mutable.add(P2);

        // Caller mutation must not alter request permissions
        assertThat(request.permissions()).containsExactly(P1);

        // Modifying request.permissions() directly must throw UnsupportedOperationException
        assertThatThrownBy(() -> request.permissions().add(P2))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

