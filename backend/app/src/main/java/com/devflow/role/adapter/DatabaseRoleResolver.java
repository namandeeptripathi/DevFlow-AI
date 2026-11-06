package com.devflow.role.adapter;

import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.evaluator.RoleResolver;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.role.domain.OrganizationRoleAssignment;
import com.devflow.role.domain.ProjectRoleAssignment;
import com.devflow.role.repository.OrganizationRoleAssignmentRepository;
import com.devflow.role.repository.ProjectRoleAssignmentRepository;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.security.authorization.cache.AuthorizationCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Database-backed persistence adapter implementing {@link RoleResolver} from {@code devflow-auth}
 * with Stage 6 authorization caching.
 *
 * <p>Enforces core resolution invariants:
 * <ul>
 *   <li>Only active organization memberships can resolve organization or project roles.</li>
 *   <li>Active scoped assignments are loaded from {@code organization_role_assignments} and {@code project_role_assignments}.</li>
 *   <li>Scope-aware inheritance: Organization roles are evaluated alongside project roles for project contexts (ADR-001 §4.2).</li>
 *   <li>Legacy fallback: Unmigrated members without assignment history resolve their legacy {@link OrganizationRole}.</li>
 *   <li>Revocation hardening: Once any assignment exists for a user, legacy fallback is disabled forever (Stage 4.1).</li>
 *   <li>Caching & Invalidation: Resolved roles are cached in Redis/local cache and evicted on assignment mutations (Stage 6).</li>
 * </ul>
 */
@Component
public class DatabaseRoleResolver implements RoleResolver {

    private final OrganizationRoleAssignmentRepository orgAssignmentRepository;
    private final ProjectRoleAssignmentRepository projectAssignmentRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final AuthorizationCacheService cacheService;

    @Autowired
    public DatabaseRoleResolver(
            OrganizationRoleAssignmentRepository orgAssignmentRepository,
            ProjectRoleAssignmentRepository projectAssignmentRepository,
            OrganizationMemberRepository organizationMemberRepository,
            @Autowired(required = false) AuthorizationCacheService cacheService
    ) {
        this.orgAssignmentRepository = Objects.requireNonNull(orgAssignmentRepository, "orgAssignmentRepository must not be null");
        this.projectAssignmentRepository = Objects.requireNonNull(projectAssignmentRepository, "projectAssignmentRepository must not be null");
        this.organizationMemberRepository = Objects.requireNonNull(organizationMemberRepository, "organizationMemberRepository must not be null");
        this.cacheService = cacheService;
    }

