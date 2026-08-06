package com.devflow.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
 * Represents the public-facing profile of a DevFlow user.
 *
 * <p>Persisted in the {@code user_profiles} table. Maintains a strict One-to-One
 * relationship with the {@link User} identity aggregate. Profile data is intentionally
 * separated from authentication data to uphold the Single Responsibility Principle
 * and to allow independent evolution of identity and profile concerns.
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>The owning side of the One-to-One relationship; the foreign key {@code user_id}
 *       resides in this table.</li>
 *   <li>{@link FetchType#LAZY} association to {@link User} to avoid N+1 issues.</li>
 *   <li>Optimistic concurrency control via {@link Version}.</li>
 *   <li>Audit fields ({@code createdAt}, {@code updatedAt}) are automatically populated
 *       by the global {@link AuditingEntityListener} configured in
 *       {@code JpaAuditingConfiguration}.</li>
 *   <li>{@code avatarUrl} stores the fully-qualified URL to the avatar asset; it does not
 *       hold binary data.</li>
 * </ul>
 *
 * @see User
 * @see com.devflow.user.repository.UserProfileRepository
 */
@Entity
@Table(
    name = "user_profiles",
    indexes = {
        @Index(name = "idx_user_profiles_user_id", columnList = "user_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_profiles_user_id", columnNames = "user_id")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class UserProfile implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotNull(message = "Associated user is required")
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @ToString.Exclude
    private User user;

    @Size(max = 100, message = "Display name cannot exceed 100 characters")
    @Column(name = "display_name", length = 100)
    private String displayName;

    @Size(max = 100, message = "First name cannot exceed 100 characters")
    @Column(name = "first_name", length = 100)
    private String firstName;

    @Size(max = 100, message = "Last name cannot exceed 100 characters")
    @Column(name = "last_name", length = 100)
    private String lastName;

    @Size(max = 1000, message = "Bio cannot exceed 1000 characters")
    @Column(name = "bio", length = 1000)
    private String bio;

    @Size(max = 2048, message = "Avatar URL cannot exceed 2048 characters")
    @Column(name = "avatar_url", length = 2048)
    private String avatarUrl;

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
        UserProfile that = (UserProfile) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
