package com.devflow.audit.service;

import com.devflow.audit.domain.AuthorizationAuditEventEntity;
import com.devflow.audit.dto.AuditLogResponse;
import com.devflow.audit.repository.AuthorizationAuditEventRepository;
import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.modules.auth.audit.AuthorizationAuditEventType;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.security.monitoring.exception.AuditLogNotFoundException;
import com.devflow.util.TraceContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service orchestrating the persistence, retrieval, and cryptographic linking of authorization
 * and security administrative audit events.
 *
 * <p>Enforces append-only invariants, monotonic sequence numbering, and fail-safe handling:
 * <ul>
 *   <li>Administrative events (e.g. role grants, revocations, custom role mutations) require durable persistence.</li>
 *   <li>Authorization evaluation decisions fail safely without altering access outcomes.</li>
 * </ul>
 */
@Service
public class AuthorizationAuditService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationAuditService.class);

    private static final Set<AuthorizationAuditEventType> ADMINISTRATIVE_EVENT_TYPES = Set.of(
            AuthorizationAuditEventType.ROLE_GRANTED,
            AuthorizationAuditEventType.ROLE_REVOKED,
            AuthorizationAuditEventType.ROLE_ASSIGNMENT_EXPIRED,
            AuthorizationAuditEventType.ROLE_ASSIGNMENT_SUPERSEDED,
            AuthorizationAuditEventType.ROLE_ASSIGNMENT_REJECTED,
            AuthorizationAuditEventType.CUSTOM_ROLE_CREATED,
            AuthorizationAuditEventType.CUSTOM_ROLE_UPDATED,
            AuthorizationAuditEventType.CUSTOM_ROLE_DISABLED,
            AuthorizationAuditEventType.CUSTOM_ROLE_DELETED,
            AuthorizationAuditEventType.CUSTOM_ROLE_PERMISSION_ADDED,
            AuthorizationAuditEventType.CUSTOM_ROLE_PERMISSION_REMOVED,
            AuthorizationAuditEventType.MEMBERSHIP_ROLE_CHANGED,
            AuthorizationAuditEventType.MEMBERSHIP_REMOVED,
            AuthorizationAuditEventType.PRIVILEGED_ROLE_GRANT_ATTEMPTED,
            AuthorizationAuditEventType.PERMISSION_DELEGATION_VIOLATION_ATTEMPTED,
            AuthorizationAuditEventType.SECURITY_ALERT_ACKNOWLEDGED,
            AuthorizationAuditEventType.SECURITY_ALERT_RESOLVED,
            AuthorizationAuditEventType.AUDIT_INTEGRITY_VERIFIED
    );

    private final AuthorizationAuditEventRepository auditEventRepository;
    private final AuditIntegrityService auditIntegrityService;
    private final Map<String, Object> tenantLocks = new ConcurrentHashMap<>();

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    public AuthorizationAuditService(
            AuthorizationAuditEventRepository auditEventRepository,
            AuditIntegrityService auditIntegrityService
    ) {
        this.auditEventRepository = Objects.requireNonNull(auditEventRepository, "auditEventRepository must not be null");
        this.auditIntegrityService = Objects.requireNonNull(auditIntegrityService, "auditIntegrityService must not be null");
    }

    /**
     * Persists an audit event to the append-only ledger, linking it to the previous hash in the tenant chain.
     *
     * <p>Acquires a transaction-scoped PostgreSQL advisory lock per tenant chain so concurrent
     * transactions serialize sequence number and hash-chain derivation without silent corruption.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuthorizationAuditEventEntity persistAuditEvent(AuthorizationAuditEvent event) {
        if (event == null) {
            return null;
        }

        String orgIdStr = event.getOrganizationId().map(UUID::toString).orElse(null);
        String lockKey = orgIdStr != null ? orgIdStr : "platform_global";

        try {
            if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
                acquireTenantAdvisoryLock("devflow:audit:" + lockKey);
                return persistAuditEventInternal(event, orgIdStr);
            }

            Object lock = tenantLocks.computeIfAbsent(lockKey, k -> new Object());
            synchronized (lock) {
                return persistAuditEventInternal(event, orgIdStr);
            }
        } catch (Exception e) {
            log.error("Failed to persist authorization audit event [{}]: {}", event.eventId(), e.getMessage(), e);

            // If security-sensitive administrative operation fails audit persistence, fail closed
            if (ADMINISTRATIVE_EVENT_TYPES.contains(event.eventType())) {
                throw new IllegalStateException("Security-critical audit recording failed for [" + event.eventType() + "]: " + e.getMessage(), e);
            }
            // Normal authorization evaluation decision logging fails safely
            return null;
        }
    }

    private void acquireTenantAdvisoryLock(String lockName) {
        entityManager.createNativeQuery(
                        "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockName, 0))) AS _lock")
                .setParameter("lockName", lockName)
                .getSingleResult();
    }

    private AuthorizationAuditEventEntity persistAuditEventInternal(AuthorizationAuditEvent event, String orgIdStr) {
        String eventIdStr = event.eventId().toString();

        // Idempotent retry protection: do not append duplicate event_id records
        Optional<AuthorizationAuditEventEntity> existing = auditEventRepository.findByEventId(eventIdStr);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Determine next monotonic sequence number
        Long maxSeq = auditEventRepository.findMaxSequenceNumber(orgIdStr);
        long nextSeq = (maxSeq == null) ? 1L : maxSeq + 1L;

        // Determine previous record hash
        String prevHash;
        if (nextSeq == 1L) {
            prevHash = AuditIntegrityService.GENESIS_HASH;
        } else {
            Optional<AuthorizationAuditEventEntity> prevRecord = (orgIdStr != null)
                    ? auditEventRepository.findTopByOrganizationIdOrderBySequenceNumberDesc(orgIdStr)
                    : auditEventRepository.findTopByOrganizationIdIsNullOrderBySequenceNumberDesc();
            prevHash = prevRecord.map(AuthorizationAuditEventEntity::getRecordHash)
                    .orElse(AuditIntegrityService.GENESIS_HASH);
        }

        // Resolve and sanitize correlation ID so untrusted headers cannot overflow VARCHAR(100)
        String rawCorrelationId = event.getCorrelationId().orElseGet(TraceContext::getCorrelationId);
        String correlationId = TraceContext.sanitize(rawCorrelationId);

        // Format requested permissions as comma-separated canonical string
        String permissionsStr = event.requestedPermissions() != null && !event.requestedPermissions().isEmpty()
                ? event.requestedPermissions().stream().map(Permission::value).sorted().collect(Collectors.joining(","))
                : null;

        String userIdStr = event.userId().toString();
        String scopeStr = event.scope().name();
        String eventTypeStr = clamp(event.eventType().name(), 60);
        String projIdStr = event.getProjectId().map(UUID::toString).orElse(null);
        String resourceTypeStr = clamp(event.getResourceType().orElse(null), 100);
        String resourceIdStr = clamp(event.getResourceId().orElse(null), 100);
        String decisionStr = event.decision().allowed() ? "ALLOW" : "DENY";
        String denialReasonStr = clamp(event.getDenialReason().map(Enum::name).orElse(null), 60);
        String targetIdStr = event.getTargetId().map(UUID::toString).orElse(null);
        String targetTypeStr = clamp(event.getTargetType().orElse(null), 50);
        String prevStateStr = event.getPreviousState().orElse(null);
        String newStateStr = event.getNewState().orElse(null);
        String reasonStr = clamp(event.getReason().orElse(null), 500);

        // Compute deterministic cryptographic SHA-256 hash over persisted field values
        String recordHash = auditIntegrityService.computeRecordHash(
                nextSeq,
                eventIdStr,
                event.timestamp(),
                eventTypeStr,
                userIdStr,
                scopeStr,
                orgIdStr,
                projIdStr,
                resourceTypeStr,
                resourceIdStr,
                decisionStr,
                denialReasonStr,
                targetIdStr,
                targetTypeStr,
                prevStateStr,
                newStateStr,
                reasonStr,
                prevHash
        );

        AuthorizationAuditEventEntity entity = AuthorizationAuditEventEntity.builder()
                .id(UUID.randomUUID().toString())
                .sequenceNumber(nextSeq)
                .eventId(eventIdStr)
                .timestamp(event.timestamp())
                .eventType(eventTypeStr)
                .userId(userIdStr)
                .scope(scopeStr)
                .organizationId(orgIdStr)
                .projectId(projIdStr)
                .resourceType(resourceTypeStr)
                .resourceId(resourceIdStr)
                .requestedPermissions(permissionsStr)
                .decision(decisionStr)
                .denialReason(denialReasonStr)
                .correlationId(correlationId)
                .targetId(targetIdStr)
                .targetType(targetTypeStr)
                .previousState(prevStateStr)
                .newState(newStateStr)
                .reason(reasonStr)
                .recordHash(recordHash)
                .previousRecordHash(prevHash)
                .createdAt(Instant.now())
                .build();

        AuthorizationAuditEventEntity saved = auditEventRepository.save(entity);
        if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
            entityManager.flush();
        }
        return saved;
    }

    private static String clamp(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    /**
     * Searches and paginates audit log records for an organization.
     */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> searchAuditLogs(
            UUID organizationId,
            String eventType,
            UUID userId,
            UUID targetId,
            String decision,
            Instant from,
            Instant to,
            Pageable pageable
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");

        String orgIdStr = organizationId.toString();
        String userIdStr = userId != null ? userId.toString() : null;
        String targetIdStr = targetId != null ? targetId.toString() : null;

        return auditEventRepository.findTenantAuditLogs(
                orgIdStr, eventType, userIdStr, targetIdStr, decision, from, to, pageable)
                .map(AuditLogResponse::fromEntity);
    }

    /**
     * Retrieves an individual audit log record within an organization boundary.
     */
    @Transactional(readOnly = true)
    public AuditLogResponse getAuditLogById(UUID organizationId, String eventId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");

        String orgIdStr = organizationId.toString();
        AuthorizationAuditEventEntity entity = auditEventRepository.findByOrganizationIdAndEventId(orgIdStr, eventId)
                .or(() -> auditEventRepository.findById(eventId).filter(e -> Objects.equals(e.getOrganizationId(), orgIdStr)))
                .orElseThrow(() -> new AuditLogNotFoundException(
                        "Audit event [" + eventId + "] not found in organization [" + organizationId + "]"));

        return AuditLogResponse.fromEntity(entity);
    }
}
