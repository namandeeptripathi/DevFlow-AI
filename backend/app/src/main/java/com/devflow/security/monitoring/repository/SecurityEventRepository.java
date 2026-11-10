package com.devflow.security.monitoring.repository;

import com.devflow.security.monitoring.domain.SecurityEventEntity;
import com.devflow.security.monitoring.domain.SecuritySeverity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for persisted security detection events.
 */
@Repository
public interface SecurityEventRepository extends
        JpaRepository<SecurityEventEntity, String>,
        JpaSpecificationExecutor<SecurityEventEntity> {

    Optional<SecurityEventEntity> findByOrganizationIdAndId(String organizationId, String id);

    @Query("""
        SELECT e FROM SecurityEventEntity e
        WHERE e.organizationId = :organizationId
          AND (:ruleId IS NULL OR e.ruleId = :ruleId)
          AND (:severity IS NULL OR e.severity = :severity)
          AND (:fromTime IS NULL OR e.detectedAt >= :fromTime)
          AND (:toTime IS NULL OR e.detectedAt <= :toTime)
        ORDER BY e.detectedAt DESC
    """)
    Page<SecurityEventEntity> findTenantSecurityEvents(
            @Param("organizationId") String organizationId,
            @Param("ruleId") String ruleId,
            @Param("severity") SecuritySeverity severity,
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime,
            Pageable pageable
    );
}
