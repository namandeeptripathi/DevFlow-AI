package com.devflow.role.domain;

import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.domain.RoleScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
import java.util.Objects;
import java.util.UUID;

/**
 * JPA persistence entity representing a canonical system role in the database catalog.
 *
 * <p>Persisted in the {@code roles} table. Holds authoritative reference data for the
 * 10 canonical roles defined in {@code devflow-auth}. Custom role creation is strictly
 * prohibited at this stage; all persisted records must correspond to a canonical {@link Role}.
 *
 * @see Role
 * @see RoleScope
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §5</a>
 */
@Entity
@Table(
    name = "roles",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_roles_name", columnNames = {"name"}),
        @UniqueConstraint(name = "uk_roles_id_scope", columnNames = {"id", "scope"})
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class RoleEntity implements Serializable {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank(message = "Role name is required")
    @Size(max = 50, message = "Role name cannot exceed 50 characters")
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @NotNull(message = "Role scope is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 30)
    private RoleScope scope;

    @Size(max = 500, message = "Role description cannot exceed 500 characters")
    @Column(name = "description", length = 500)
    private String description;

    @Builder.Default
    @Column(name = "is_system", nullable = false)
    private boolean system = true;

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
     * Converts this persistent entity to the canonical domain {@link Role}.
     *
     * @return the canonical domain Role
     * @throws IllegalArgumentException if this entity's name does not match a canonical Role
     */
    public Role toDomainRole() {
        return Role.valueOf(this.name);
    }

    /**
     * Factory method creating a {@link RoleEntity} from a canonical domain {@link Role}.
     *
     * @param role the canonical domain Role
     * @param id the stable UUID for this canonical role
     * @param description human-readable description
     * @return the new RoleEntity
     */
    public static RoleEntity fromDomainRole(Role role, UUID id, String description) {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(id, "id must not be null");
        return RoleEntity.builder()
                .id(id)
                .name(role.name())
                .scope(role.getScope())
                .description(description)
                .system(true)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoleEntity that = (RoleEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
