package com.devflow.role.domain;

import com.devflow.organization.domain.Organization;
import com.devflow.user.domain.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
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
 * Persistent relational entity establishing authoritative project-to-organization ownership boundaries.
 *
 * <p>Persisted in the {@code project_registrations} table. Enforces:
 * <ul>
 *   <li>Global uniqueness: Database primary key constraint prevents a project from belonging to multiple organizations.</li>
 *   <li>Tenant isolation: Foreign key constraint guarantees association with a valid {@link Organization}.</li>
 *   <li>Audit trail: Preserves the registering actor ({@link User}) and registration timestamp.</li>
 * </ul>
 */
@Entity
@Table(
    name = "project_registrations",
    indexes = {
        @Index(name = "idx_project_registrations_org_id", columnList = "organization_id")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ProjectRegistration implements Serializable {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "project_id", updatable = false, nullable = false, length = 36)
    private UUID projectId;

    @NotNull(message = "Organization is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    @ToString.Exclude
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registered_by")
    @ToString.Exclude
    private User registeredBy;

    @NotNull(message = "Registration timestamp is required")
    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

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
        ProjectRegistration that = (ProjectRegistration) o;
        return Objects.equals(projectId, that.projectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectId);
    }
}
