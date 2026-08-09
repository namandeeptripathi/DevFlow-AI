package com.devflow.organization.domain;

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
 * Represents a user's membership within an organization (tenant).
 *
 * <p>Persisted in the {@code organization_members} table. Manages the link between
 * {@link Organization} and {@link User}, capturing their assigned {@link OrganizationRole}
 * and {@link OrganizationMembershipStatus}.
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Primary key is a randomly generated {@link UUID}.</li>
 *   <li>Unique constraint on {@code (organization_id, user_id)} enforces that a user can only
 *       have one membership record per organization.</li>
 *   <li>Both {@code organization} and {@code user} are lazy-loaded to prevent unwanted join fetches.</li>
 *   <li>Optimistic concurrency control is enforced via {@link Version}.</li>
 *   <li>Audit fields ({@code createdAt}, {@code updatedAt}) are automatically populated by
 *       {@link AuditingEntityListener}.</li>
 * </ul>
 *
 * @see Organization
 * @see User
 * @see OrganizationRole
 * @see OrganizationMembershipStatus
 */
@Entity
@Table(
    name = "organization_members",
    indexes = {
        @Index(name = "idx_organization_members_org_id", columnList = "organization_id"),
        @Index(name = "idx_organization_members_user_id", columnList = "user_id"),
        @Index(name = "idx_organization_members_org_user", columnList = "organization_id, user_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_organization_members_org_user", columnNames = {"organization_id", "user_id"})
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class OrganizationMember implements Serializable {

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

    @NotNull(message = "Organization role is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private OrganizationRole role;

    @NotNull(message = "Membership status is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private OrganizationMembershipStatus status = OrganizationMembershipStatus.ACTIVE;

    @Column(name = "joined_at")
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OrganizationMember that = (OrganizationMember) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
