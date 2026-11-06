package com.devflow.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Optional request payload for revoking a role assignment with an explicit business reason (Stage 6).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Optional payload to supply a business reason for revoking a role assignment")
public class RevokeRoleRequest {

    @Schema(description = "Business justification or audit reason for revocation", example = "Project reassignment")
    private String reason;
}

