package com.devflow.role.adapter;

import com.devflow.modules.auth.evaluator.TenantBoundaryValidator;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.domain.ProjectRoleAssignment;
import com.devflow.role.registry.ProjectRegistry;
import com.devflow.role.repository.ProjectRoleAssignmentRepository;
import com.devflow.team.repository.TeamRepository;
import com.devflow.workspace.repository.WorkspaceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Database-backed persistence adapter implementing {@link TenantBoundaryValidator} from {@code devflow-auth}.
 *
 * <p>Enforces tenant isolation by verifying:
 * <ul>
 *   <li>Project tenant boundary: Verifies whether a project belongs to the tenant organization.
 *       In Stage 2/Stage 4, project persistence is anchored by scoped project role assignments.
 *       Fails closed if the project has no verified association with the tenant. Missing role
 *       assignments are NEVER interpreted as proof that access is allowed.</li>
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
    private final ProjectRegistry projectRegistry;

    @Autowired
    public DatabaseTenantBoundaryValidator(
            ProjectRoleAssignmentRepository projectRoleAssignmentRepository,
            WorkspaceRepository workspaceRepository,
            TeamRepository teamRepository,
            OrganizationRepository organizationRepository,
            @Autowired(required = false) ProjectRegistry projectRegistry
    ) {
        this.projectRoleAssignmentRepository = Objects.requireNonNull(
                projectRoleAssignmentRepository, "projectRoleAssignmentRepository must not be null");
        this.workspaceRepository = Objects.requireNonNull(
                workspaceRepository, "workspaceRepository must not be null");
        this.teamRepository = Objects.requireNonNull(
                teamRepository, "teamRepository must not be null");
        this.organizationRepository = Objects.requireNonNull(
                organizationRepository, "organizationRepository must not be null");
        this.projectRegistry = projectRegistry;
    }

    public DatabaseTenantBoundaryValidator(
            ProjectRoleAssignmentRepository projectRoleAssignmentRepository,
            WorkspaceRepository workspaceRepository,
            TeamRepository teamRepository,
            OrganizationRepository organizationRepository
    ) {
        this(projectRoleAssignmentRepository, workspaceRepository, teamRepository, organizationRepository, null);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isProjectInOrganization(UUID organizationId, UUID projectId) {
        if (organizationId == null || projectId == null) {
            return false;
        }

        if (!organizationRepository.existsById(organizationId)) {
            return false;
        }

        if (projectRegistry != null) {
            Optional<UUID> registeredOrg = projectRegistry.getProjectOrganizationId(projectId);
            if (registeredOrg.isPresent() && !organizationId.equals(registeredOrg.get())) {
                return false;
            }
        }

        List<ProjectRoleAssignment> assignments = projectRoleAssignmentRepository.findByProjectId(projectId);
        if (assignments.isEmpty()) {
            if (projectRegistry != null && projectRegistry.isProjectRegistered(organizationId, projectId)) {
                return true;
            }
            // Fail closed: an unregistered project with no role assignments has no verified association with this organization.
            // Missing role assignments must never be interpreted as proof that access is allowed.
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

        return switch (resourceType.toLowerCase(Locale.ROOT)) {
            case "organization", "org" -> organizationRepository.existsById(entityId)
                    ? Optional.of(entityId)
                    : Optional.empty();

            case "workspace" -> workspaceRepository.findById(entityId)
                    .map(w -> w.getOrganization().getId());

            case "team" -> teamRepository.findById(entityId)
                    .map(t -> t.getWorkspace().getOrganization().getId());

            case "project" -> {
                if (projectRegistry != null) {
                    Optional<UUID> registeredOrg = projectRegistry.getProjectOrganizationId(entityId);
                    if (registeredOrg.isPresent()) {
                        List<ProjectRoleAssignment> assignments = projectRoleAssignmentRepository.findByProjectId(entityId);
                        if (assignments.isEmpty() || assignments.stream().allMatch(a -> a.getOrganization() != null && registeredOrg.get().equals(a.getOrganization().getId()))) {
                            yield registeredOrg;
                        } else {
                            yield Optional.empty();
                        }
                    }
                }
                List<ProjectRoleAssignment> assignments = projectRoleAssignmentRepository.findByProjectId(entityId);
                if (assignments.isEmpty()) {
                    yield Optional.empty();
                }
                UUID orgId = assignments.get(0).getOrganization() != null ? assignments.get(0).getOrganization().getId() : null;
                if (orgId == null) {
                    yield Optional.empty();
                }
                boolean consistent = assignments.stream()
                        .allMatch(a -> a.getOrganization() != null && orgId.equals(a.getOrganization().getId()));
                yield consistent ? Optional.of(orgId) : Optional.empty();
            }

            default -> Optional.empty();
        };
    }
}
