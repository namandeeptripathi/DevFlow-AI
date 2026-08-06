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

    // ── Authentication ────────────────────────────────────────────────────────

    public static final String AUTH_REGISTER = API_V1 + "/auth/register";
    public static final String AUTH_LOGIN = API_V1 + "/auth/login";
    public static final String AUTH_REFRESH = API_V1 + "/auth/refresh";
    public static final String AUTH_LOGOUT = API_V1 + "/auth/logout";

    // ── User Profile ──────────────────────────────────────────────────────────

    /** Base path for all user-domain endpoints. */
    public static final String USER_BASE = API_V1 + "/users";

    /** Authenticated user's own profile resource. */
    public static final String USERS_ME = USER_BASE + "/me";

    /** Authenticated user's avatar sub-resource. */
    public static final String USERS_ME_AVATAR = USERS_ME + "/avatar";

    /** Authenticated user's preferences sub-resource. */
    public static final String USERS_ME_PREFERENCES = USERS_ME + "/preferences";

    /** User search endpoint. */
    public static final String USERS_SEARCH = USER_BASE + "/search";

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
        AUTH_REGISTER,
        AUTH_LOGIN,
        AUTH_REFRESH,
        AUTH_LOGOUT
    };
}
