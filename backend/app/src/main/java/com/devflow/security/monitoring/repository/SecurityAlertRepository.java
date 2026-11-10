package com.devflow.security.monitoring.repository;

import com.devflow.security.monitoring.domain.SecurityAlertEntity;
import com.devflow.security.monitoring.domain.SecurityAlertStatus;
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

    @Query("""
        SELECT a FROM SecurityAlertEntity a
        WHERE a.organizationId = :organizationId
          AND (:status IS NULL OR a.status = :status)
          AND (:severity IS NULL OR a.severity = :severity)
          AND (:fromTime IS NULL OR a.lastSeenAt >= :fromTime)
          AND (:toTime IS NULL OR a.lastSeenAt <= :toTime)
        ORDER BY a.lastSeenAt DESC
    """)
    Page<SecurityAlertEntity> findTenantSecurityAlerts(
            @Param("organizationId") String organizationId,
            @Param("status") SecurityAlertStatus status,
            @Param("severity") SecuritySeverity severity,
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime,
            Pageable pageable
    );
}
