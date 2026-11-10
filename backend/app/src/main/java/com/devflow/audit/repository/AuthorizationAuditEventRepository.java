package com.devflow.audit.repository;

import com.devflow.audit.domain.AuthorizationAuditEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Append-only data access repository for authorization audit records.
 *
 * <p>Strictly excludes update and delete operations in accordance with append-only ledger invariants.
 */
@Repository
public interface AuthorizationAuditEventRepository extends
        JpaRepository<AuthorizationAuditEventEntity, String>,
        JpaSpecificationExecutor<AuthorizationAuditEventEntity> {

    Optional<AuthorizationAuditEventEntity> findByEventId(String eventId);

    Optional<AuthorizationAuditEventEntity> findByOrganizationIdAndEventId(String organizationId, String eventId);

    Optional<AuthorizationAuditEventEntity> findTopByOrganizationIdOrderBySequenceNumberDesc(String organizationId);

    Optional<AuthorizationAuditEventEntity> findTopByOrganizationIdIsNullOrderBySequenceNumberDesc();

    @Query("SELECT MAX(e.sequenceNumber) FROM AuthorizationAuditEventEntity e WHERE (:orgId IS NULL AND e.organizationId IS NULL) OR e.organizationId = :orgId")
    Long findMaxSequenceNumber(@Param("orgId") String orgId);

    List<AuthorizationAuditEventEntity> findByOrganizationIdOrderBySequenceNumberAsc(String organizationId);

    List<AuthorizationAuditEventEntity> findByOrganizationIdAndSequenceNumberBetweenOrderBySequenceNumberAsc(
            String organizationId, Long startSeq, Long endSeq);

    List<AuthorizationAuditEventEntity> findByOrganizationIdAndTimestampBetweenOrderBySequenceNumberAsc(
            String organizationId, Instant from, Instant to);

    @Query("""
        SELECT e FROM AuthorizationAuditEventEntity e
        WHERE e.organizationId = :organizationId
          AND (:eventType IS NULL OR e.eventType = :eventType)
          AND (:userId IS NULL OR e.userId = :userId)
          AND (:targetId IS NULL OR e.targetId = :targetId)
          AND (:decision IS NULL OR e.decision = :decision)
          AND (:fromTime IS NULL OR e.timestamp >= :fromTime)
          AND (:toTime IS NULL OR e.timestamp <= :toTime)
        ORDER BY e.sequenceNumber DESC
    """)
    Page<AuthorizationAuditEventEntity> findTenantAuditLogs(
            @Param("organizationId") String organizationId,
            @Param("eventType") String eventType,
            @Param("userId") String userId,
            @Param("targetId") String targetId,
            @Param("decision") String decision,
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime,
            Pageable pageable
    );

    @Query("""
        SELECT COUNT(e) FROM AuthorizationAuditEventEntity e
        WHERE e.organizationId = :organizationId
          AND e.userId = :userId
          AND e.decision = 'DENY'
          AND e.timestamp >= :since
    """)
    long countRecentDenials(
            @Param("organizationId") String organizationId,
            @Param("userId") String userId,
            @Param("since") Instant since
    );

    @Query("""
        SELECT COUNT(e) FROM AuthorizationAuditEventEntity e
        WHERE e.userId = :userId
          AND e.eventType = 'TENANT_BOUNDARY_VIOLATION'
          AND e.timestamp >= :since
    """)
    long countRecentTenantBoundaryViolations(
            @Param("userId") String userId,
            @Param("since") Instant since
    );

    @Query("""
        SELECT COUNT(e) FROM AuthorizationAuditEventEntity e
        WHERE e.organizationId = :organizationId
          AND e.userId = :userId
          AND (e.eventType = 'PROJECT_ASSIGNMENT_DENIAL' OR e.eventType = 'SCOPE_MISMATCH_DENIAL')
          AND e.timestamp >= :since
    """)
    long countRecentProjectScopeViolations(
            @Param("organizationId") String organizationId,
            @Param("userId") String userId,
            @Param("since") Instant since
    );

    @Query("""
        SELECT COUNT(e) FROM AuthorizationAuditEventEntity e
        WHERE e.organizationId = :organizationId
          AND e.userId = :userId
          AND e.eventType = 'PRIVILEGED_ROLE_GRANT_ATTEMPTED'
          AND e.timestamp >= :since
    """)
    long countRecentPrivilegedRoleGrantAttempts(
            @Param("organizationId") String organizationId,
            @Param("userId") String userId,
            @Param("since") Instant since
    );

    @Query("""
        SELECT COUNT(e) FROM AuthorizationAuditEventEntity e
        WHERE e.organizationId = :organizationId
          AND e.userId = :userId
          AND e.eventType = 'PERMISSION_DELEGATION_VIOLATION_ATTEMPTED'
          AND e.timestamp >= :since
    """)
    long countRecentPermissionDelegationViolations(
            @Param("organizationId") String organizationId,
            @Param("userId") String userId,
            @Param("since") Instant since
    );

    @Query("""
        SELECT COUNT(e) FROM AuthorizationAuditEventEntity e
        WHERE e.organizationId = :organizationId
          AND e.userId = :userId
          AND e.eventType IN ('ROLE_GRANTED', 'ROLE_REVOKED', 'CUSTOM_ROLE_CREATED', 'CUSTOM_ROLE_UPDATED', 'CUSTOM_ROLE_DISABLED', 'CUSTOM_ROLE_DELETED')
          AND e.timestamp >= :since
    """)
    long countRecentAdministrativeModifications(
            @Param("organizationId") String organizationId,
            @Param("userId") String userId,
            @Param("since") Instant since
    );

    @Query("""
        SELECT e.id FROM AuthorizationAuditEventEntity e
        WHERE e.organizationId = :organizationId
          AND e.userId = :userId
          AND (:eventType IS NULL OR e.eventType = :eventType)
          AND e.timestamp >= :since
        ORDER BY e.timestamp DESC
    """)
    List<String> findRecentEvidenceEventIds(
            @Param("organizationId") String organizationId,
            @Param("userId") String userId,
            @Param("eventType") String eventType,
            @Param("since") Instant since,
            Pageable pageable
    );
}
