package com.devflow.security.monitoring.repository;

import com.devflow.security.monitoring.domain.SecurityAlertEntity;
import com.devflow.security.monitoring.domain.SecurityAlertStatus;
import com.devflow.security.monitoring.domain.SecuritySeverity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for persisted security alerts with lifecycle tracking.
 */
@Repository
public interface SecurityAlertRepository extends
        JpaRepository<SecurityAlertEntity, String>,
        JpaSpecificationExecutor<SecurityAlertEntity> {

    Optional<SecurityAlertEntity> findByOrganizationIdAndId(String organizationId, String id);

    @Query("""
        SELECT a FROM SecurityAlertEntity a
        WHERE a.organizationId = :organizationId
          AND a.ruleId = :ruleId
          AND a.actorId = :actorId
          AND a.status IN ('OPEN', 'ACKNOWLEDGED')
          AND a.lastSeenAt >= :cooldownThreshold
        ORDER BY a.lastSeenAt DESC
    """)
    Optional<SecurityAlertEntity> findActiveAlertForDeduplication(
            @Param("organizationId") String organizationId,
            @Param("ruleId") String ruleId,
            @Param("actorId") String actorId,
            @Param("cooldownThreshold") Instant cooldownThreshold
    );

    /**
     * Queries tenant security alerts with optional filters using Criteria Specifications
     * so nullable parameters are omitted cleanly on PostgreSQL without untyped parameter bindings.
     */
    default Page<SecurityAlertEntity> findTenantSecurityAlerts(
            String organizationId,
            SecurityAlertStatus status,
            SecuritySeverity severity,
            Instant fromTime,
            Instant toTime,
            Pageable pageable
    ) {
        Specification<SecurityAlertEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (severity != null) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            if (fromTime != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("lastSeenAt"), fromTime));
            }
            if (toTime != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("lastSeenAt"), toTime));
            }
            if (pageable.getSort().isUnsorted()
                    && query != null
                    && query.getResultType() != Long.class
                    && query.getResultType() != long.class) {
                query.orderBy(cb.desc(root.get("lastSeenAt")));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return findAll(spec, pageable);
    }
}
