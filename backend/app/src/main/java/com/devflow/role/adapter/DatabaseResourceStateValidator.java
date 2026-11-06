package com.devflow.role.adapter;

import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.evaluator.ResourceStateValidator;
import com.devflow.team.domain.Team;
import com.devflow.team.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Database-backed persistence adapter implementing {@link ResourceStateValidator} from {@code devflow-auth}.
 *
 * <p>Enforces resource lifecycle constraints:
 * <ul>
 *   <li>Archived teams prohibit mutative operations (write, configure, delete, member additions);
 *       read operations remain permitted.</li>
 *   <li>Missing resources or invalid state configurations fail closed.</li>
 * </ul>
 *
 * @see ResourceStateValidator
 * @see TeamRepository
 */
@Component
public class DatabaseResourceStateValidator implements ResourceStateValidator {

    private final TeamRepository teamRepository;

    @Autowired
    public DatabaseResourceStateValidator(TeamRepository teamRepository) {
        this.teamRepository = Objects.requireNonNull(teamRepository, "teamRepository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOperationPermitted(
            AuthorizationContext context,
            String resourceType,
            String resourceId,
            Permission permission
    ) {
        if (resourceType == null || permission == null) {
            return false;
        }

        if ("team".equalsIgnoreCase(resourceType) && resourceId != null && !resourceId.isBlank()) {
            try {
                UUID teamId = UUID.fromString(resourceId.trim());
                Optional<Team> teamOpt = teamRepository.findById(teamId);
                if (teamOpt.isPresent() && teamOpt.get().isArchived()) {
                    // Archived teams only permit read operations
                    return "read".equalsIgnoreCase(permission.action())
                            || "view".equalsIgnoreCase(permission.action());
                }
            } catch (IllegalArgumentException e) {
                return false; // Malformed resource ID fails closed
            }
        }

        return true;
    }
}
