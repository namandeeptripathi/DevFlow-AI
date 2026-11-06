package com.devflow.audit;

import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import org.springframework.context.ApplicationEvent;

import java.util.Objects;

/**
 * Spring {@link ApplicationEvent} wrapping an immutable {@link AuthorizationAuditEvent}.
 *
 * <p>Allows internal listeners, metrics collectors, and alerting systems to consume
 * centralized authorization evaluation outcomes asynchronously without coupling to the audit SPI.
 */
public class AuthorizationAuditApplicationEvent extends ApplicationEvent {

    private final AuthorizationAuditEvent auditEvent;

    public AuthorizationAuditApplicationEvent(Object source, AuthorizationAuditEvent auditEvent) {
        super(source);
        this.auditEvent = Objects.requireNonNull(auditEvent, "auditEvent must not be null");
    }

    public AuthorizationAuditEvent getAuditEvent() {
        return auditEvent;
    }
}
