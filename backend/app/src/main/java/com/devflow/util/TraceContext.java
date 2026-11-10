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

    private TraceContext() {
    }

    /**
     * Resolves the current trace correlation ID from MDC, request attributes, or request headers.
     *
     * @return correlation ID or null if unresolvable
     */
    public static String getCorrelationId() {
        String mdcCid = MDC.get(CORRELATION_ID_KEY);
        if (mdcCid != null && !mdcCid.isBlank()) {
            return mdcCid;
        }

        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttrs) {
                Object reqAttr = servletAttrs.getRequest().getAttribute(CORRELATION_ID_KEY);
                if (reqAttr instanceof String str && !str.isBlank()) {
                    return str;
                }
                String header = servletAttrs.getRequest().getHeader(REQUEST_ID_HEADER);
                if (header != null && !header.isBlank()) {
                    return header;
                }
            }
        } catch (Exception ignored) {
            // Out of request thread context
        }

        return null;
    }

    /**
     * Generates or ensures a correlation ID is present in MDC.
     */
    public static String ensureCorrelationId() {
        String cid = getCorrelationId();
        if (cid == null || cid.isBlank()) {
            cid = UUID.randomUUID().toString();
            MDC.put(CORRELATION_ID_KEY, cid);
        }
        return cid;
    }
}
