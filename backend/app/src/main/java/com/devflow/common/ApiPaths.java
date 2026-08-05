package com.devflow.common;

/**
 * Canonical API path constants for the DevFlow backend.
 *
 * <p>Centralizing path strings here eliminates magic-string duplication between
 * {@link com.devflow.security.SecurityConfiguration}, test classes, and any future
 * gateway or reverse-proxy configuration.
 *
 * <p>Every path that appears in the Security Filter Chain permit list must be
 * declared here. Adding a new public endpoint requires adding its pattern here
 * first, then referencing {@link #PUBLIC_PATHS} in the filter chain.
 */
public final class ApiPaths {

    private ApiPaths() {
        // Utility class — no instantiation permitted.
    }

    // ── API versioning ────────────────────────────────────────────────────────

    /** URI prefix for all versioned REST API endpoints. */
    public static final String API_V1 = "/api/v1";

    // ── Actuator ──────────────────────────────────────────────────────────────

    /** Health-check endpoint. Permitted for load-balancer probes without credentials. */
    public static final String ACTUATOR_HEALTH = "/actuator/health";

    // ── OpenAPI / Swagger UI ──────────────────────────────────────────────────

    /** SpringDoc OpenAPI JSON specification endpoint (all sub-paths). */
    public static final String OPEN_API_DOCS = "/v3/api-docs/**";

    /** SpringDoc Swagger UI static resources (all sub-paths). */
    public static final String SWAGGER_UI = "/swagger-ui/**";

    /** SpringDoc Swagger UI entry-point HTML. */
    public static final String SWAGGER_UI_HTML = "/swagger-ui.html";

    // ── Consolidated permit list ──────────────────────────────────────────────

    /**
     * All endpoint path patterns that are publicly accessible without authentication.
     *
     * <p>Referenced by {@link com.devflow.security.SecurityConfiguration} when
     * constructing the {@code permitAll()} rule in the Security Filter Chain.
     * Tests that verify public-endpoint accessibility reference this array directly.
     */
    public static final String[] PUBLIC_PATHS = {
        ACTUATOR_HEALTH,
        OPEN_API_DOCS,
        SWAGGER_UI,
        SWAGGER_UI_HTML,
    };
}
