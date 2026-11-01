package com.devflow.modules.auth.context;

import com.devflow.modules.auth.domain.RoleScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationContextTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();

    @Test
    @DisplayName("Platform authorization context has PLATFORM scope and no organization/project IDs")
    void shouldCreatePlatformContext() {
        PlatformAuthorizationContext context = AuthorizationContext.platform();

        assertThat(context.getScope()).isEqualTo(RoleScope.PLATFORM);
        assertThat(context.getOrganizationId()).isEmpty();
        assertThat(context.getProjectId()).isEmpty();
        assertThat(context).isSameAs(PlatformAuthorizationContext.instance());
        assertThat(context.toString()).contains("PLATFORM");
    }

    @Test
    @DisplayName("Organization authorization context has ORGANIZATION scope and requires organizationId")
    void shouldCreateOrganizationContext() {
        OrganizationAuthorizationContext context = AuthorizationContext.organization(ORG_ID);

        assertThat(context.getScope()).isEqualTo(RoleScope.ORGANIZATION);
        assertThat(context.getOrganizationId()).contains(ORG_ID);
        assertThat(context.organizationId()).isEqualTo(ORG_ID);
        assertThat(context.getProjectId()).isEmpty();
        assertThat(context.toString()).contains("ORGANIZATION").contains(ORG_ID.toString());
    }

    @Test
    @DisplayName("Organization authorization context rejects null organizationId")
    void organizationContextShouldRejectNullOrgId() {
        assertThatThrownBy(() -> AuthorizationContext.organization(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("organizationId must not be null");
    }

    @Test
    @DisplayName("Project authorization context has PROJECT scope and requires organizationId and projectId")
    void shouldCreateProjectContext() {
        ProjectAuthorizationContext context = AuthorizationContext.project(ORG_ID, PROJECT_ID);

        assertThat(context.getScope()).isEqualTo(RoleScope.PROJECT);
        assertThat(context.getOrganizationId()).contains(ORG_ID);
        assertThat(context.organizationId()).isEqualTo(ORG_ID);
        assertThat(context.getProjectId()).contains(PROJECT_ID);
        assertThat(context.projectId()).isEqualTo(PROJECT_ID);
        assertThat(context.toString()).contains("PROJECT").contains(ORG_ID.toString()).contains(PROJECT_ID.toString());
    }

    @Test
    @DisplayName("Project authorization context rejects null boundaries")
    void projectContextShouldRejectNullBoundaries() {
        assertThatThrownBy(() -> AuthorizationContext.project(null, PROJECT_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("organizationId must not be null");

        assertThatThrownBy(() -> AuthorizationContext.project(ORG_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("projectId must not be null");
    }

    @Test
    @DisplayName("Pattern matching exhaustiveness over sealed AuthorizationContext")
    void shouldSupportPatternMatching() {
        AuthorizationContext platform = AuthorizationContext.platform();
        AuthorizationContext org = AuthorizationContext.organization(ORG_ID);
        AuthorizationContext project = AuthorizationContext.project(ORG_ID, PROJECT_ID);

        assertThat(describeContext(platform)).isEqualTo("Platform");
        assertThat(describeContext(org)).isEqualTo("Organization:" + ORG_ID);
        assertThat(describeContext(project)).isEqualTo("Project:" + PROJECT_ID + "@" + ORG_ID);
    }

    private String describeContext(AuthorizationContext context) {
        return switch (context) {
            case PlatformAuthorizationContext ignored -> "Platform";
            case OrganizationAuthorizationContext o -> "Organization:" + o.organizationId();
            case ProjectAuthorizationContext p -> "Project:" + p.projectId() + "@" + p.organizationId();
        };
    }
}

