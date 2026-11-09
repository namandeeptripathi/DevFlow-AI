package com.devflow.role.domain;

import com.devflow.organization.domain.Organization;
import com.devflow.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * Represents an organization-scoped custom role assignment granting an organization member
 * specific privileges defined in a {@link CustomRoleEntity} (Stage 7).
 *
 * <p>Persisted in the {@code custom_role_assignments} table.
 *
 * <h2>Design & Security Invariants</h2>
 * <ul>
 *   <li>Tenant isolation: Constrained to a specific {@link Organization} and {@link User}.</li>
 *   <li>Target membership requirement: Target user must be an active member of the organization.</li>
 *   <li>Active uniqueness: Partial unique index prevents duplicate active assignments for the
 *       same {@code (organization_id, user_id, custom_role_id)}.</li>
 *   <li>Audit trail: Captures {@code assignedBy}, {@code assignedAt}, and if revoked,
 *       {@code revokedBy}, {@code revokedAt}, and {@code revocationReason}.</li>
 *   <li>Lifecycle support: Expired or revoked assignments are immediately excluded from active evaluations.</li>
 * </ul>
 *
 * @see Organization
 * @see User
 * @see CustomRoleEntity
 */
@Entity
@Table(
    name = "custom_role_assignments",
    indexes = {
        @Index(name = "idx_custom_role_assignments_org_id", columnList = "organization_id"),
        @Index(name = "idx_custom_role_assignments_user_id", columnList = "user_id"),
        @Index(name = "idx_custom_role_assignments_role_id", columnList = "custom_role_id"),
        @Index(name = "idx_custom_role_assignments_org_user", columnList = "organization_id, user_id")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class CustomRoleAssignment implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

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

    @NotNull(message = "Custom role is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "custom_role_id", nullable = false)
    @ToString.Exclude
    private CustomRoleEntity customRole;

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
    @Column(name = "revocation_reason")
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
     * Checks whether this assignment has been revoked.
     */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /**
     * Checks whether this assignment is currently active (unrevoked and unexpired).
     */
    public boolean isActive() {
        return isActive(Instant.now());
    }

    /**
     * Checks whether this assignment is active at a specific reference timestamp.
     */
    public boolean isActive(Instant referenceTime) {
        if (revokedAt != null) {
            return false;
        }
        return expiresAt == null || expiresAt.isAfter(referenceTime);
    }

    /**
     * Revokes this assignment with the specified actor, reason, and timestamp.
     */
    public void revoke(User revokedByUser, String reason, Instant now) {
        this.revokedBy = Objects.requireNonNull(revokedByUser, "revokedByUser must not be null");
        this.revokedAt = Objects.requireNonNull(now, "revocation timestamp must not be null");
        this.revocationReason = (reason != null && !reason.isBlank()) ? reason.trim() : "REVOKED";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CustomRoleAssignment that = (CustomRoleAssignment) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
