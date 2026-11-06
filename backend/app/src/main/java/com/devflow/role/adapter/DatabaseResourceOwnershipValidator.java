package com.devflow.role.adapter;

import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.evaluator.ResourceOwnershipValidator;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.team.domain.TeamMember;
import com.devflow.team.repository.TeamMemberRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Database-backed persistence adapter implementing {@link ResourceOwnershipValidator} from {@code devflow-auth}.
 *
 * <p>Authoritatively evaluates resource ownership facts from persistent storage:
 * <ul>
 *   <li>Organization ownership: Confirms whether the principal is the recorded founding/current owner.</li>
 *   <li>Team leadership/ownership: Evaluates whether the principal is the designated team lead.</li>
 *   <li>User profile/preference ownership: Validates that the requested resource identifier matches the subject.</li>
 *   <li>Missing, malformed, or unverified ownership facts fail closed immediately.</li>
 * </ul>
 *
 * @see ResourceOwnershipValidator
 * @see OrganizationRepository
 * @see TeamMemberRepository
 */
@Component
public class DatabaseResourceOwnershipValidator implements ResourceOwnershipValidator {

    private final OrganizationRepository organizationRepository;
    private final TeamMemberRepository teamMemberRepository;

    @Autowired
    public DatabaseResourceOwnershipValidator(
            OrganizationRepository organizationRepository,
            TeamMemberRepository teamMemberRepository
    ) {
        this.organizationRepository = Objects.requireNonNull(
                organizationRepository, "organizationRepository must not be null");
        this.teamMemberRepository = Objects.requireNonNull(
                teamMemberRepository, "teamMemberRepository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOwner(UUID userId, AuthorizationContext context, String resourceType, String resourceId) {
        if (userId == null || resourceType == null) {
            return false;
        }

        return switch (resourceType.toLowerCase()) {
            case "organization", "org" -> verifyOrganizationOwnership(userId, context, resourceId);
            case "team" -> verifyTeamOwnership(userId, context, resourceId);
            case "user", "profile", "preferences" -> resourceId != null && resourceId.equals(userId.toString());
            default -> false; // Fail closed for unsupported resource types
        };
    }

    private boolean verifyOrganizationOwnership(UUID userId, AuthorizationContext context, String resourceId) {
        UUID targetOrgId = resolveOrganizationId(context, resourceId);
        if (targetOrgId == null) {
            return false;
        }

        return organizationRepository.findById(targetOrgId)
                .map(org -> org.getOwner() != null && userId.equals(org.getOwner().getId()))
                .orElse(false);
    }

    private boolean verifyTeamOwnership(UUID userId, AuthorizationContext context, String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return false;
        }

        Optional<UUID> orgIdOpt = context.getOrganizationId();
        if (orgIdOpt.isEmpty()) {
            return false;
        }

        try {
            UUID teamId = UUID.fromString(resourceId.trim());
            return teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(orgIdOpt.get(), teamId, userId)
                    .map(TeamMember::isLead)
                    .orElse(false);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private UUID resolveOrganizationId(AuthorizationContext context, String resourceId) {
        if (resourceId != null && !resourceId.isBlank()) {
            try {
                return UUID.fromString(resourceId.trim());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return context != null ? context.getOrganizationId().orElse(null) : null;
    }
}
