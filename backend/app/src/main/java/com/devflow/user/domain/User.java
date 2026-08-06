package com.devflow.user.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Email;
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
 * Represents an authenticated user identity within the DevFlow platform.
 *
 * <p>Persisted in the {@code users} table. Serves as the principal entity for authentication,
 * credential management, and multi-tenant security context resolution.
 *
 * <h2>Security & Persistence Constraints</h2>
 * <ul>
 *   <li>Primary key is a randomly generated {@link UUID}.</li>
 *   <li>{@code passwordHash} stores exclusively salted/hashed representations (BCrypt);
 *       annotated with {@link JsonIgnore} and {@link ToString.Exclude} to ensure it is never
 *       exposed in REST serialization or application logs.</li>
 *   <li>Optimistic concurrency control is enforced via {@link Version}.</li>
 *   <li>Unique constraints and B-Tree indexes exist on {@code email} and {@code username}.</li>
 *   <li>Audit fields ({@code createdAt}, {@code updatedAt}) are automatically populated by JPA Auditing.</li>
 * </ul>
 *
 * @see AccountStatus
 * @see com.devflow.security.user.DevFlowUserDetails
 * @see <a href="../../../../docs/database/DATABASE_DESIGN.md">Database Architecture Specification §4</a>
 */
@Entity
@Table(
    name = "users",
    indexes = {
        @Index(name = "idx_users_email", columnList = "email"),
        @Index(name = "idx_users_username", columnList = "username")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
        @UniqueConstraint(name = "uk_users_username", columnNames = "username")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class User implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank(message = "Email address is required")
    @Email(message = "Email address must be valid")
    @Size(max = 255, message = "Email address cannot exceed 255 characters")
    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @NotBlank(message = "Password hash is required")
    @Size(max = 255, message = "Password hash cannot exceed 255 characters")
    @Column(name = "password_hash", nullable = false, length = 255)
    @JsonIgnore
    @ToString.Exclude
    private String passwordHash;

    @Builder.Default
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @NotNull(message = "Account status is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 30)
    @Builder.Default
    private AccountStatus accountStatus = AccountStatus.PENDING_VERIFICATION;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /**
     * The public-facing profile for this user.
     *
     * <p>Inverse (non-owning) side of the One-to-One relationship; the foreign key
     * resides in the {@code user_profiles} table. {@link CascadeType#ALL} and
     * {@code orphanRemoval = true} ensure the profile is persisted and removed
     * in lockstep with this {@link User}.
     */
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @ToString.Exclude
    private UserProfile profile;

    /**
     * The personalisation preferences for this user.
     *
     * <p>Inverse (non-owning) side of the One-to-One relationship; the foreign key
     * resides in the {@code user_preferences} table. {@link CascadeType#ALL} and
     * {@code orphanRemoval = true} ensure preferences are persisted and removed
     * in lockstep with this {@link User}.
     */
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @ToString.Exclude
    private UserPreferences preferences;

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
        User user = (User) o;
        return id != null && Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
