package com.devflow.role.domain;

import com.devflow.permission.domain.PermissionEntity;
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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA persistence entity representing a custom role's association with a canonical permission (Stage 7).
 *
 * <p>Persisted in the {@code custom_role_permissions} table.
 *
 * @see CustomRoleEntity
 * @see PermissionEntity
 */
@Entity
@Table(
    name = "custom_role_permissions",
    indexes = {
        @Index(name = "idx_custom_role_perms_role_id", columnList = "custom_role_id"),
        @Index(name = "idx_custom_role_perms_perm_id", columnList = "permission_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_custom_role_permissions_role_perm", columnNames = {"custom_role_id", "permission_id"})
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class CustomRolePermissionEntity implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotNull(message = "Custom role is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "custom_role_id", nullable = false)
    @ToString.Exclude
    private CustomRoleEntity customRole;

    @NotNull(message = "Permission is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permission_id", nullable = false)
    @ToString.Exclude
    private PermissionEntity permission;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CustomRolePermissionEntity that = (CustomRolePermissionEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
