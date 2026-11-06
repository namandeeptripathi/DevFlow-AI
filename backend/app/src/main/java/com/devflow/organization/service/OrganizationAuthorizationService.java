package com.devflow.organization.service;

import com.devflow.modules.auth.api.AuthApi;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.context.OrganizationAuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.evaluator.AuthorizationEvaluator;
import com.devflow.modules.auth.evaluator.MembershipValidator;
import com.devflow.modules.auth.evaluator.RoleResolver;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationMembershipRequiredException;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.role.adapter.DatabaseMembershipValidator;
import com.devflow.role.adapter.DatabaseRoleResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Centralised authorization service for organization-scoped permission checks (Stage 5).
 *
 * <p>Delegates all authorization decisions strictly to the centralized {@link AuthApi} engine,
 * backed by database role resolution ({@link RoleResolver}) and active membership validation
 * ({@link MembershipValidator}).
 *
 * <h2>Permission Model & Invariants</h2>
 * <ul>
 *   <li><strong>Member Check:</strong> Requires verified active organization membership ({@link OrganizationMembershipStatus#ACTIVE})
 *       and at least one active organization-scoped canonical role.</li>
 *   <li><strong>Admin or Owner Check:</strong> Evaluates canonical permission {@link PermissionRegistry#MEMBER_INVITE},
 *       held exclusively by {@link Role#ORGANIZATION_OWNER} and {@link Role#ORGANIZATION_ADMIN}.</li>
 *   <li><strong>Owner Check:</strong> Evaluates canonical permission {@link PermissionRegistry#ORG_DELETE},
 *       held exclusively by {@link Role#ORGANIZATION_OWNER}. Organization administrators never receive owner privileges.</li>
 *   <li><strong>Billing Isolation:</strong> Billing privileges remain isolated to {@link Role#ORGANIZATION_BILLING}
 *       and {@link Role#ORGANIZATION_OWNER}; administrators cannot access billing resources.</li>
 *   <li><strong>Legacy Compatibility:</strong> Resolves unmigrated {@code DEVELOPER} members to canonical
 *       {@link Role#ORGANIZATION_MEMBER} via {@link DatabaseRoleResolver#mapLegacyOrganizationRole}.</li>
 *   <li><strong>Fail Closed:</strong> Inactive or suspended members and null arguments fail closed immediately.</li>
 * </ul>
 *
 * @see AuthApi
 * @see PermissionRegistry
 * @see MembershipValidator
 * @see RoleResolver
 */
@Service
public class OrganizationAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationAuthorizationService.class);

    private final AuthApi authApi;
    private final MembershipValidator membershipValidator;
    private final RoleResolver roleResolver;

    @Autowired
    public OrganizationAuthorizationService(
            AuthApi authApi,
            MembershipValidator membershipValidator,
            RoleResolver roleResolver
    ) {
        this.authApi = Objects.requireNonNull(authApi, "authApi must not be null");
        this.membershipValidator = Objects.requireNonNull(membershipValidator, "membershipValidator must not be null");
        this.roleResolver = Objects.requireNonNull(roleResolver, "roleResolver must not be null");
    }

    /**
     * Compatibility constructor strictly for slice tests and test doubles.
     *
     * @param organizationMemberRepository repository to build compatibility adapter
     */
    public OrganizationAuthorizationService(OrganizationMemberRepository organizationMemberRepository) {
        this(
                createCompatibilityAuthApi(organizationMemberRepository),
                new DatabaseMembershipValidator(organizationMemberRepository),
                createCompatibilityRoleResolver(organizationMemberRepository)
        );
    }

    // ── Boolean queries ──────────────────────────────────────────────────────

    /**
     * Checks whether the user is an active member of the organization holding an active role.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @return {@code true} if the user holds an active membership and role; {@code false} otherwise
     */
    @Transactional(readOnly = true)
    public boolean isMember(UUID organizationId, UUID userId) {
        if (organizationId == null || userId == null) {
            return false;
        }

        if (!membershipValidator.isActiveMember(userId, organizationId)) {
            return false;
        }

        Set<Role> roles = roleResolver.resolveRoles(userId, OrganizationAuthorizationContext.of(organizationId));
        return roles != null && roles.stream().anyMatch(r -> r != null && r.isOrganizationRole());
    }

    /**
     * Checks whether the user holds active {@link Role#ORGANIZATION_OWNER} authority in the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @return {@code true} if authorized as owner; {@code false} otherwise
     */
    @Transactional(readOnly = true)
    public boolean isOwner(UUID organizationId, UUID userId) {
        if (organizationId == null || userId == null) {
            return false;
        }

        return authApi.authorize(AuthorizationRequest.organization(
                userId,
                organizationId,
                PermissionRegistry.ORG_DELETE
        )).allowed();
    }

    /**
     * Checks whether the user holds active ADMIN or OWNER authority in the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @return {@code true} if authorized as admin or owner; {@code false} otherwise
     */
    @Transactional(readOnly = true)
    public boolean isAdminOrOwner(UUID organizationId, UUID userId) {
        if (organizationId == null || userId == null) {
            return false;
        }

        return authApi.authorize(AuthorizationRequest.organization(
                userId,
                organizationId,
                PermissionRegistry.MEMBER_INVITE
        )).allowed();
    }

    /**
     * Checks whether the user holds a specific canonical permission in the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @param permission the canonical permission to check
     * @return {@code true} if authorized; {@code false} otherwise
     */
    @Transactional(readOnly = true)
    public boolean hasPermission(UUID organizationId, UUID userId, Permission permission) {
        return hasPermission(organizationId, userId, permission, null);
    }

    /**
     * Checks whether the user holds a specific canonical permission on a resource in the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @param permission the canonical permission to check
     * @param resourceId optional sub-resource identifier
     * @return {@code true} if authorized; {@code false} otherwise
     */
    @Transactional(readOnly = true)
    public boolean hasPermission(UUID organizationId, UUID userId, Permission permission, String resourceId) {
        if (organizationId == null || userId == null || permission == null) {
            return false;
        }

        return authApi.authorize(new AuthorizationRequest(
                userId,
                OrganizationAuthorizationContext.of(organizationId),
                permission,
                resourceId,
                Collections.emptyMap()
        )).allowed();
    }

    // ── Imperative checks ────────────────────────────────────────────────────

    /**
     * Requires the user to be an active member of the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @throws OrganizationMembershipRequiredException if the user is not an active member
     */
    @Transactional(readOnly = true)
    public void requireMember(UUID organizationId, UUID userId) {
        if (!isMember(organizationId, userId)) {
            log.warn("Security rejection: user [{}] denied member access to organization [{}]", userId, organizationId);
            throw new OrganizationMembershipRequiredException(
                    "Access denied: active organization membership required for organization [" + organizationId + "]");
        }
    }

    /**
     * Requires the user to be an active admin or owner of the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @throws OrganizationAccessDeniedException if the user is not an active admin or owner
     */
    @Transactional(readOnly = true)
    public void requireAdminOrOwner(UUID organizationId, UUID userId) {
        if (!isAdminOrOwner(organizationId, userId)) {
            log.warn("Security rejection: user [{}] denied admin/owner access to organization [{}]", userId, organizationId);
            throw new OrganizationAccessDeniedException(
                    "Access denied: ADMIN or OWNER role required for organization [" + organizationId + "]");
        }
    }

    /**
     * Requires the user to be an active owner of the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @throws OrganizationAccessDeniedException if the user is not an active owner
     */
    @Transactional(readOnly = true)
    public void requireOwner(UUID organizationId, UUID userId) {
        if (!isOwner(organizationId, userId)) {
            log.warn("Security rejection: user [{}] denied owner access to organization [{}]", userId, organizationId);
            throw new OrganizationAccessDeniedException(
                    "Access denied: OWNER role required for organization [" + organizationId + "]");
        }
    }

    /**
     * Requires the user to hold a specific canonical permission in the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @param permission the canonical permission required
     * @throws OrganizationAccessDeniedException if the user lacks the permission
     */
    @Transactional(readOnly = true)
    public void requirePermission(UUID organizationId, UUID userId, Permission permission) {
        requirePermission(organizationId, userId, permission, null);
    }

    /**
     * Requires the user to hold a specific canonical permission on a resource in the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @param permission the canonical permission required
     * @param resourceId optional sub-resource identifier
     * @throws OrganizationAccessDeniedException if the user lacks the permission
     */
    @Transactional(readOnly = true)
    public void requirePermission(UUID organizationId, UUID userId, Permission permission, String resourceId) {
        if (!hasPermission(organizationId, userId, permission, resourceId)) {
            String target = resourceId != null ? " on resource [" + resourceId + "]" : "";
            log.warn("Security rejection: user [{}] denied permission [{}] in organization [{}]",
                    userId, permission.value(), organizationId);
            throw new OrganizationAccessDeniedException(
                    "Access denied: permission [" + permission.value() + "]" + target +
                    " required for organization [" + organizationId + "]");
        }
    }

    // ── Compatibility Helpers ─────────────────────────────────────────────────

    private static RoleResolver createCompatibilityRoleResolver(OrganizationMemberRepository repository) {
        Objects.requireNonNull(repository, "organizationMemberRepository must not be null");
        return (userId, context) -> {
            Optional<UUID> orgIdOpt = context.getOrganizationId();
            if (orgIdOpt.isEmpty()) {
                return Collections.emptySet();
            }
            return repository.findByOrganizationIdAndUserId(orgIdOpt.get(), userId)
                    .filter(m -> m.getStatus() == OrganizationMembershipStatus.ACTIVE)
                    .map(m -> m.getRole() != null
                            ? Set.of(DatabaseRoleResolver.mapLegacyOrganizationRole(m.getRole()))
                            : Collections.<Role>emptySet())
                    .orElse(Collections.emptySet());
        };
    }

    private static AuthApi createCompatibilityAuthApi(OrganizationMemberRepository repository) {
        Objects.requireNonNull(repository, "organizationMemberRepository must not be null");
        DatabaseMembershipValidator memVal = new DatabaseMembershipValidator(repository);
        RoleResolver roleRes = createCompatibilityRoleResolver(repository);
        return AuthorizationEvaluator.builder()
                .membershipValidator(memVal)
                .roleResolver(roleRes)
                .build();
    }
}
