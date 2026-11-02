package com.devflow.role.adapter;

import com.devflow.modules.auth.evaluator.MembershipValidator;
import com.devflow.modules.auth.evaluator.ProjectAssignmentValidator;
import com.devflow.modules.auth.evaluator.TenantBoundaryValidator;
import com.devflow.role.repository.ProjectRoleAssignmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Database-backed persistence adapter implementing {@link ProjectAssignmentValidator} from {@code devflow-auth}.
 *
 * <p>Enforces that:
 * <ul>
 *   <li>The user must hold an active organization membership in the project's tenant.</li>
 *   <li>The project must belong to the specified organization boundary (when {@link TenantBoundaryValidator} is present).</li>
 *   <li>The user must hold at least one active, non-revoked, unexpired project role assignment.</li>
 * </ul>
 */
@Component
public class DatabaseProjectAssignmentValidator implements ProjectAssignmentValidator {

    private final ProjectRoleAssignmentRepository projectRoleAssignmentRepository;
    private final MembershipValidator membershipValidator;
    private TenantBoundaryValidator tenantBoundaryValidator;

    @Autowired
    public DatabaseProjectAssignmentValidator(
            ProjectRoleAssignmentRepository projectRoleAssignmentRepository,
            MembershipValidator membershipValidator,
            @Autowired(required = false) TenantBoundaryValidator tenantBoundaryValidator
    ) {
        this.projectRoleAssignmentRepository = Objects.requireNonNull(
                projectRoleAssignmentRepository, "projectRoleAssignmentRepository must not be null");
        this.membershipValidator = Objects.requireNonNull(
                membershipValidator, "membershipValidator must not be null");
        this.tenantBoundaryValidator = tenantBoundaryValidator;
    }

    public void setTenantBoundaryValidator(TenantBoundaryValidator tenantBoundaryValidator) {
        this.tenantBoundaryValidator = tenantBoundaryValidator;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAssignedToProject(UUID userId, UUID organizationId, UUID projectId) {
        if (userId == null || organizationId == null || projectId == null) {
            return false;
        }

        // Project assignments strictly require active organization membership
        if (!membershipValidator.isActiveMember(userId, organizationId)) {
            return false;
        }

        // Tenant boundary check: Project must belong to the organization
        if (tenantBoundaryValidator != null && !tenantBoundaryValidator.isProjectInOrganization(organizationId, projectId)) {
            return false;
        }

        return projectRoleAssignmentRepository.hasActiveProjectAssignment(
                organizationId, projectId, userId, Instant.now());
    }
}
