package com.devflow.team.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

/**
 * Data Transfer Object representing the {@code PUT /api/v1/teams/{teamId}/lead} request payload.
 *
 * <p>Identifies the existing team member who will assume the active {@code LEAD} role.
 *
 * @see com.devflow.team.domain.TeamMember
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@Schema(description = "Request payload for transferring team leadership")
public class TransferTeamLeadershipRequest {

    @NotNull(message = "User ID is required")
    @Schema(description = "UUID of the existing team member to promote as team lead", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID userId;

    /**
     * Alias getter to retrieve the target member's user ID.
     *
     * @return the target user UUID
     */
    public UUID getTargetUserId() {
        return userId;
    }
}

