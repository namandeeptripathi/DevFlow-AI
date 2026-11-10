package com.devflow.security.monitoring.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for resolving a security alert with an explicit investigation rationale.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolveSecurityAlertRequest {

    @NotBlank(message = "resolutionReason must not be blank")
    @Size(max = 500, message = "resolutionReason must not exceed 500 characters")
    private String resolutionReason;
}
