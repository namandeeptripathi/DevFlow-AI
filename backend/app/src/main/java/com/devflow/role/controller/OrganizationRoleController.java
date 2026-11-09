package com.devflow.role.controller;

import com.devflow.modules.auth.domain.Role;
import com.devflow.role.domain.CustomRoleAssignment;
import com.devflow.role.domain.CustomRoleEntity;
import com.devflow.role.domain.OrganizationRoleAssignment;
import com.devflow.role.dto.GrantRoleRequest;
import com.devflow.role.dto.RevokeRoleRequest;
import com.devflow.role.dto.RoleAssignmentResponse;
import com.devflow.role.exception.InvalidRoleAssignmentException;
import com.devflow.role.exception.RoleAssignmentNotFoundException;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for managing organization-scoped role assignments (Stage 6).
 *
 * <p>Base path: {@code /api/v1/organizations/{organizationId}/roles}
 *
 * <h2>Security Invariants</h2>
 * <ul>
 *   <li>Only active organization {@code OWNER} or {@code ADMIN} can list or grant roles.</li>
 *   <li>Granting/revoking {@code ORGANIZATION_BILLING} or {@code ORGANIZATION_OWNER} requires {@code OWNER}.</li>
 *   <li>Sole active organization owner cannot be revoked.</li>
 *   <li>Platform roles cannot be granted via organization APIs.</li>
 *   <li>Target user must hold active membership in the enclosing organization tenant.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/roles")
@Tag(name = "Organization Roles", description = "Endpoints for managing organization role assignments")
public class OrganizationRoleController {

    private final RoleAssignmentService roleAssignmentService;

    public OrganizationRoleController(RoleAssignmentService roleAssignmentService) {
        this.roleAssignmentService = Objects.requireNonNull(roleAssignmentService, "roleAssignmentService must not be null");
    }

    /**
     * Lists organization-scoped role assignments for the specified tenant.
     *
     * @param organizationId tenant organization UUID
     * @param activeOnly whether to filter active assignments only (default true)
     * @param userDetails authenticated principal
     * @return list of role assignments
     */
    @GetMapping
    @Operation(summary = "List organization role assignments",
            description = "Lists role assignments in the specified organization. Requires OWNER or ADMIN role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role assignments retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires OWNER or ADMIN role"),
            @ApiResponse(responseCode = "404", description = "Organization not found")
    })
    public ResponseEntity<List<RoleAssignmentResponse>> listOrganizationRoles(
            @PathVariable UUID organizationId,
            @RequestParam(required = false, defaultValue = "true") boolean activeOnly,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        List<OrganizationRoleAssignment> canonicalAssignments = roleAssignmentService.listOrganizationRoleAssignments(
                organizationId, userDetails.getId(), activeOnly);
        List<CustomRoleAssignment> customAssignments = roleAssignmentService.listCustomRoleAssignments(
                organizationId, userDetails.getId(), activeOnly);

        List<RoleAssignmentResponse> response = new ArrayList<>();
        canonicalAssignments.forEach(a -> response.add(RoleAssignmentResponse.from(a)));
        customAssignments.forEach(a -> response.add(RoleAssignmentResponse.from(a)));

        return ResponseEntity.ok(response);
    }

    /**
     * Grants an organization-scoped role (canonical or custom) to an organization member.
     *
     * @param organizationId tenant organization UUID
     * @param request grant payload
     * @param userDetails authenticated principal
     * @return created assignment
     */
    @PostMapping
    @Operation(summary = "Grant organization role",
            description = "Assigns an organization-scoped canonical or custom role to a member. Requires OWNER or ADMIN role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Role granted successfully",
                    content = @Content(schema = @Schema(implementation = RoleAssignmentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid role name or non-member target user"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — insufficient permissions or role escalation attempt"),
            @ApiResponse(responseCode = "409", description = "Active assignment for this role already exists")
    })
    public ResponseEntity<RoleAssignmentResponse> grantOrganizationRole(
            @PathVariable UUID organizationId,
            @Valid @RequestBody GrantRoleRequest request,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        String roleStr = request.getRole();
        if (roleStr == null || roleStr.isBlank()) {
            throw new InvalidRoleAssignmentException("Role name must not be blank");
        }

        // 1. Try canonical role first
        Role canonicalRole = tryParseCanonicalRole(roleStr);
        if (canonicalRole != null) {
            if (canonicalRole.isPlatformRole()) {
                throw new InvalidRoleAssignmentException("Platform roles cannot be assigned through organization APIs: " + canonicalRole);
            }
            if (!canonicalRole.isOrganizationRole()) {
                throw new InvalidRoleAssignmentException("Cannot assign non-organization role to organization: " + canonicalRole);
            }

            OrganizationRoleAssignment assignment = roleAssignmentService.assignOrganizationRole(
                    organizationId,
                    request.getUserId(),
                    canonicalRole,
                    userDetails.getId(),
                    request.getExpiresAt()
            );

            return ResponseEntity.status(HttpStatus.CREATED).body(RoleAssignmentResponse.from(assignment));
        }

        // 2. Try custom role by name or UUID in this organization
        Optional<CustomRoleEntity> customRoleOpt = roleAssignmentService.findCustomRoleByNameOrId(organizationId, roleStr);
        if (customRoleOpt.isPresent()) {
            CustomRoleAssignment customAssignment = roleAssignmentService.assignCustomRole(
                    organizationId,
                    request.getUserId(),
                    customRoleOpt.get().getId(),
                    userDetails.getId(),
                    request.getExpiresAt()
            );

            return ResponseEntity.status(HttpStatus.CREATED).body(RoleAssignmentResponse.from(customAssignment));
        }

        throw new InvalidRoleAssignmentException("Invalid role name: " + roleStr);
    }

    /**
     * Revokes an organization role assignment (canonical or custom).
     *
     * @param organizationId tenant organization UUID
     * @param assignmentId assignment UUID to revoke
     * @param request optional revocation reason payload
     * @param userDetails authenticated principal
     * @return 204 No Content
     */
    @DeleteMapping("/{assignmentId}")
    @Operation(summary = "Revoke organization role assignment",
            description = "Revokes an active organization canonical or custom role assignment. Requires OWNER or ADMIN role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Role assignment revoked successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — insufficient permissions or attempt to revoke sole owner"),
            @ApiResponse(responseCode = "404", description = "Role assignment not found")
    })
    public ResponseEntity<Void> revokeOrganizationRole(
            @PathVariable UUID organizationId,
            @PathVariable UUID assignmentId,
            @RequestBody(required = false) RevokeRoleRequest request,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        String reason = (request != null && request.getReason() != null && !request.getReason().isBlank())
                ? request.getReason().trim()
                : "ADMIN_REVOCATION";

        try {
            roleAssignmentService.revokeOrganizationRoleAssignment(
                    organizationId,
                    assignmentId,
                    userDetails.getId(),
                    reason
            );
        } catch (RoleAssignmentNotFoundException e) {
            // If not found as canonical, attempt revoking as custom role assignment
            roleAssignmentService.revokeCustomRoleAssignment(
                    organizationId,
                    assignmentId,
                    userDetails.getId(),
                    reason
            );
        }

        return ResponseEntity.noContent().build();
    }

    private Role tryParseCanonicalRole(String roleStr) {
        try {
            return Role.valueOf(roleStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

