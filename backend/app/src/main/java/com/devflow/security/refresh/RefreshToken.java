package com.devflow.security.refresh;

import com.devflow.user.domain.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
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
 * Persisted entity representing a cryptographically hashed Refresh Token.
 *
 * <p>Serves the multi-device Refresh Token Rotation strategy defined in
 * Authentication Strategy §6. Access tokens are short-lived (15 minutes);
 * refresh tokens are long-lived (7 days) and tracked per device session.
 *
 * <h2>Security Constraints</h2>
 * <ul>
 *   <li>Only SHA-256 hashed representations are stored in {@code tokenHash};
 *       the raw plaintext token is never persisted in database or logs.</li>
 *   <li>Annotated with {@link JsonIgnore} and {@link ToString.Exclude} to prevent
 *       accidental serialization or logging of the token hash.</li>
 *   <li>{@link FetchType#LAZY} fetching for the associated {@link User} relationship.</li>
 *   <li>Optimistic concurrency control is enforced via {@link Version}.</li>
 * </ul>
 *
 * @see User
 * @see RefreshTokenService
 * @see <a href="../../../../../docs/security/AUTHENTICATION_STRATEGY.md">Authentication Strategy §6</a>
 */
@Entity
@Table(
    name = "refresh_tokens",
    indexes = {
        @Index(name = "idx_refresh_tokens_token_hash", columnList = "token_hash"),
        @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id"),
        @Index(name = "idx_refresh_tokens_expires_at", columnList = "expires_at"),
        @Index(name = "idx_refresh_tokens_revoked", columnList = "revoked")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_refresh_tokens_token_hash", columnNames = "token_hash")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class RefreshToken implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank(message = "Token hash is required")
    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    @JsonIgnore
    @ToString.Exclude
    private String tokenHash;

    @NotNull(message = "Associated user is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(name = "device_id", length = 100)
    private String deviceId;

    @Column(name = "device_name", length = 100)
    private String deviceName;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @NotNull(message = "Expiration timestamp is required")
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Builder.Default
    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private Instant revokedAt;

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
     * Checks whether this refresh token is currently active (unexpired and unrevoked).
     *
     * @return {@code true} if token is not revoked and expiration timestamp is in the future
     */
    public boolean isActive() {
        return !revoked && expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    /**
     * Marks this token as revoked.
     */
    public void revoke() {
        this.revoked = true;
        this.revokedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RefreshToken that = (RefreshToken) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
