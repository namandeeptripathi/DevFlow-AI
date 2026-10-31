package com.devflow.team.domain;

import com.devflow.organization.domain.Organization;
import com.devflow.team.exception.InvalidTeamOperationException;
import com.devflow.workspace.domain.Workspace;
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
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
 * Represents a collaborative engineering team within an operational {@link Workspace}
 * and {@link Organization} (tenant).
 *
 * <p>Persisted in the {@code teams} table.
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Primary key is a randomly generated {@link UUID} stored as {@code VARCHAR(36)}.</li>
 *   <li>Name is required and constrained to 100 characters; unique per workspace (case-insensitively).</li>
 *   <li>{@code workspace} and {@code organization} are lazy-loaded and mandatory.</li>
 *   <li>Composite tenant integrity: the team's organization must match its workspace's organization.</li>
 *   <li>Team leadership is governed strictly through {@link TeamMember} with {@link TeamRole#LEAD};
 *       no separate lead reference is duplicated on this entity.</li>
 *   <li>Optimistic concurrency control is enforced via {@link Version}.</li>
 *   <li>Audit fields ({@code createdAt}, {@code updatedAt}) are automatically populated by
 *       {@link AuditingEntityListener}.</li>
 * </ul>
 *
 * @see Workspace
 * @see Organization
 * @see TeamMember
 * @see TeamStatus
 */
@Entity
@Table(
    name = "teams",
    indexes = {
        @Index(name = "idx_teams_organization_id", columnList = "organization_id")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Team implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank(message = "Team name is required")
    @Size(max = 100, message = "Team name cannot exceed 100 characters")
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @NotNull(message = "Workspace is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    @ToString.Exclude
    private Workspace workspace;

    @NotNull(message = "Organization is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    @ToString.Exclude
    private Organization organization;

    @Size(max = 500, message = "Team description cannot exceed 500 characters")
    @Column(name = "description", length = 500)
    private String description;

    @NotNull(message = "Team status is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private TeamStatus status = TeamStatus.ACTIVE;

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
     * Checks if the team is in an active lifecycle status.
     *
     * @return {@code true} if active; {@code false} otherwise
     */
    public boolean isActive() {
        return this.status == TeamStatus.ACTIVE;
    }

    /**
     * Checks if the team is archived.
     *
     * @return {@code true} if archived; {@code false} otherwise
     */
    public boolean isArchived() {
        return this.status == TeamStatus.ARCHIVED;
    }

    /**
     * Transitions this team to the {@link TeamStatus#ARCHIVED} state.
     *
     * @throws InvalidTeamOperationException if the team is already archived
     */
    public void archive() {
        if (isArchived()) {
            throw new InvalidTeamOperationException("Team [" + id + "] is already archived");
        }
        this.status = TeamStatus.ARCHIVED;
    }

    /**
     * Updates the name of the team.
     *
     * @param newName the updated name
     * @throws InvalidTeamOperationException if the team is archived
     * @throws IllegalArgumentException if the provided name is blank or exceeds 100 characters
     */
    public void rename(String newName) {
        if (isArchived()) {
            throw new InvalidTeamOperationException("Cannot rename archived team [" + id + "]");
        }
        if (newName == null || newName.trim().isEmpty()) {
            throw new IllegalArgumentException("Team name must not be blank");
        }
        String trimmed = newName.trim();
        if (trimmed.length() > 100) {
            throw new IllegalArgumentException("Team name cannot exceed 100 characters");
        }
        this.name = trimmed;
    }

    /**
     * Updates the human-readable description of the team.
     *
     * @param newDescription the updated description
     * @throws InvalidTeamOperationException if the team is archived
     * @throws IllegalArgumentException if the description exceeds 500 characters
     */
    public void updateDescription(String newDescription) {
        if (isArchived()) {
            throw new InvalidTeamOperationException("Cannot update description of archived team [" + id + "]");
        }
        if (newDescription != null && newDescription.length() > 500) {
            throw new IllegalArgumentException("Team description cannot exceed 500 characters");
        }
        this.description = (newDescription != null && !newDescription.trim().isEmpty())
                ? newDescription.trim()
                : null;
    }

    // ── Equality & HashCode ───────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Team that = (Team) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

