package com.devflow.team.domain;

import com.devflow.team.exception.InvalidTeamOperationException;
import com.devflow.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a user's membership and assigned functional role within a {@link Team}.
 *
 * <p>Persisted in the {@code team_members} table. Serves as the single source of truth
 * for team leadership and member assignments.
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Primary key is a randomly generated {@link UUID} stored as {@code VARCHAR(36)}.</li>
 *   <li>Unique constraint on {@code (team_id, user_id)} enforces that a user can only belong once to a team.</li>
 *   <li>At most one {@link TeamRole#LEAD} is permitted per team (enforced by partial unique index in PostgreSQL).</li>
 *   <li>Both {@code team} and {@code user} are lazy-loaded to prevent eager join overhead.</li>
 *   <li>Optimistic concurrency control is enforced via {@link Version}.</li>
 *   <li>Audit fields ({@code createdAt}, {@code updatedAt}) are automatically populated by
 *       {@link AuditingEntityListener}.</li>
 * </ul>
 *
 * @see Team
 * @see User
 * @see TeamRole
 */
@Entity
@Table(
    name = "team_members",
    indexes = {
        @Index(name = "idx_team_members_user_id", columnList = "user_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_team_members_team_user", columnNames = {"team_id", "user_id"})
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class TeamMember implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotNull(message = "Team is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    @ToString.Exclude
    private Team team;

    @NotNull(message = "User is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @NotNull(message = "Team role is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    @Builder.Default
    private TeamRole role = TeamRole.MEMBER;

    @NotNull(message = "Joined at timestamp is required")
    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // ── Domain Methods ────────────────────────────────────────────────────────

    /**
     * Checks if this member is the designated team lead.
     *
     * @return {@code true} if role is {@link TeamRole#LEAD}; {@code false} otherwise
     */
    public boolean isLead() {
        return this.role == TeamRole.LEAD;
    }

    /**
     * Promotes this member to {@link TeamRole#LEAD}.
     *
     * @throws InvalidTeamOperationException if the associated team is archived
     */
    public void promoteToLead() {
        assertTeamNotArchived();
        this.role = TeamRole.LEAD;
    }

    /**
     * Demotes this member to {@link TeamRole#MEMBER}.
     *
     * @throws InvalidTeamOperationException if the associated team is archived
     */
    public void demoteToMember() {
        assertTeamNotArchived();
        this.role = TeamRole.MEMBER;
    }

    /**
     * Changes this member's functional role.
     *
     * @param newRole the new role to assign
     * @throws InvalidTeamOperationException if the associated team is archived
     * @throws IllegalArgumentException if the role is null
     */
    public void changeRole(TeamRole newRole) {
        if (newRole == null) {
            throw new IllegalArgumentException("Team role must not be null");
        }
        assertTeamNotArchived();
        this.role = newRole;
    }

    private void assertTeamNotArchived() {
        if (this.team != null && this.team.isArchived()) {
            throw new InvalidTeamOperationException("Cannot mutate membership for archived team [" + this.team.getId() + "]");
        }
    }

    // ── Equality & HashCode ───────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TeamMember that = (TeamMember) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

