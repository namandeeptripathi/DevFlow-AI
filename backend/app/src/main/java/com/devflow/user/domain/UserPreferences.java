package com.devflow.user.domain;

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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents the personalisation and display preferences of a DevFlow user.
 *
 * <p>Persisted in the {@code user_preferences} table. Maintains a strict One-to-One
 * relationship with the {@link User} identity aggregate. Preferences are intentionally
 * separated from profile and authentication concerns to allow independent evolution.
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
 *   <li>{@code notificationPreferences} is stored as a {@code JSONB} column to allow
 *       flexible, schema-free extension of individual notification channel toggles
 *       without requiring additional migrations per preference key.</li>
 *   <li>All enum fields are persisted as {@code STRING} to remain readable in the
 *       database and safe against enum reordering.</li>
 * </ul>
 *
 * @see User
 * @see Theme
 * @see DateFormat
 * @see TimeFormat
 * @see com.devflow.user.repository.UserPreferencesRepository
 */
@Entity
@Table(
    name = "user_preferences",
    indexes = {
        @Index(name = "idx_user_preferences_user_id", columnList = "user_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_preferences_user_id", columnNames = "user_id")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class UserPreferences implements Serializable {

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

    @Size(max = 100, message = "Timezone cannot exceed 100 characters")
    @Column(name = "timezone", length = 100)
    @Builder.Default
    private String timezone = "UTC";

    @Size(max = 10, message = "Language code cannot exceed 10 characters")
    @Column(name = "language", length = 10)
    @Builder.Default
    private String language = "en";

    @NotNull(message = "Theme preference is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 20)
    @Builder.Default
    private Theme theme = Theme.SYSTEM;

    @NotNull(message = "Date format preference is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "date_format", nullable = false, length = 20)
    @Builder.Default
    private DateFormat dateFormat = DateFormat.ISO;

    @NotNull(message = "Time format preference is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "time_format", nullable = false, length = 20)
    @Builder.Default
    private TimeFormat timeFormat = TimeFormat.TWENTY_FOUR_HOUR;

    /**
     * Flexible JSONB column for per-channel notification toggles.
     *
     * <p>Stored as a PostgreSQL {@code JSONB} blob so that individual notification
     * channels (e.g., {@code emailEnabled}, {@code pushEnabled}) can be added or
     * removed without a schema migration. The structure is a flat {@code Map<String, Object>}
     * at the persistence layer; the service layer is responsible for interpreting keys.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notification_preferences", columnDefinition = "jsonb")
    private Map<String, Object> notificationPreferences;

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
        UserPreferences that = (UserPreferences) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
