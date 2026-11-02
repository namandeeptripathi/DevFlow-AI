package com.devflow.role.adapter;

import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.context.OrganizationAuthorizationContext;
import com.devflow.modules.auth.context.ProjectAuthorizationContext;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.evaluator.RoleResolver;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.role.domain.OrganizationRoleAssignment;
import com.devflow.role.domain.ProjectRoleAssignment;
import com.devflow.role.repository.OrganizationRoleAssignmentRepository;
import com.devflow.role.repository.ProjectRoleAssignmentRepository;
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
import java.util.stream.Collectors;

/**
 * Database-backed persistence adapter implementing {@link RoleResolver} from {@code devflow-auth}.
 *
 * <p>Authoritatively resolves active canonical {@link Role} assignments from persistent database storage:
 * <ul>
 *   <li>Filters out revoked or expired assignments based on current evaluation timestamp.</li>
 *   <li>Enforces that project roles require active organization membership.</li>
 *   <li>Preserves full backward compatibility with legacy {@code organization_members} records,
 *       mapping {@link OrganizationRole#DEVELOPER} to {@link Role#ORGANIZATION_MEMBER}.</li>
 *   <li>Suspended, invited, or missing memberships immediately fail closed and resolve to an empty set.</li>
 * </ul>
 *
 * @see Role
 * @see AuthorizationContext
 * @see OrganizationRoleAssignmentRepository
 * @see ProjectRoleAssignmentRepository
 */
@Component
public class DatabaseRoleResolver implements RoleResolver {

    private final OrganizationRoleAssignmentRepository orgAssignmentRepository;
    private final ProjectRoleAssignmentRepository projectAssignmentRepository;
    private final OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    public DatabaseRoleResolver(
            OrganizationRoleAssignmentRepository orgAssignmentRepository,
            ProjectRoleAssignmentRepository projectAssignmentRepository,
            OrganizationMemberRepository organizationMemberRepository
    ) {
        this.orgAssignmentRepository = Objects.requireNonNull(orgAssignmentRepository, "orgAssignmentRepository must not be null");
        this.projectAssignmentRepository = Objects.requireNonNull(projectAssignmentRepository, "projectAssignmentRepository must not be null");
        this.organizationMemberRepository = Objects.requireNonNull(organizationMemberRepository, "organizationMemberRepository must not be null");
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

        // 1. Invariant: User must have an ACTIVE membership record in the organization
        Optional<OrganizationMember> memberOpt = organizationMemberRepository
                .findByOrganizationIdAndUserId(organizationId, userId);

        if (memberOpt.isEmpty() || memberOpt.get().getStatus() != OrganizationMembershipStatus.ACTIVE) {
            return Collections.emptySet();
        }

        OrganizationMember member = memberOpt.get();

        // 2. Query persistent organization role assignments
        List<OrganizationRoleAssignment> activeAssignments = orgAssignmentRepository
                .findActiveByOrganizationIdAndUserId(organizationId, userId, now);

        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (OrganizationRoleAssignment assignment : activeAssignments) {
            roles.add(assignment.getRole().toDomainRole());
        }

        // 3. Backward compatibility: if no explicit assignment exists yet, map legacy membership role
        if (roles.isEmpty() && member.getRole() != null) {
            roles.add(mapLegacyOrganizationRole(member.getRole()));
        }

        return Collections.unmodifiableSet(roles);
    }

    private Set<Role> resolveProjectRoles(UUID userId, AuthorizationContext context, Instant now) {
        Optional<UUID> orgIdOpt = context.getOrganizationId();
        Optional<UUID> projIdOpt = context.getProjectId();
        if (orgIdOpt.isEmpty() || projIdOpt.isEmpty()) {
            return Collections.emptySet();
        }
        UUID organizationId = orgIdOpt.get();
        UUID projectId = projIdOpt.get();

        // 1. Invariant: Project role requires active organization membership
        boolean isMemberActive = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, userId)
                .map(m -> m.getStatus() == OrganizationMembershipStatus.ACTIVE)
                .orElse(false);

        if (!isMemberActive) {
            return Collections.emptySet();
        }

        Set<Role> roles = EnumSet.noneOf(Role.class);

        // 2. Include organization-level roles for scope-aware inheritance evaluation (§4.2, ADR-001)
        roles.addAll(resolveOrganizationRoles(userId, context, now));

        // 3. Query persistent project role assignments
        List<ProjectRoleAssignment> activeProjectAssignments = projectAssignmentRepository
                .findActiveByOrganizationIdAndProjectIdAndUserId(organizationId, projectId, userId, now);

        for (ProjectRoleAssignment assignment : activeProjectAssignments) {
            roles.add(assignment.getRole().toDomainRole());
        }

        return Collections.unmodifiableSet(roles);
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
