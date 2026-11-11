package com.devflow.util;

import org.slf4j.MDC;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * Utility for accessing and propagating request correlation identifiers (X-Request-ID).
 */
public final class TraceContext {

    public static final String CORRELATION_ID_KEY = "correlationId";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final int MAX_CORRELATION_ID_LENGTH = 100;

    private TraceContext() {
    }

    /**
     * Resolves and sanitizes the current trace correlation ID from MDC, request attributes, or request headers.
     *
     * @return sanitized correlation ID (max 100 chars, no control characters) or null if unresolvable
     */
    public static String getCorrelationId() {
        String mdcCid = sanitize(MDC.get(CORRELATION_ID_KEY));
        if (mdcCid != null) {
            return mdcCid;
        }

        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttrs) {
                Object reqAttr = servletAttrs.getRequest().getAttribute(CORRELATION_ID_KEY);
                if (reqAttr instanceof String str) {
                    String sanitized = sanitize(str);
                    if (sanitized != null) {
                        return sanitized;
                    }
                }
                String header = servletAttrs.getRequest().getHeader(REQUEST_ID_HEADER);
                String sanitizedHeader = sanitize(header);
                if (sanitizedHeader != null) {
                    return sanitizedHeader;
                }
            }
        } catch (Exception ignored) {
            // Out of request thread context
        }

        return null;
    }

    /**
     * Sanitizes a caller-supplied or propagated correlation ID to prevent log injection
     * and database column overflow.
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_CORRELATION_ID_LENGTH));
        for (int i = 0; i < raw.length() && sb.length() < MAX_CORRELATION_ID_LENGTH; i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_CORRELATION_ID_LENGTH
                ? cleaned.substring(0, MAX_CORRELATION_ID_LENGTH)
                : cleaned;
    }

    /**
     * Generates or ensures a sanitized correlation ID is present in MDC.
     */
    public static String ensureCorrelationId() {
        String cid = getCorrelationId();
        if (cid == null || cid.isBlank()) {
            cid = UUID.randomUUID().toString();
        }
        MDC.put(CORRELATION_ID_KEY, cid);
        return cid;
    }
}
