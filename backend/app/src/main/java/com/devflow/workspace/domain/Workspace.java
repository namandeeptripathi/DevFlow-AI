package com.devflow.workspace.domain;

import com.devflow.organization.domain.Organization;
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
import jakarta.persistence.ManyToOne;
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
 * Represents an operational workspace (e.g., Engineering, Design, Product, HR)
 * within an organization (tenant).
 *
 * <p>Persisted in the {@code workspaces} table. Belongs to exactly one {@link Organization}.
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Primary key is a randomly generated {@link UUID} stored as {@code VARCHAR(36)}.</li>
 *   <li>Name is required and constrained to 100 characters; unique per organization.</li>
 *   <li>{@link WorkspaceVisibility} defaults to {@link WorkspaceVisibility#PUBLIC}.</li>
 *   <li>{@code organization} is lazy-loaded and excluded from {@link #toString()}.</li>
 *   <li>Optimistic concurrency control is enforced via {@link Version}.</li>
 *   <li>Audit fields ({@code createdAt}, {@code updatedAt}) are automatically populated by
 *       {@link AuditingEntityListener}.</li>
 * </ul>
 *
 * @see Organization
 * @see WorkspaceVisibility
 */
@Entity
@Table(
    name = "workspaces",
    indexes = {
        @Index(name = "idx_workspaces_organization_id", columnList = "organization_id"),
        @Index(name = "idx_workspaces_org_name", columnList = "organization_id, name")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_workspaces_org_name", columnNames = {"organization_id", "name"})
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Workspace implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank(message = "Workspace name is required")
    @Size(max = 100, message = "Workspace name cannot exceed 100 characters")
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @NotNull(message = "Organization is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    @ToString.Exclude
    private Organization organization;

    @NotNull(message = "Workspace visibility is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 30)
    @Builder.Default
    private WorkspaceVisibility visibility = WorkspaceVisibility.PUBLIC;

    @Size(max = 500, message = "Workspace description cannot exceed 500 characters")
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Workspace that = (Workspace) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
