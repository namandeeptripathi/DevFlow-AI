package com.devflow.audit.service;

import com.devflow.audit.domain.AuthorizationAuditEventEntity;
import com.devflow.audit.dto.AuditLogResponse;
import com.devflow.audit.repository.AuthorizationAuditEventRepository;
import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.modules.auth.audit.AuthorizationAuditEventType;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.security.monitoring.exception.AuditLogNotFoundException;
import com.devflow.util.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    public AuthorizationAuditService(
            AuthorizationAuditEventRepository auditEventRepository,
            AuditIntegrityService auditIntegrityService
    ) {
        this.auditEventRepository = Objects.requireNonNull(auditEventRepository, "auditEventRepository must not be null");
        this.auditIntegrityService = Objects.requireNonNull(auditIntegrityService, "auditIntegrityService must not be null");
    }

    /**
     * Persists an audit event to the append-only ledger, linking it to the previous hash in the tenant chain.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuthorizationAuditEventEntity persistAuditEvent(AuthorizationAuditEvent event) {
        if (event == null) {
            return null;
        }

        String orgIdStr = event.getOrganizationId().map(UUID::toString).orElse(null);
        String lockKey = orgIdStr != null ? orgIdStr : "platform_global";
        Object lock = tenantLocks.computeIfAbsent(lockKey, k -> new Object());

        try {
            synchronized (lock) {
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
                    prevHash = prevRecord.map(AuthorizationAuditEventEntity::getRecordHash).orElse(AuditIntegrityService.GENESIS_HASH);
                }

                // Resolve correlation ID
                String correlationId = event.getCorrelationId().orElseGet(TraceContext::getCorrelationId);

                // Format requested permissions as comma-separated canonical string
                String permissionsStr = event.requestedPermissions() != null && !event.requestedPermissions().isEmpty()
                        ? event.requestedPermissions().stream().map(Permission::value).sorted().collect(Collectors.joining(","))
                        : null;

                String eventIdStr = event.eventId().toString();
                String userIdStr = event.userId().toString();
                String scopeStr = event.scope().name();
                String projIdStr = event.getProjectId().map(UUID::toString).orElse(null);
                String resourceTypeStr = event.getResourceType().orElse(null);
                String resourceIdStr = event.getResourceId().orElse(null);
                String decisionStr = event.decision().allowed() ? "ALLOW" : "DENY";
                String denialReasonStr = event.getDenialReason().map(Enum::name).orElse(null);
                String targetIdStr = event.getTargetId().map(UUID::toString).orElse(null);
                String targetTypeStr = event.getTargetType().orElse(null);
                String prevStateStr = event.getPreviousState().orElse(null);
                String newStateStr = event.getNewState().orElse(null);
                String reasonStr = event.getReason().orElse(null);

                // Compute deterministic cryptographic SHA-256 hash
                String recordHash = auditIntegrityService.computeRecordHash(
                        nextSeq,
                        eventIdStr,
                        event.timestamp(),
                        event.eventType().name(),
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
                        .eventType(event.eventType().name())
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

                return auditEventRepository.save(entity);
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

        Specification<AuthorizationAuditEventEntity> spec = (root, query, cb) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            predicates.add(cb.equal(root.get("organizationId"), orgIdStr));
            if (eventType != null && !eventType.isBlank()) {
                predicates.add(cb.equal(root.get("eventType"), eventType.trim()));
            }
            if (userIdStr != null && !userIdStr.isBlank()) {
                predicates.add(cb.equal(root.get("userId"), userIdStr.trim()));
            }
            if (targetIdStr != null && !targetIdStr.isBlank()) {
                predicates.add(cb.equal(root.get("targetId"), targetIdStr.trim()));
            }
            if (decision != null && !decision.isBlank()) {
                predicates.add(cb.equal(root.get("decision"), decision.trim().toUpperCase()));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), to));
            }
            query.orderBy(cb.desc(root.get("sequenceNumber")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return auditEventRepository.findAll(spec, pageable).map(AuditLogResponse::fromEntity);
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
