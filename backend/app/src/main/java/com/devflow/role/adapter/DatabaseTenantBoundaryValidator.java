package com.devflow.role.adapter;

import com.devflow.modules.auth.evaluator.TenantBoundaryValidator;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.domain.ProjectRoleAssignment;
import com.devflow.role.repository.ProjectRoleAssignmentRepository;
import com.devflow.team.repository.TeamRepository;
import com.devflow.workspace.repository.WorkspaceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Database-backed persistence adapter implementing {@link TenantBoundaryValidator} from {@code devflow-auth}.
 *
 * <p>Enforces tenant isolation by verifying:
 * <ul>
 *   <li>Project tenant boundary: Verifies whether a project belongs to the tenant organization.
 *       In Stage 5, project persistence is anchored by scoped project role assignments.
 *       Fails closed if the project has no verified association with the tenant.</li>
 *   <li>Resource tenant boundary: Resolves the owning organization for domain resources
 *       (organizations, workspaces, teams, projects) from trusted relational data.</li>
 * </ul>
 *
 * @see TenantBoundaryValidator
 * @see ProjectRoleAssignmentRepository
 * @see WorkspaceRepository
 * @see TeamRepository
 * @see OrganizationRepository
 */
@Component
public class DatabaseTenantBoundaryValidator implements TenantBoundaryValidator {

    private final ProjectRoleAssignmentRepository projectRoleAssignmentRepository;
    private final WorkspaceRepository workspaceRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;

    @Autowired
    public DatabaseTenantBoundaryValidator(
            ProjectRoleAssignmentRepository projectRoleAssignmentRepository,
            WorkspaceRepository workspaceRepository,
            TeamRepository teamRepository,
            OrganizationRepository organizationRepository
    ) {
        this.projectRoleAssignmentRepository = Objects.requireNonNull(
                projectRoleAssignmentRepository, "projectRoleAssignmentRepository must not be null");
        this.workspaceRepository = Objects.requireNonNull(
                workspaceRepository, "workspaceRepository must not be null");
        this.teamRepository = Objects.requireNonNull(
                teamRepository, "teamRepository must not be null");
        this.organizationRepository = Objects.requireNonNull(
                organizationRepository, "organizationRepository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isProjectInOrganization(UUID organizationId, UUID projectId) {
        if (organizationId == null || projectId == null) {
            return false;
        }

        List<ProjectRoleAssignment> assignments = projectRoleAssignmentRepository.findByProjectId(projectId);
        if (assignments.isEmpty()) {
            // Fails closed when project infrastructure has no recorded association
            return false;
        }

        return assignments.stream()
                .allMatch(a -> a.getOrganization() != null && organizationId.equals(a.getOrganization().getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> getResourceOrganizationId(String resourceType, String resourceId) {
        if (resourceType == null || resourceId == null || resourceId.isBlank()) {
            return Optional.empty();
        }

        UUID entityId;
        try {
            entityId = UUID.fromString(resourceId.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        return switch (resourceType.toLowerCase()) {
            case "organization", "org" -> organizationRepository.existsById(entityId)
                    ? Optional.of(entityId)
                    : Optional.empty();

            case "workspace" -> workspaceRepository.findById(entityId)
                    .map(w -> w.getOrganization().getId());

            case "team" -> teamRepository.findById(entityId)
                    .map(t -> t.getWorkspace().getOrganization().getId());

            case "project" -> projectRoleAssignmentRepository.findByProjectId(entityId).stream()
                    .map(a -> a.getOrganization().getId())
                    .findFirst();

            default -> Optional.empty();
        };
    }
}
