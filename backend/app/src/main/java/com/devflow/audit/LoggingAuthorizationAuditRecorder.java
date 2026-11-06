package com.devflow.audit;

import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.modules.auth.audit.AuthorizationAuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Production implementation of {@link AuthorizationAuditRecorder} from {@code devflow-auth}.
 *
 * <p>Emits structured logs for all authorization events (ALLOW and DENY) and optionally publishes
 * {@link AuthorizationAuditApplicationEvent} for asynchronous consumption.
 *
 * <p>Maintains an in-memory buffer of recent events for inspection and integration testing.
 *
 * @see AuthorizationAuditRecorder
 * @see AuthorizationAuditEvent
 */
@Component
public class LoggingAuthorizationAuditRecorder implements AuthorizationAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(LoggingAuthorizationAuditRecorder.class);

    private final ApplicationEventPublisher eventPublisher;
    private final List<AuthorizationAuditEvent> recordedEvents = Collections.synchronizedList(new ArrayList<>());

    @Autowired
    public LoggingAuthorizationAuditRecorder(@Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void record(AuthorizationAuditEvent event) {
        if (event == null) {
            return;
        }

        recordedEvents.add(event);

        if (event.decision().allowed()) {
            log.info("AUDIT_AUTHZ_ALLOW: eventId=[{}] type=[{}] user=[{}] scope=[{}] org=[{}] project=[{}] resource=[{}:{}] permissions={}",
                    event.eventId(),
                    event.eventType(),
                    event.userId(),
                    event.scope(),
                    event.organizationId(),
                    event.projectId(),
                    event.resourceType(),
                    event.resourceId(),
                    event.requestedPermissions());
        } else {
            log.warn("AUDIT_AUTHZ_DENY: eventId=[{}] type=[{}] user=[{}] scope=[{}] org=[{}] project=[{}] resource=[{}:{}] permissions={} reason=[{}] message=[{}]",
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
                    event.decision().message());
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
        return Collections.unmodifiableList(new ArrayList<>(recordedEvents));
    }

    /**
     * Clears recorded events buffer (useful for test isolation).
     */
    public void clearEvents() {
        recordedEvents.clear();
    }
}
