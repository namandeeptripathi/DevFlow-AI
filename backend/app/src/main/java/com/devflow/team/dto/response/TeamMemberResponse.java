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
 * Data Transfer Object representing a team membership record exposed to API consumers.
 *
 * @see com.devflow.team.domain.TeamMember
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@Schema(description = "Representation of a team membership")
public class TeamMemberResponse {

    @Schema(description = "Unique identifier of the membership record", example = "550e8400-e29b-41d4-a716-446655440000", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID id;

    @Schema(description = "UUID of the team", example = "550e8400-e29b-41d4-a716-446655440001", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID teamId;

    @Schema(description = "UUID of the member user", example = "550e8400-e29b-41d4-a716-446655440002", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID userId;

    @Schema(description = "Functional role within the team (LEAD, MEMBER)", example = "MEMBER", requiredMode = Schema.RequiredMode.REQUIRED)
    private TeamRole role;

    @Schema(description = "Timestamp when the member joined the team", example = "2026-10-31T18:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant joinedAt;

    @Schema(description = "Email of the member", example = "developer@devflow.com", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String email;

    @Schema(description = "Username of the member", example = "developer", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String username;

    /**
     * Maps a {@link TeamMember} domain entity to its corresponding {@link TeamMemberResponse} DTO.
     *
     * @param member the domain entity to map
     * @return the mapped {@link TeamMemberResponse}, or {@code null} if input is null
     */
    public static TeamMemberResponse from(TeamMember member) {
        if (member == null) {
            return null;
        }

        User user = member.getUser();

        return TeamMemberResponse.builder()
                .id(member.getId())
                .teamId(member.getTeam() != null ? member.getTeam().getId() : null)
                .userId(user != null ? user.getId() : null)
                .role(member.getRole())
                .joinedAt(member.getJoinedAt())
                .email(user != null ? user.getEmail() : null)
                .username(user != null ? user.getUsername() : null)
                .build();
    }
}

