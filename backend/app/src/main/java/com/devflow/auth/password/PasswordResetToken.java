package com.devflow.auth.password;

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
 * JPA Entity representing a database-backed Password Reset Token.
 *
 * <h2>Security & Design Constraints</h2>
 * <ul>
 *   <li>Database-backed (never stateless JWTs) allowing single-use enforcement and instant revocation.</li>
 *   <li>Only SHA-256 hashes are persisted in {@code tokenHash}; plaintext tokens are never stored.</li>
 *   <li>Annotated with {@link JsonIgnore} and {@link ToString.Exclude} to prevent accidental log exposure.</li>
 *   <li>{@link FetchType#LAZY} relationship to {@link User}.</li>
 *   <li>Optimistic concurrency control via {@link Version}.</li>
 * </ul>
 *
 * @see User
 * @see PasswordResetService
 */
@Entity
@Table(
    name = "password_reset_tokens",
    indexes = {
        @Index(name = "idx_password_reset_tokens_token_hash", columnList = "token_hash"),
        @Index(name = "idx_password_reset_tokens_user_id", columnList = "user_id"),
        @Index(name = "idx_password_reset_tokens_expires_at", columnList = "expires_at"),
        @Index(name = "idx_password_reset_tokens_used", columnList = "used")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_password_reset_tokens_token_hash", columnNames = "token_hash")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class PasswordResetToken implements Serializable {

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

    @NotNull(message = "Expiration timestamp is required")
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Builder.Default
    @Column(name = "used", nullable = false)
    private boolean used = false;

    @Column(name = "used_at")
    private Instant usedAt;

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
     * Checks if this token is currently valid (unused and unexpired).
     *
     * @return {@code true} if token has not been used and expiration is in the future
     */
    public boolean isValid() {
        return !used && expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    /**
     * Marks this password reset token as used.
     */
    public void markUsed() {
        this.used = true;
        this.usedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PasswordResetToken token = (PasswordResetToken) o;
        return id != null && Objects.equals(id, token.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
