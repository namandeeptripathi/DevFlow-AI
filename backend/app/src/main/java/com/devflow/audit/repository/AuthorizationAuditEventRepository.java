package com.devflow.audit.repository;

import com.devflow.audit.domain.AuthorizationAuditEventEntity;
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
import java.util.Locale;
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

    /**
     * Queries tenant audit log records with optional filters using Criteria Specifications
     * so nullable parameters are omitted cleanly on PostgreSQL without untyped parameter bindings.
     */
    default Page<AuthorizationAuditEventEntity> findTenantAuditLogs(
            String organizationId,
            String eventType,
            String userId,
            String targetId,
            String decision,
            Instant fromTime,
            Instant toTime,
            Pageable pageable
    ) {
        Specification<AuthorizationAuditEventEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (eventType != null && !eventType.isBlank()) {
                predicates.add(cb.equal(root.get("eventType"), eventType.trim()));
            }
            if (userId != null && !userId.isBlank()) {
                predicates.add(cb.equal(root.get("userId"), userId.trim()));
            }
            if (targetId != null && !targetId.isBlank()) {
                predicates.add(cb.equal(root.get("targetId"), targetId.trim()));
            }
            if (decision != null && !decision.isBlank()) {
                predicates.add(cb.equal(root.get("decision"), decision.trim().toUpperCase(Locale.ROOT)));
            }
            if (fromTime != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), fromTime));
            }
            if (toTime != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), toTime));
            }
            if (pageable.getSort().isUnsorted()
                    && query != null
                    && query.getResultType() != Long.class
                    && query.getResultType() != long.class) {
                query.orderBy(cb.desc(root.get("sequenceNumber")));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return findAll(spec, pageable);
    }

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
          AND e.timestamp >= :since
        ORDER BY e.timestamp DESC
    """)
    List<String> findRecentEvidenceEventIdsAnyType(
            @Param("organizationId") String organizationId,
            @Param("userId") String userId,
            @Param("since") Instant since,
            Pageable pageable
    );

    @Query("""
        SELECT e.id FROM AuthorizationAuditEventEntity e
        WHERE e.organizationId = :organizationId
          AND e.userId = :userId
          AND e.eventType = :eventType
          AND e.timestamp >= :since
        ORDER BY e.timestamp DESC
    """)
    List<String> findRecentEvidenceEventIdsByType(
            @Param("organizationId") String organizationId,
            @Param("userId") String userId,
            @Param("eventType") String eventType,
            @Param("since") Instant since,
            Pageable pageable
    );

    /**
     * Fetches recent evidence event IDs for security rules without passing untyped null
     * bind parameters into PostgreSQL JPQL predicates.
     */
    default List<String> findRecentEvidenceEventIds(
            String organizationId,
            String userId,
            String eventType,
            Instant since,
            Pageable pageable
    ) {
        if (eventType == null || eventType.isBlank()) {
            return findRecentEvidenceEventIdsAnyType(organizationId, userId, since, pageable);
        }
        return findRecentEvidenceEventIdsByType(organizationId, userId, eventType, since, pageable);
    }
}
