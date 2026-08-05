package com.devflow.auth.verification;

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
 * Entity representing a database-backed email verification token.
 *
 * <p>Used to verify a user's primary email address post-registration.
 *
 * <h2>Security & Design Constraints</h2>
 * <ul>
 *   <li>Database-backed (never stateless JWTs) for instant revocation capability.</li>
 *   <li>Only SHA-256 hashes are persisted in {@code tokenHash}; plaintext tokens are never stored.</li>
 *   <li>Annotated with {@link JsonIgnore} and {@link ToString.Exclude} to prevent log leaks.</li>
 *   <li>{@link FetchType#LAZY} relationship to {@link User}.</li>
 *   <li>Optimistic concurrency control via {@link Version}.</li>
 * </ul>
 *
 * @see User
 * @see EmailVerificationService
 */
@Entity
@Table(
    name = "email_verification_tokens",
    indexes = {
        @Index(name = "idx_email_verification_tokens_token_hash", columnList = "token_hash"),
        @Index(name = "idx_email_verification_tokens_user_id", columnList = "user_id"),
        @Index(name = "idx_email_verification_tokens_expires_at", columnList = "expires_at"),
        @Index(name = "idx_email_verification_tokens_verified", columnList = "verified")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_email_verification_tokens_token_hash", columnNames = "token_hash")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class EmailVerificationToken implements Serializable {

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
    @Column(name = "verified", nullable = false)
    private boolean verified = false;

    @Column(name = "verified_at")
    private Instant verifiedAt;

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
     * Checks if this token is currently valid (unverified and unexpired).
     *
     * @return {@code true} if token has not been used and expiration is in the future
     */
    public boolean isValid() {
        return !verified && expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    /**
     * Marks this token as verified.
     */
    public void markVerified() {
        this.verified = true;
        this.verifiedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EmailVerificationToken token = (EmailVerificationToken) o;
        return id != null && Objects.equals(id, token.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
