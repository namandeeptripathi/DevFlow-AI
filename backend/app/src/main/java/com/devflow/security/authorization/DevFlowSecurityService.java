package com.devflow.security.authorization;

import com.devflow.modules.auth.api.AuthApi;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.context.OrganizationAuthorizationContext;
import com.devflow.modules.auth.context.PlatformAuthorizationContext;
import com.devflow.modules.auth.context.ProjectAuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.security.user.DevFlowUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Security evaluation bridge component exposed as {@code @authz} for expression-based method security.
 *
 * <p>Enables declarative access control annotations such as:
 * <pre>{@code
 *   @PreAuthorize("@authz.hasOrgPermission(#organizationId, 'member.invite')")
 *   @PreAuthorize("@authz.hasProjectPermission(#orgId, #projId, 'project.write')")
 * }</pre>
 *
 * <h2>Security Invariants</h2>
 * <ul>
 *   <li>The authorization subject is strictly extracted from the trusted authenticated principal
 *       ({@link DevFlowUserDetails#getId()}) in {@link SecurityContextHolder}.</li>
 *   <li>Client-supplied user IDs, roles, or permission strings are never trusted as authoritative.</li>
 *   <li>Unauthenticated or anonymous principals are rejected immediately.</li>
 *   <li>Unknown permissions or invalid contexts fail closed immediately.</li>
 * </ul>
 *
 * @see AuthApi
 * @see PermissionRegistry
 */
@Component("authz")
public class DevFlowSecurityService {

    private final AuthApi authApi;
    private final PermissionRegistry permissionRegistry;

    public DevFlowSecurityService(AuthApi authApi, PermissionRegistry permissionRegistry) {
        this.authApi = Objects.requireNonNull(authApi, "authApi must not be null");
        this.permissionRegistry = Objects.requireNonNull(permissionRegistry, "permissionRegistry must not be null");
    }

    /**
     * Checks if the currently authenticated user holds the specified canonical permission in the organization.
     *
     * @param organizationId the target organization UUID
     * @param permissionValue canonical permission string (e.g. "org.manage", "member.invite")
     * @return {@code true} if authorized; {@code false} otherwise
     */
    public boolean hasOrgPermission(UUID organizationId, String permissionValue) {
        return hasOrgPermission(organizationId, permissionValue, null);
    }

    /**
     * Checks if the currently authenticated user holds the specified canonical permission on a specific resource in the organization.
     *
     * @param organizationId the target organization UUID
     * @param permissionValue canonical permission string
     * @param resourceId optional sub-resource identifier
     * @return {@code true} if authorized; {@code false} otherwise
     */
    public boolean hasOrgPermission(UUID organizationId, String permissionValue, String resourceId) {
        UUID userId = getCurrentAuthenticatedUserId();
        if (userId == null || organizationId == null || permissionValue == null || permissionValue.isBlank()) {
            return false;
        }

        Optional<Permission> permissionOpt = permissionRegistry.find(permissionValue.trim());
        if (permissionOpt.isEmpty()) {
            return false;
        }

        AuthorizationRequest request = new AuthorizationRequest(
                userId,
                OrganizationAuthorizationContext.of(organizationId),
                permissionOpt.get(),
                resourceId,
                Collections.emptyMap()
        );

        return authApi.authorize(request).allowed();
    }

    /**
     * Checks if the currently authenticated user holds the specified canonical permission in a project.
     *
     * @param organizationId the tenant organization UUID
     * @param projectId the target project UUID
     * @param permissionValue canonical permission string (e.g. "project.write", "task.create")
     * @return {@code true} if authorized; {@code false} otherwise
     */
    public boolean hasProjectPermission(UUID organizationId, UUID projectId, String permissionValue) {
        return hasProjectPermission(organizationId, projectId, permissionValue, null);
    }

    /**
     * Checks if the currently authenticated user holds the specified canonical permission on a resource in a project.
     *
     * @param organizationId tenant organization UUID
     * @param projectId target project UUID
     * @param permissionValue canonical permission string
     * @param resourceId optional resource identifier
     * @return {@code true} if authorized; {@code false} otherwise
     */
    public boolean hasProjectPermission(UUID organizationId, UUID projectId, String permissionValue, String resourceId) {
        UUID userId = getCurrentAuthenticatedUserId();
        if (userId == null || organizationId == null || projectId == null || permissionValue == null || permissionValue.isBlank()) {
            return false;
        }

        Optional<Permission> permissionOpt = permissionRegistry.find(permissionValue.trim());
        if (permissionOpt.isEmpty()) {
            return false;
        }

        AuthorizationRequest request = new AuthorizationRequest(
                userId,
                ProjectAuthorizationContext.of(organizationId, projectId),
                permissionOpt.get(),
                resourceId,
                Collections.emptyMap()
        );

        return authApi.authorize(request).allowed();
    }

    /**
     * Checks if the currently authenticated user holds a platform-level permission.
     *
     * @param permissionValue canonical platform permission string
     * @return {@code true} if authorized; {@code false} otherwise
     */
    public boolean hasPlatformPermission(String permissionValue) {
        UUID userId = getCurrentAuthenticatedUserId();
        if (userId == null || permissionValue == null || permissionValue.isBlank()) {
            return false;
        }

        Optional<Permission> permissionOpt = permissionRegistry.find(permissionValue.trim());
        if (permissionOpt.isEmpty()) {
            return false;
        }

        AuthorizationRequest request = new AuthorizationRequest(
                userId,
                PlatformAuthorizationContext.instance(),
                permissionOpt.get(),
                null,
                Collections.emptyMap()
        );

        return authApi.authorize(request).allowed();
    }

    private UUID getCurrentAuthenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof DevFlowUserDetails userDetails) {
            return userDetails.getId();
        }

        return null;
    }
}
