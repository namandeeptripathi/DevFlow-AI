package com.devflow.security.monitoring.repository;

import com.devflow.security.monitoring.domain.SecurityEventEntity;
import com.devflow.security.monitoring.domain.SecuritySeverity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for persisted security detection events.
 */
@Repository
public interface SecurityEventRepository extends
        JpaRepository<SecurityEventEntity, String>,
        JpaSpecificationExecutor<SecurityEventEntity> {

    Optional<SecurityEventEntity> findByOrganizationIdAndId(String organizationId, String id);

    /**
     * Queries tenant security events with optional filters using Criteria Specifications
     * so nullable parameters are omitted cleanly on PostgreSQL without untyped parameter bindings.
     */
    default Page<SecurityEventEntity> findTenantSecurityEvents(
            String organizationId,
            String ruleId,
            SecuritySeverity severity,
            Instant fromTime,
            Instant toTime,
            Pageable pageable
    ) {
        Specification<SecurityEventEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (ruleId != null && !ruleId.isBlank()) {
                predicates.add(cb.equal(root.get("ruleId"), ruleId.trim()));
            }
            if (severity != null) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            if (fromTime != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("detectedAt"), fromTime));
            }
            if (toTime != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("detectedAt"), toTime));
            }
            if (pageable.getSort().isUnsorted()
                    && query != null
                    && query.getResultType() != Long.class
                    && query.getResultType() != long.class) {
                query.orderBy(cb.desc(root.get("detectedAt")));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return findAll(spec, pageable);
    }
}