    public DatabaseRoleResolver(
            OrganizationRoleAssignmentRepository orgAssignmentRepository,
            ProjectRoleAssignmentRepository projectAssignmentRepository,
            OrganizationMemberRepository organizationMemberRepository
    ) {
        this(orgAssignmentRepository, projectAssignmentRepository, organizationMemberRepository, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Role> resolveRoles(UUID userId, AuthorizationContext context) {
        if (userId == null || context == null) {
            return Collections.emptySet();
        }

        Instant now = Instant.now();

        return switch (context.getScope()) {
            case ORGANIZATION -> resolveOrganizationRoles(userId, context, now);
            case PROJECT -> resolveProjectRoles(userId, context, now);
            case PLATFORM -> Collections.emptySet();
        };
    }

    private Set<Role> resolveOrganizationRoles(UUID userId, AuthorizationContext context, Instant now) {
        Optional<UUID> orgIdOpt = context.getOrganizationId();
        if (orgIdOpt.isEmpty()) {
            return Collections.emptySet();
        }
        UUID organizationId = orgIdOpt.get();

        // 1. Check authorization cache
        if (cacheService != null) {
            Optional<Set<Role>> cached = cacheService.getOrganizationRoles(organizationId, userId);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        // 2. Invariant: User must have an ACTIVE membership record in the organization
        Optional<OrganizationMember> memberOpt = organizationMemberRepository
                .findByOrganizationIdAndUserId(organizationId, userId);

        if (memberOpt.isEmpty() || memberOpt.get().getStatus() != OrganizationMembershipStatus.ACTIVE) {
            if (cacheService != null) {
                cacheService.putOrganizationRoles(organizationId, userId, Collections.emptySet());
            }
            return Collections.emptySet();
        }

        OrganizationMember member = memberOpt.get();

        // 3. Query persistent organization role assignments
        List<OrganizationRoleAssignment> activeAssignments = orgAssignmentRepository
                .findActiveByOrganizationIdAndUserId(organizationId, userId, now);

        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (OrganizationRoleAssignment assignment : activeAssignments) {
            roles.add(assignment.getRole().toDomainRole());
        }

        // 4. Backward compatibility: legacy membership role fallback strictly for unmigrated members
        // without any role assignment history in this organization (ADR-001, Stage 4.1).
        if (roles.isEmpty() && member.getRole() != null) {
            boolean hasTransitioned = orgAssignmentRepository.hasAnyAssignments(organizationId, userId);
            if (!hasTransitioned) {
                roles.add(mapLegacyOrganizationRole(member.getRole()));
            }
        }

        Set<Role> unmodifiableRoles = Collections.unmodifiableSet(roles);

        // 5. Store in authorization cache
        if (cacheService != null) {
            cacheService.putOrganizationRoles(organizationId, userId, unmodifiableRoles);
        }

        return unmodifiableRoles;
    }

    private Set<Role> resolveProjectRoles(UUID userId, AuthorizationContext context, Instant now) {
        Optional<UUID> orgIdOpt = context.getOrganizationId();
        Optional<UUID> projIdOpt = context.getProjectId();
        if (orgIdOpt.isEmpty() || projIdOpt.isEmpty()) {
            return Collections.emptySet();
        }
        UUID organizationId = orgIdOpt.get();
        UUID projectId = projIdOpt.get();

        // 1. Check authorization cache
        if (cacheService != null) {
            Optional<Set<Role>> cached = cacheService.getProjectRoles(organizationId, projectId, userId);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        // 2. Invariant: Project role requires active organization membership
        boolean isMemberActive = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, userId)
                .map(m -> m.getStatus() == OrganizationMembershipStatus.ACTIVE)
                .orElse(false);

        if (!isMemberActive) {
            if (cacheService != null) {
                cacheService.putProjectRoles(organizationId, projectId, userId, Collections.emptySet());
            }
            return Collections.emptySet();
        }

        Set<Role> roles = EnumSet.noneOf(Role.class);

        // 3. Include organization-level roles for scope-aware inheritance evaluation (§4.2, ADR-001)
        roles.addAll(resolveOrganizationRoles(userId, context, now));

        // 4. Query persistent project role assignments
        List<ProjectRoleAssignment> activeProjectAssignments = projectAssignmentRepository
                .findActiveByOrganizationIdAndProjectIdAndUserId(organizationId, projectId, userId, now);

        for (ProjectRoleAssignment assignment : activeProjectAssignments) {
            roles.add(assignment.getRole().toDomainRole());
        }

        Set<Role> unmodifiableRoles = Collections.unmodifiableSet(roles);

        // 5. Store in authorization cache
        if (cacheService != null) {
            cacheService.putProjectRoles(organizationId, projectId, userId, unmodifiableRoles);
        }

        return unmodifiableRoles;
    }

    /**
     * Maps existing {@link OrganizationRole} values to canonical {@link Role} values.
     * Specifically, {@code DEVELOPER} maps to {@code Role.ORGANIZATION_MEMBER}.
     *
     * @param legacyRole the legacy organization role
     * @return the corresponding canonical Role
     */
    public static Role mapLegacyOrganizationRole(OrganizationRole legacyRole) {
        Objects.requireNonNull(legacyRole, "legacyRole must not be null");
        return switch (legacyRole) {
            case OWNER -> Role.ORGANIZATION_OWNER;
            case ADMIN -> Role.ORGANIZATION_ADMIN;
            case DEVELOPER -> Role.ORGANIZATION_MEMBER;
            case VIEWER -> Role.ORGANIZATION_VIEWER;
        };
    }
}
