package com.devflow.modules.auth.context;

import com.devflow.modules.auth.domain.RoleScope;

import java.util.Optional;
import java.util.UUID;

/**
 * Immutable authorization context for deployment-wide platform operations.
 *
 * <p>Platform requests operate across all tenants and do not require or carry
 * an organization identifier.
 *
 * @see AuthorizationContext
 * @see RoleScope#PLATFORM
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §4.1, §5.1, §11.1</a>
 */
public record PlatformAuthorizationContext() implements AuthorizationContext {

    private static final PlatformAuthorizationContext INSTANCE = new PlatformAuthorizationContext();

    /**
     * Returns the singleton platform context instance.
     *
     * @return the platform context
     */
    public static PlatformAuthorizationContext instance() {
        return INSTANCE;
    }

    @Override
    public RoleScope getScope() {
        return RoleScope.PLATFORM;
    }

    @Override
    public Optional<UUID> getOrganizationId() {
        return Optional.empty();
    }

    @Override
    public Optional<UUID> getProjectId() {
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "AuthorizationContext[scope=PLATFORM]";
    }
}

