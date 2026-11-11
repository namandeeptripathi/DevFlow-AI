package com.devflow.audit;

import com.devflow.audit.service.AuthorizationAuditService;
import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.modules.auth.audit.AuthorizationAuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Production implementation of {@link AuthorizationAuditRecorder} from {@code devflow-auth}.
 *
 * <p>Emits structured logs for all authorization events (ALLOW and DENY), persists audit events
 * into the tamper-evident PostgreSQL ledger via {@link AuthorizationAuditService}, and publishes
 * {@link AuthorizationAuditApplicationEvent} for asynchronous security monitoring and detection consumption.
 *
 * <p>Maintains an in-memory buffer of recent events for inspection and integration testing.
 *
 * @see AuthorizationAuditRecorder
 * @see AuthorizationAuditEvent
 * @see AuthorizationAuditService
 */
@Component
public class LoggingAuthorizationAuditRecorder implements AuthorizationAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(LoggingAuthorizationAuditRecorder.class);
    private static final int DEFAULT_IN_MEMORY_BUFFER_SIZE = 1000;

    private final ApplicationEventPublisher eventPublisher;
    private final AuthorizationAuditService auditService;
    private final Deque<AuthorizationAuditEvent> recordedEvents = new ArrayDeque<>();
    private final Object bufferLock = new Object();
    private volatile int maxBufferSize = DEFAULT_IN_MEMORY_BUFFER_SIZE;

    public LoggingAuthorizationAuditRecorder(@Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this(eventPublisher, null);
    }

    @Autowired
    public LoggingAuthorizationAuditRecorder(
            @Autowired(required = false) ApplicationEventPublisher eventPublisher,
            @Autowired(required = false) AuthorizationAuditService auditService
    ) {
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Value("${devflow.security.audit.in-memory-buffer-size:1000}")
    public void setMaxBufferSize(int maxBufferSize) {
        this.maxBufferSize = Math.max(0, maxBufferSize);
    }

    @Override
    public void record(AuthorizationAuditEvent event) {
        if (event == null) {
            return;
        }

        int cap = this.maxBufferSize;
        if (cap > 0) {
            synchronized (bufferLock) {
                while (recordedEvents.size() >= cap) {
                    recordedEvents.removeFirst();
                }
                recordedEvents.addLast(event);
            }
        }

        if (auditService != null) {
            try {
                auditService.persistAuditEvent(event);
            } catch (IllegalStateException e) {
                // Administrative operations must not succeed if durable audit fails
                throw e;
            } catch (Exception e) {
                log.warn("Failed to persist audit event [{}]: {}", event.eventId(), e.getMessage());
            }
        }

        if (event.decision().allowed()) {
            log.info("AUDIT_AUTHZ_ALLOW: eventId=[{}] type=[{}] user=[{}] scope=[{}] org=[{}] project=[{}] resource=[{}:{}] permissions={} target=[{}:{}]",
                    event.eventId(),
                    event.eventType(),
                    event.userId(),
                    event.scope(),
                    event.organizationId(),
                    event.projectId(),
                    event.resourceType(),
                    event.resourceId(),
                    event.requestedPermissions(),
                    event.targetType(),
                    event.targetId());
        } else {
            log.warn("AUDIT_AUTHZ_DENY: eventId=[{}] type=[{}] user=[{}] scope=[{}] org=[{}] project=[{}] resource=[{}:{}] permissions={} reason=[{}] message=[{}] target=[{}:{}]",
                    event.eventId(),
                    event.eventType(),
                    event.userId(),
                    event.scope(),
                    event.organizationId(),
                    event.projectId(),
                    event.resourceType(),
                    event.resourceId(),
                    event.requestedPermissions(),
                    event.denialReason(),
                    event.decision().message(),
                    event.targetType(),
                    event.targetId());
        }

        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(new AuthorizationAuditApplicationEvent(this, event));
            } catch (Exception e) {
                log.warn("Failed to publish authorization audit application event: {}", e.getMessage());
            }
        }
    }

    /**
     * Gets an unmodifiable snapshot of all recorded audit events.
     *
     * @return unmodifiable list of recorded events
     */
    public List<AuthorizationAuditEvent> getRecordedEvents() {
        synchronized (bufferLock) {
            return Collections.unmodifiableList(new ArrayList<>(recordedEvents));
        }
    }

    /**
     * Clears recorded events buffer (useful for test isolation).
     */
    public void clearEvents() {
        synchronized (bufferLock) {
            recordedEvents.clear();
        }
    }
}
