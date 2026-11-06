package com.devflow.security.authorization.cache;

import com.devflow.modules.auth.domain.Role;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service contract for caching and invalidating expensive authorization lookups (Stage 6).
 *
 * <h2>Cached Lookups</h2>
 * <ul>
 *   <li>Resolved organization roles: {@code (organizationId, userId) -> Set<Role>}</li>
 *   <li>Resolved project roles: {@code (organizationId, projectId, userId) -> Set<Role>}</li>
 *   <li>Active organization membership: {@code (organizationId, userId) -> Boolean}</li>
 * </ul>
 *
 * <h2>Security & Isolation Invariants</h2>
 * <ul>
 *   <li><strong>Tenant Scoped Keys:</strong> Every cache key strictly includes the tenant {@code organizationId}.</li>
 *   <li><strong>Fail-Closed Fallback:</strong> If caching infrastructure is unavailable, cache lookup returns
 *       {@link Optional#empty()}, triggering authoritative database evaluation. Authorization never becomes permissive.</li>
 *   <li><strong>Targeted Invalidation:</strong> Modifications to assignments or memberships immediately
 *       evict matching cache entries so revoked privileges cannot persist.</li>
 * </ul>
 */
public interface AuthorizationCacheService {

    /**
     * Retrieves cached organization roles for the specified user and organization.
     *
     * @param organizationId tenant organization UUID
     * @param userId target user UUID
     * @return optional containing the cached role set, or empty on cache miss / error
     */
    Optional<Set<Role>> getOrganizationRoles(UUID organizationId, UUID userId);

    /**
     * Stores resolved organization roles in cache.
     *
     * @param organizationId tenant organization UUID
     * @param userId target user UUID
     * @param roles resolved canonical roles
     */
    void putOrganizationRoles(UUID organizationId, UUID userId, Set<Role> roles);

    /**
     * Retrieves cached project roles for the specified user, project, and organization.
     *
     * @param organizationId tenant organization UUID
     * @param projectId target project UUID
     * @param userId target user UUID
     * @return optional containing the cached role set, or empty on cache miss / error
     */
    Optional<Set<Role>> getProjectRoles(UUID organizationId, UUID projectId, UUID userId);

    /**
     * Stores resolved project roles in cache.
     *
     * @param organizationId tenant organization UUID
     * @param projectId target project UUID
     * @param userId target user UUID
     * @param roles resolved canonical roles
     */
    void putProjectRoles(UUID organizationId, UUID projectId, UUID userId, Set<Role> roles);

    /**
     * Retrieves cached active organization membership status.
     *
     * @param organizationId tenant organization UUID
     * @param userId target user UUID
     * @return optional containing {@code true} if active, {@code false} if inactive, or empty on cache miss
     */
    Optional<Boolean> isMemberActive(UUID organizationId, UUID userId);

    /**
     * Stores active organization membership status in cache.
     *
     * @param organizationId tenant organization UUID
     * @param userId target user UUID
     * @param active whether membership is active
     */
    void putMemberActive(UUID organizationId, UUID userId, boolean active);

    /**
     * Evicts all authorization cache entries (roles and membership) for the user in the organization.
     *
     * @param organizationId tenant organization UUID
     * @param userId target user UUID
     */
    void evictUser(UUID organizationId, UUID userId);

    /**
     * Evicts cached project roles for the user in the specified project.
     *
     * @param organizationId tenant organization UUID
     * @param projectId target project UUID
     * @param userId target user UUID
     */
    void evictUserProject(UUID organizationId, UUID projectId, UUID userId);

    /**
     * Evicts all authorization cache entries for the entire organization tenant.
     *
     * @param organizationId tenant organization UUID
     */
    void evictOrganization(UUID organizationId);

    /**
     * Clears all authorization cache entries across all tenants.
     */
    void clear();
}
