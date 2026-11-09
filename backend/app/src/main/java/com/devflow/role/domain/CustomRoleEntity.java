package com.devflow.role.domain;

import com.devflow.organization.domain.Organization;
import com.devflow.user.domain.User;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
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
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * JPA persistence entity representing an organization-scoped custom role (Stage 7).
 *
 * <p>Persisted in the {@code custom_roles} table. Associated with exactly one organization tenant.
 * Contains a set of canonical permissions mapped via {@link CustomRolePermissionEntity}.
 *
 * <h2>Design & Security Invariants</h2>
 * <ul>
 *   <li>Tenant isolation: Always scoped to exactly one {@link Organization}.</li>
 *   <li>Unique role name: Unique per organization via {@code (organization_id, name)}.</li>
 *   <li>Active/disabled state: Disabled roles cannot be assigned and do not grant permissions.</li>
 *   <li>Audit preservation: Retains {@code createdBy}, {@code createdAt}, {@code updatedAt}.</li>
 *   <li>Optimistic concurrency: Protected by {@code @Version} column.</li>
 * </ul>
 *
 * @see Organization
 * @see User
 * @see CustomRolePermissionEntity
 * @see CustomRoleAssignment
 */
@Entity
@Table(
    name = "custom_roles",
    indexes = {
        @Index(name = "idx_custom_roles_org_id", columnList = "organization_id"),
        @Index(name = "idx_custom_roles_active", columnList = "organization_id, is_active")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_custom_roles_org_name", columnNames = {"organization_id", "name"})
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class CustomRoleEntity implements Serializable {

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

    @NotBlank(message = "Role name is required")
    @Size(max = 50, message = "Role name cannot exceed 50 characters")
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @NotBlank(message = "Display name is required")
    @Size(max = 100, message = "Display name cannot exceed 100 characters")
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Size(max = 500, message = "Role description cannot exceed 500 characters")
    @Column(name = "description", length = 500)
    private String description;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Builder.Default
    @OneToMany(mappedBy = "customRole", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @ToString.Exclude
    private Set<CustomRolePermissionEntity> rolePermissions = new HashSet<>();

    @NotNull(message = "Created by user is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    @ToString.Exclude
    private User createdBy;

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
        CustomRoleEntity that = (CustomRoleEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
