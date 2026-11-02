package com.devflow.modules.auth.audit;

import java.util.Objects;

/**
 * Framework-independent delivery contract (SPI) for recording authorization audit events.
 *
 * <p>Receives structured {@link AuthorizationAuditEvent} instances emitted by the authorization engine.
 * Implementations may write to log sinks, message queues, or centralized observability pipelines.
 *
 * @see AuthorizationAuditEvent
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §13</a>
 */
@FunctionalInterface
public interface AuthorizationAuditRecorder {

    /**
     * Records an authorization audit event.
     *
     * @param event the immutable audit event
     */
    void record(AuthorizationAuditEvent event);

    /**
     * Returns a no-op audit recorder that discards all events.
     *
     * @return a no-op recorder
     */
    static AuthorizationAuditRecorder noOp() {
        return event -> Objects.requireNonNull(event, "event must not be null");
    }
}

