package com.devflow.role.controller;

import com.devflow.modules.auth.domain.Role;
import com.devflow.role.domain.ProjectRoleAssignment;
import com.devflow.role.dto.GrantRoleRequest;
import com.devflow.role.dto.RevokeRoleRequest;
import com.devflow.role.dto.RoleAssignmentResponse;
import com.devflow.role.exception.InvalidRoleAssignmentException;
import com.devflow.role.service.RoleAssignmentService;
import com.devflow.security.user.DevFlowUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST controller for managing project-scoped role assignments (Stage 6).
 *
 * <p>Base path: {@code /api/v1/organizations/{organizationId}/projects/{projectId}/roles}
 *
 * <h2>Security Invariants</h2>
 * <ul>
 *   <li>Only active organization {@code OWNER}, {@code ADMIN}, or project {@code LEAD} can grant/revoke project roles.</li>
 *   <li>Only canonical project roles ({@code PROJECT_LEAD}, {@code PROJECT_MEMBER}, {@code PROJECT_VIEWER}) can be assigned.</li>
 *   <li>Tenant boundary validation: Project must strictly belong to the enclosing organization tenant.</li>
 *   <li>Target user must hold active membership in the enclosing organization tenant.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/roles")
@Tag(name = "Project Roles", description = "Endpoints for managing project role assignments")
public class ProjectRoleController {

    private final RoleAssignmentService roleAssignmentService;

    public ProjectRoleController(RoleAssignmentService roleAssignmentService) {
        this.roleAssignmentService = Objects.requireNonNull(roleAssignmentService, "roleAssignmentService must not be null");
    }

    /**
     * Lists project-scoped role assignments for the specified project.
     *
     * @param organizationId tenant organization UUID
     * @param projectId target project UUID
     * @param activeOnly whether to filter active assignments only (default true)
     * @param userDetails authenticated principal
     * @return list of project role assignments
     */
    @GetMapping
    @Operation(summary = "List project role assignments",
            description = "Lists role assignments in the specified project. Requires OWNER, ADMIN, or project member.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project role assignments retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — caller not authorized in project"),
            @ApiResponse(responseCode = "404", description = "Organization or project not found")
    })
    public ResponseEntity<List<RoleAssignmentResponse>> listProjectRoles(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @RequestParam(required = false, defaultValue = "true") boolean activeOnly,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        List<ProjectRoleAssignment> assignments = roleAssignmentService.listProjectRoleAssignments(
                organizationId, projectId, userDetails.getId(), activeOnly);

        List<RoleAssignmentResponse> response = assignments.stream()
                .map(RoleAssignmentResponse::from)
                .toList();

        return ResponseEntity.ok(response);
    }

    /**
     * Grants a project-scoped role to an organization member.
     *
     * @param organizationId tenant organization UUID
     * @param projectId target project UUID
     * @param request grant payload
     * @param userDetails authenticated principal
     * @return created assignment
     */
    @PostMapping
    @Operation(summary = "Grant project role",
            description = "Assigns a project-scoped role to an organization member. Requires OWNER, ADMIN, or PROJECT_LEAD.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Project role granted successfully",
                    content = @Content(schema = @Schema(implementation = RoleAssignmentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid project role name or tenant boundary violation"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — caller lacks project assignment authority"),
            @ApiResponse(responseCode = "409", description = "Active assignment for this role already exists")
    })
    public ResponseEntity<RoleAssignmentResponse> grantProjectRole(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @Valid @RequestBody GrantRoleRequest request,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        Role role = parseAndValidateProjectRole(request.getRole());

        ProjectRoleAssignment assignment = roleAssignmentService.assignProjectRole(
                organizationId,
                projectId,
                request.getUserId(),
                role,
                userDetails.getId(),
                request.getExpiresAt()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(RoleAssignmentResponse.from(assignment));
    }

    /**
     * Revokes a project role assignment.
     *
     * @param organizationId tenant organization UUID
     * @param projectId target project UUID
     * @param assignmentId assignment UUID to revoke
     * @param request optional revocation reason payload
     * @param userDetails authenticated principal
     * @return 204 No Content
     */
    @DeleteMapping("/{assignmentId}")
    @Operation(summary = "Revoke project role assignment",
            description = "Revokes an active project role assignment. Requires OWNER, ADMIN, or PROJECT_LEAD.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Project role assignment revoked successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — caller lacks project revocation authority"),
            @ApiResponse(responseCode = "404", description = "Project role assignment not found")
    })
    public ResponseEntity<Void> revokeProjectRole(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @PathVariable UUID assignmentId,
            @RequestBody(required = false) RevokeRoleRequest request,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        String reason = (request != null && request.getReason() != null && !request.getReason().isBlank())
                ? request.getReason().trim()
                : "ADMIN_REVOCATION";

        roleAssignmentService.revokeProjectRoleAssignment(
                organizationId,
                projectId,
                assignmentId,
                userDetails.getId(),
                reason
        );

        return ResponseEntity.noContent().build();
    }

    private Role parseAndValidateProjectRole(String roleStr) {
        if (roleStr == null || roleStr.isBlank()) {
            throw new InvalidRoleAssignmentException("Role name must not be blank");
        }

        Role role;
        try {
            role = Role.valueOf(roleStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidRoleAssignmentException("Invalid role name: " + roleStr);
        }

        if (role.isPlatformRole()) {
            throw new InvalidRoleAssignmentException("Platform roles cannot be assigned through project APIs: " + role);
        }

        if (!role.isProjectRole()) {
            throw new InvalidRoleAssignmentException("Cannot assign non-project role to project: " + role);
        }

        return role;
    }
}

