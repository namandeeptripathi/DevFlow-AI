package com.devflow.permission.domain;

import com.devflow.modules.auth.domain.Permission;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
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
 * JPA persistence entity representing a canonical authorization permission in the database catalog.
 *
 * <p>Persisted in the {@code permissions} table. Holds authoritative reference data for the
 * 48 canonical permissions defined in {@code devflow-auth}. Follows the {@code {resource}.{action}}
 * canonical naming grammar.
 *
 * @see Permission
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §6</a>
 */
@Entity
@Table(
    name = "permissions",
    indexes = {
        @Index(name = "idx_permissions_resource", columnList = "resource")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_permissions_name", columnNames = {"name"}),
        @UniqueConstraint(name = "uk_permissions_resource_action", columnNames = {"resource", "action"})
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class PermissionEntity implements Serializable {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank(message = "Permission name is required")
    @Size(max = 100, message = "Permission name cannot exceed 100 characters")
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @NotBlank(message = "Resource domain is required")
    @Size(max = 50, message = "Resource domain cannot exceed 50 characters")
    @Column(name = "resource", nullable = false, length = 50)
    private String resource;

    @NotBlank(message = "Action is required")
    @Size(max = 50, message = "Action cannot exceed 50 characters")
    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Size(max = 500, message = "Permission description cannot exceed 500 characters")
    @Column(name = "description", length = 500)
    private String description;

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
     * Converts this persistent entity to the canonical domain {@link Permission}.
     *
     * @return the canonical domain Permission
     */
    public Permission toDomainPermission() {
        return Permission.of(this.resource, this.action);
    }

    /**
     * Factory method creating a {@link PermissionEntity} from a canonical domain {@link Permission}.
     *
     * @param permission the canonical domain Permission
     * @param id the stable UUID for this canonical permission
     * @param description human-readable description
     * @return the new PermissionEntity
     */
    public static PermissionEntity fromDomainPermission(Permission permission, UUID id, String description) {
        Objects.requireNonNull(permission, "permission must not be null");
        Objects.requireNonNull(id, "id must not be null");
        return PermissionEntity.builder()
                .id(id)
                .name(permission.value())
                .resource(permission.resource())
                .action(permission.action())
                .description(description)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PermissionEntity that = (PermissionEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
