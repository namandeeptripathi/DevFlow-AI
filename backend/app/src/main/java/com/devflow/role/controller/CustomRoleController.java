package com.devflow.role.controller;

import com.devflow.role.domain.CustomRoleEntity;
import com.devflow.role.dto.CreateCustomRoleRequest;
import com.devflow.role.dto.CustomRoleResponse;
import com.devflow.role.dto.UpdateCustomRoleRequest;
import com.devflow.role.service.CustomRoleService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST controller for managing organization-scoped custom roles (Stage 7).
 *
 * <p>Base path: {@code /api/v1/organizations/{organizationId}/custom-roles}
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/custom-roles")
@Tag(name = "Custom Roles", description = "Endpoints for defining, updating, and managing organization custom roles")
public class CustomRoleController {

    private final CustomRoleService customRoleService;

    public CustomRoleController(CustomRoleService customRoleService) {
        this.customRoleService = Objects.requireNonNull(customRoleService, "customRoleService must not be null");
    }

    /**
     * Lists custom roles for the specified organization tenant.
     */
    @GetMapping
    @Operation(summary = "List custom roles",
            description = "Lists custom roles defined in the organization. Requires OWNER or ADMIN role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Custom roles retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires OWNER or ADMIN role"),
            @ApiResponse(responseCode = "404", description = "Organization not found")
    })
    public ResponseEntity<List<CustomRoleResponse>> listCustomRoles(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) Boolean activeOnly,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        List<CustomRoleEntity> roles = customRoleService.listCustomRoles(organizationId, userDetails.getId(), activeOnly);
        List<CustomRoleResponse> response = roles.stream()
                .map(CustomRoleResponse::from)
                .toList();

        return ResponseEntity.ok(response);
    }

    /**
     * Creates a new organization-scoped custom role.
     */
    @PostMapping
    @Operation(summary = "Create custom role",
            description = "Defines a new custom role with specified canonical permissions. Requires OWNER or ADMIN role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Custom role created successfully",
                    content = @Content(schema = @Schema(implementation = CustomRoleResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid role parameters, unknown permissions, or platform permissions"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — caller lacks permission to delegate requested permissions"),
            @ApiResponse(responseCode = "404", description = "Organization not found"),
            @ApiResponse(responseCode = "409", description = "Custom role with this name already exists in the organization")
    })
    public ResponseEntity<CustomRoleResponse> createCustomRole(
            @PathVariable UUID organizationId,
            @Valid @RequestBody CreateCustomRoleRequest request,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        CustomRoleEntity created = customRoleService.createCustomRole(
                organizationId,
                userDetails.getId(),
                request
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(CustomRoleResponse.from(created));
    }

    /**
     * Retrieves an existing custom role by ID.
     */
    @GetMapping("/{roleId}")
    @Operation(summary = "Get custom role details",
            description = "Retrieves a custom role definition by ID within the organization tenant. Requires OWNER or ADMIN role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Custom role details retrieved successfully",
                    content = @Content(schema = @Schema(implementation = CustomRoleResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden"),
            @ApiResponse(responseCode = "404", description = "Custom role not found in organization")
    })
    public ResponseEntity<CustomRoleResponse> getCustomRole(
            @PathVariable UUID organizationId,
            @PathVariable UUID roleId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        CustomRoleEntity role = customRoleService.getCustomRole(organizationId, roleId, userDetails.getId());
        return ResponseEntity.ok(CustomRoleResponse.from(role));
    }

    /**
     * Updates an existing custom role definition.
     */
    @PutMapping("/{roleId}")
    @Operation(summary = "Update custom role",
            description = "Updates display name, description, active status, or permissions. Requires OWNER or ADMIN role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Custom role updated successfully",
                    content = @Content(schema = @Schema(implementation = CustomRoleResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid role parameters, unknown permissions, or composite violation"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — caller lacks permission to delegate updated permissions"),
            @ApiResponse(responseCode = "404", description = "Custom role not found in organization")
    })
    public ResponseEntity<CustomRoleResponse> updateCustomRole(
            @PathVariable UUID organizationId,
            @PathVariable UUID roleId,
            @Valid @RequestBody UpdateCustomRoleRequest request,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        CustomRoleEntity updated = customRoleService.updateCustomRole(
                organizationId,
                roleId,
                userDetails.getId(),
                request
        );

        return ResponseEntity.ok(CustomRoleResponse.from(updated));
    }

    /**
     * Deletes or safely deactivates a custom role.
     */
    @DeleteMapping("/{roleId}")
    @Operation(summary = "Delete or deactivate custom role",
            description = "Deletes a custom role if unused, or safely deactivates it if historical assignments exist. Fails if active assignments depend on it.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Custom role deleted or deactivated successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — insufficient permissions"),
            @ApiResponse(responseCode = "404", description = "Custom role not found in organization"),
            @ApiResponse(responseCode = "409", description = "Conflict — active assignments still depend on this role")
    })
    public ResponseEntity<Void> deleteCustomRole(
            @PathVariable UUID organizationId,
            @PathVariable UUID roleId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        customRoleService.deleteCustomRole(organizationId, roleId, userDetails.getId());
        return ResponseEntity.noContent().build();
    }
}
