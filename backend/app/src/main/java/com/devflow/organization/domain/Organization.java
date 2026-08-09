package com.devflow.organization.domain;

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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
 * Represents an organization (tenant) within the DevFlow platform.
 *
 * <p>Persisted in the {@code organizations} table. Serves as the primary
 * aggregate root for multi-tenant isolation. Every workspace, project,
 * and team is scoped to exactly one organization.
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Primary key is a randomly generated {@link UUID}.</li>
 *   <li>{@code slug} provides a URL-safe, human-readable, globally unique
 *       identifier for API routing and UI navigation.</li>
 *   <li>{@code owner} is the founding user who created the organization;
 *       fetched lazily to avoid unnecessary joins.</li>
 *   <li>Optimistic concurrency control is enforced via {@link Version}.</li>
 *   <li>Audit fields ({@code createdAt}, {@code updatedAt}) are automatically
 *       populated by the global {@link AuditingEntityListener}.</li>
 * </ul>
 *
 * @see User
 * @see com.devflow.organization.repository.OrganizationRepository
 */
@Entity
@Table(
    name = "organizations",
    indexes = {
        @Index(name = "idx_organizations_slug", columnList = "slug"),
        @Index(name = "idx_organizations_owner_id", columnList = "owner_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_organizations_slug", columnNames = "slug")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Organization implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank(message = "Organization name is required")
    @Size(max = 100, message = "Organization name cannot exceed 100 characters")
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @NotBlank(message = "Organization slug is required")
    @Size(max = 120, message = "Organization slug cannot exceed 120 characters")
    @Pattern(
        regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
        message = "Slug must be lowercase alphanumeric with hyphens (e.g., 'my-org')"
    )
    @Column(name = "slug", nullable = false, unique = true, length = 120)
    private String slug;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    @Column(name = "description", length = 500)
    private String description;

    @NotNull(message = "Organization owner is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    @ToString.Exclude
    private User owner;

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
        Organization that = (Organization) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
