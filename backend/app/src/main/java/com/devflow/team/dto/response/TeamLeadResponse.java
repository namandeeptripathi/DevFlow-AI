package com.devflow.team.dto.response;

import com.devflow.team.domain.TeamMember;
import com.devflow.team.domain.TeamRole;
import com.devflow.user.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * Data Transfer Object representing the designated team lead returned by {@code GET /api/v1/teams/{teamId}/lead}
 * and leadership mutation endpoints.
 *
 * @see com.devflow.team.domain.TeamMember
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@Schema(description = "Representation of a designated team lead")
public class TeamLeadResponse {

    @Schema(description = "Unique identifier of the lead membership record", example = "550e8400-e29b-41d4-a716-446655440000", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID id;

    @Schema(description = "UUID of the team", example = "550e8400-e29b-41d4-a716-446655440001", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID teamId;

    @Schema(description = "UUID of the lead user", example = "550e8400-e29b-41d4-a716-446655440002", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID userId;

    @Schema(description = "Functional role within the team (always LEAD)", example = "LEAD", requiredMode = Schema.RequiredMode.REQUIRED)
    private TeamRole role;

    @Schema(description = "Timestamp when the lead assumed team membership", example = "2026-10-31T18:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant joinedAt;

    @Schema(description = "Email of the team lead", example = "lead@devflow.com", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String email;

    @Schema(description = "Username of the team lead", example = "techlead", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String username;

    /**
     * Maps a {@link TeamMember} domain entity holding the {@link TeamRole#LEAD} role to a {@link TeamLeadResponse} DTO.
     *
     * @param lead the lead membership entity to map
     * @return the mapped {@link TeamLeadResponse}, or {@code null} if input is null
     */
    public static TeamLeadResponse from(TeamMember lead) {
        if (lead == null) {
            return null;
        }

        User user = lead.getUser();

        return TeamLeadResponse.builder()
                .id(lead.getId())
                .teamId(lead.getTeam() != null ? lead.getTeam().getId() : null)
                .userId(user != null ? user.getId() : null)
                .role(lead.getRole())
                .joinedAt(lead.getJoinedAt())
                .email(user != null ? user.getEmail() : null)
                .username(user != null ? user.getUsername() : null)
                .build();
    }
}

