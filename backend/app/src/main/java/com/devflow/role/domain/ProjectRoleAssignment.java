package com.devflow.role.domain;

import com.devflow.modules.auth.domain.RoleScope;
import com.devflow.organization.domain.Organization;
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
import jakarta.persistence.Version;
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
 * Represents a project-scoped role assignment granting a principal project-level authority.
 *
 * <p>Persisted in the {@code project_role_assignments} table. Supports the assignment of canonical
 * project roles: {@code PROJECT_LEAD}, {@code PROJECT_MEMBER}, and {@code PROJECT_VIEWER}.
 *
 * <h2>Design & Security Invariants</h2>
 * <ul>
 *   <li>Tenant isolation: Always linked to both a {@code projectId} and its enclosing {@link Organization}.</li>
 *   <li>Role scope isolation: Database foreign key and check constraints guarantee that only
 *       {@link RoleScope#PROJECT} roles can be assigned. Project roles cannot grant organization-wide permissions.</li>
 *   <li>Active uniqueness: Partial unique index prevents duplicate active assignments for the
 *       same {@code (project_id, user_id, role_id)}.</li>
 *   <li>Membership requirement: Database foreign key to {@code organization_members} guarantees the user
 *       has an established membership in the tenant organization.</li>
 *   <li>Audit trail: Preserves {@code assignedBy}, {@code assignedAt}, {@code revokedBy}, {@code revokedAt},
 *       and {@code revocationReason}.</li>
 *   <li>Lifecycle support: Expired or revoked assignments are immediately excluded from active evaluations.</li>
 * </ul>
 *
 * @see Organization
 * @see User
 * @see RoleEntity
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §5.3, §8.2</a>
 */
@Entity
@Table(
    name = "project_role_assignments",
    indexes = {
        @Index(name = "idx_project_role_assignments_project_id", columnList = "project_id"),
        @Index(name = "idx_project_role_assignments_org_id", columnList = "organization_id"),
        @Index(name = "idx_project_role_assignments_user_id", columnList = "user_id"),
        @Index(name = "idx_project_role_assignments_proj_user", columnList = "project_id, user_id"),
        @Index(name = "idx_project_role_assignments_org_user", columnList = "organization_id, user_id"),
        @Index(name = "idx_project_role_assignments_role_id", columnList = "role_id")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ProjectRoleAssignment implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotNull(message = "Project ID is required")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @NotNull(message = "Organization is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    @ToString.Exclude
    private Organization organization;

    @NotNull(message = "User is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @NotNull(message = "Role is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    @ToString.Exclude
    private RoleEntity role;

    @NotNull(message = "Role scope is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "role_scope", nullable = false, length = 30)
    @Builder.Default
    private RoleScope roleScope = RoleScope.PROJECT;

    @NotNull(message = "Assigned by user is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_by", nullable = false)
    @ToString.Exclude
    private User assignedBy;

    @NotNull(message = "Assigned at timestamp is required")
    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by")
    @ToString.Exclude
    private User revokedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Size(max = 255, message = "Revocation reason cannot exceed 255 characters")
    @Column(name = "revocation_reason", length = 255)
    private String revocationReason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Determines whether this role assignment is currently active.
     *
     * @param evaluationTime the reference instant
     * @return {@code true} if not revoked and not expired; {@code false} otherwise
     */
    public boolean isActive(Instant evaluationTime) {
        Objects.requireNonNull(evaluationTime, "evaluationTime must not be null");
        if (revokedAt != null) {
            return false;
        }
        return expiresAt == null || expiresAt.isAfter(evaluationTime);
    }

    /**
     * Determines whether this role assignment is currently active at system time.
     *
     * @return {@code true} if active; {@code false} otherwise
     */
    public boolean isActive() {
        return isActive(Instant.now());
    }

    /**
     * Indicates whether this assignment has been revoked.
     *
     * @return {@code true} if revoked; {@code false} otherwise
     */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /**
     * Indicates whether this assignment has expired at the given instant.
     *
     * @param evaluationTime the reference instant
     * @return {@code true} if expired; {@code false} otherwise
     */
    public boolean isExpired(Instant evaluationTime) {
        Objects.requireNonNull(evaluationTime, "evaluationTime must not be null");
        return expiresAt != null && !expiresAt.isAfter(evaluationTime);
    }

    /**
     * Indicates whether this assignment has expired at system time.
     *
     * @return {@code true} if expired; {@code false} otherwise
     */
    public boolean isExpired() {
        return isExpired(Instant.now());
    }

    /**
     * Revokes this assignment, recording the actor, reason, and timestamp.
     *
     * @param revokedByUser the user executing the revocation
     * @param reason the business justification
     * @param when the timestamp of revocation (defaults to now if null)
     */
    public void revoke(User revokedByUser, String reason, Instant when) {
        this.revokedBy = revokedByUser;
        this.revocationReason = reason;
        this.revokedAt = (when != null) ? when : Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProjectRoleAssignment that = (ProjectRoleAssignment) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
