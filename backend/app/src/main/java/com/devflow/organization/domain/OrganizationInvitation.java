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
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
 * Represents an invitation extended to a user to join an organization (tenant).
 *
 * <p>Persisted in the {@code organization_invitations} table. Captures the recipient's
 * target {@link #email}, assigned {@link OrganizationRole}, secure validation {@link #token},
 * and lifecycle {@link OrganizationInvitationStatus}.
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Primary key is a randomly generated {@link UUID}.</li>
 *   <li>Unique constraint on {@code token} ensures secure, unguessable token validation.</li>
 *   <li>Both {@code organization} and {@code invitedBy} are lazy-loaded.</li>
 *   <li>Optimistic concurrency control is enforced via {@link Version}.</li>
 *   <li>Audit fields ({@code createdAt}, {@code updatedAt}) are automatically populated by
 *       {@link AuditingEntityListener}.</li>
 * </ul>
 *
 * @see Organization
 * @see User
 * @see OrganizationRole
 * @see OrganizationInvitationStatus
 */
@Entity
@Table(
    name = "organization_invitations",
    indexes = {
        @Index(name = "idx_organization_invitations_org_id", columnList = "organization_id"),
        @Index(name = "idx_organization_invitations_email", columnList = "email"),
        @Index(name = "idx_organization_invitations_token", columnList = "token"),
        @Index(name = "idx_organization_invitations_status", columnList = "status")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_organization_invitations_token", columnNames = {"token"})
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class OrganizationInvitation implements Serializable {

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

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    @Column(name = "email", nullable = false)
    private String email;

    @NotNull(message = "Role is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private OrganizationRole role;

    @NotBlank(message = "Token is required")
    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    @NotNull(message = "Invited by user is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by_user_id", nullable = false)
    @ToString.Exclude
    private User invitedBy;

    @NotNull(message = "Invitation status is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private OrganizationInvitationStatus status = OrganizationInvitationStatus.PENDING;

    @NotNull(message = "Expiration timestamp is required")
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

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
        OrganizationInvitation that = (OrganizationInvitation) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
