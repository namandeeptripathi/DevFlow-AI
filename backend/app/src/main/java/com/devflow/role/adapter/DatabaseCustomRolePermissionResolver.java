package com.devflow.role.adapter;

import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.evaluator.CustomRolePermissionResolver;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.role.domain.CustomRoleAssignment;
import com.devflow.role.repository.CustomRoleAssignmentRepository;
import com.devflow.security.authorization.cache.AuthorizationCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Database-backed persistence adapter implementing {@link CustomRolePermissionResolver} from {@code devflow-auth}
 * with Stage 7 authorization caching.
 *
 * <h2>Resolution Invariants</h2>
 * <ul>
 *   <li>Only active organization members can resolve custom role permissions.</li>
 *   <li>Only active (non-disabled) custom roles grant permissions.</li>
 *   <li>Only active (non-revoked, unexpired) assignments are evaluated.</li>
 *   <li>Resolved permissions are cached and invalidated on role/assignment mutations.</li>
 * </ul>
 */
@Component
public class DatabaseCustomRolePermissionResolver implements CustomRolePermissionResolver {

    private final CustomRoleAssignmentRepository assignmentRepository;
    private final OrganizationMemberRepository memberRepository;
    private final AuthorizationCacheService cacheService;

    @Autowired
    public DatabaseCustomRolePermissionResolver(
            CustomRoleAssignmentRepository assignmentRepository,
            OrganizationMemberRepository memberRepository,
            @Autowired(required = false) AuthorizationCacheService cacheService
    ) {
        this.assignmentRepository = Objects.requireNonNull(assignmentRepository, "assignmentRepository must not be null");
        this.memberRepository = Objects.requireNonNull(memberRepository, "memberRepository must not be null");
        this.cacheService = cacheService;
    }

    public DatabaseCustomRolePermissionResolver(
            CustomRoleAssignmentRepository assignmentRepository,
            OrganizationMemberRepository memberRepository
    ) {
        this(assignmentRepository, memberRepository, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Permission> resolveCustomPermissions(UUID userId, AuthorizationContext context) {
        if (userId == null || context == null) {
            return Collections.emptySet();
        }

        Optional<UUID> orgIdOpt = context.getOrganizationId();
        if (orgIdOpt.isEmpty()) {
            return Collections.emptySet();
        }
        UUID organizationId = orgIdOpt.get();

        // 1. Check authorization cache
        if (cacheService != null) {
            Optional<Set<Permission>> cached = cacheService.getCustomRolePermissions(organizationId, userId);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        // 2. Active membership check
        Optional<OrganizationMember> memberOpt = memberRepository.findByOrganizationIdAndUserId(organizationId, userId);
        if (memberOpt.isEmpty() || memberOpt.get().getStatus() != OrganizationMembershipStatus.ACTIVE) {
            if (cacheService != null) {
                cacheService.putCustomRolePermissions(organizationId, userId, Collections.emptySet());
            }
            return Collections.emptySet();
        }

        // 3. Query active custom role assignments
        Instant now = Instant.now();
        List<CustomRoleAssignment> activeAssignments = assignmentRepository
                .findActiveByOrganizationIdAndUserId(organizationId, userId, now);

        Set<Permission> permissions = new HashSet<>();
        for (CustomRoleAssignment assignment : activeAssignments) {
            // Only active custom roles grant permissions
            if (assignment.getCustomRole() != null && assignment.getCustomRole().isActive()) {
                assignment.getCustomRole().getRolePermissions().forEach(rp -> {
                    if (rp.getPermission() != null) {
                        permissions.add(rp.getPermission().toDomainPermission());
                    }
                });
            }
        }

        Set<Permission> unmodifiable = Collections.unmodifiableSet(permissions);

        // 4. Store in authorization cache
        if (cacheService != null) {
            cacheService.putCustomRolePermissions(organizationId, userId, unmodifiable);
        }

        return unmodifiable;
    }
}
