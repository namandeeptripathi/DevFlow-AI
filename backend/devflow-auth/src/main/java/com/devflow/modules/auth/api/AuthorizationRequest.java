package com.devflow.modules.auth.api;

import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.context.OrganizationAuthorizationContext;
import com.devflow.modules.auth.context.PlatformAuthorizationContext;
import com.devflow.modules.auth.context.ProjectAuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.RoleScope;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable request specification for authorization policy evaluation by {@link AuthApi}.
 *
 * <p>Encapsulates the requesting principal, the typed {@link AuthorizationContext} defining the
 * hierarchical authorization boundary (Platform, Organization, or Project), the required permission,
 * and optional resource/contextual attribute metadata.
 *
 * <h2>Supported Attribute Value Types</h2>
 * To guarantee strict immutability without arbitrary reflection or serialization:
 * <ul>
 *   <li>{@link String}, {@link Boolean}</li>
 *   <li>Immutable numeric types: {@link Integer}, {@link Long}, {@link Double}, {@link Float}, {@link Short}, {@link Byte}, {@link BigDecimal}, {@link BigInteger}</li>
 *   <li>{@link UUID}, {@link Enum}</li>
 *   <li>Temporal types: {@link Instant}, {@link LocalDate}, {@link LocalTime}, {@link LocalDateTime}, {@link OffsetDateTime}, {@link ZonedDateTime}, {@link Duration}</li>
 *   <li>Nested {@link Map} and {@link Collection} containing only supported types (recursively defensively copied into unmodifiable views)</li>
 * </ul>
 * Any null attribute value, mutable numeric type (such as {@code AtomicInteger} or {@code AtomicLong}),
 * or unsupported mutable object type will fail validation.
 *
 * @param userId     the UUID of the authenticated principal requesting access
 * @param context    the typed authorization context establishing the scope boundary
 * @param permission the permission required for the operation
 * @param resourceId an optional identifier of the target sub-resource (e.g. task ID, cycle ID)
 * @param attributes an unmodifiable map of contextual attributes for extensible policy evaluation
 *
 * @see AuthApi
 * @see AuthorizationContext
 * @see AuthorizationDecision
 * @see Permission
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §10, §14.7</a>
 */
public record AuthorizationRequest(
        UUID userId,
        AuthorizationContext context,
        Permission permission,
        String resourceId,
        Map<String, Object> attributes
) implements Serializable {

    public AuthorizationRequest {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        attributes = sanitizeAttributes(attributes);
    }

    // ── Platform Scope Factories ─────────────────────────────────────────────

    /**
     * Creates a platform-scoped authorization request.
     *
     * @param userId     the UUID of the user
     * @param permission the required permission
     * @return an immutable platform authorization request
     */
    public static AuthorizationRequest platform(UUID userId, Permission permission) {
        return new AuthorizationRequest(userId, AuthorizationContext.platform(), permission, null, Collections.emptyMap());
    }

    /**
     * Creates a platform-scoped authorization request with contextual attributes.
     *
     * @param userId     the UUID of the user
     * @param permission the required permission
     * @param attributes contextual attributes
     * @return an immutable platform authorization request
     */
    public static AuthorizationRequest platform(UUID userId, Permission permission, Map<String, Object> attributes) {
        return new AuthorizationRequest(userId, AuthorizationContext.platform(), permission, null, attributes);
    }

    // ── Organization Scope Factories ─────────────────────────────────────────

    /**
     * Creates an organization-scoped authorization request without specific sub-resource ID.
     *
     * @param userId         the UUID of the user
     * @param organizationId the UUID of the tenant organization
     * @param permission     the required permission
     * @return an immutable organization authorization request
     */
    public static AuthorizationRequest organization(UUID userId, UUID organizationId, Permission permission) {
        return new AuthorizationRequest(userId, AuthorizationContext.organization(organizationId), permission, null, Collections.emptyMap());
    }

    /**
     * Creates an organization-scoped authorization request for a specific sub-resource.
     *
     * @param userId         the UUID of the user
     * @param organizationId the UUID of the tenant organization
     * @param permission     the required permission
     * @param resourceId     an optional identifier of the target resource
     * @return an immutable organization authorization request
     */
    public static AuthorizationRequest organization(UUID userId, UUID organizationId, Permission permission, String resourceId) {
        return new AuthorizationRequest(userId, AuthorizationContext.organization(organizationId), permission, resourceId, Collections.emptyMap());
    }

    /**
     * Creates an organization-scoped authorization request with full metadata.
     *
     * @param userId         the UUID of the user
     * @param organizationId the UUID of the tenant organization
     * @param permission     the required permission
     * @param resourceId     an optional identifier of the target resource
     * @param attributes     contextual attributes
     * @return an immutable organization authorization request
     */
    public static AuthorizationRequest organization(
            UUID userId, UUID organizationId, Permission permission, String resourceId, Map<String, Object> attributes) {
        return new AuthorizationRequest(userId, AuthorizationContext.organization(organizationId), permission, resourceId, attributes);
    }

    // ── Project Scope Factories ──────────────────────────────────────────────

    /**
     * Creates a project-scoped authorization request bounded to an organization.
     *
     * @param userId         the UUID of the user
     * @param organizationId the UUID of the enclosing organization
     * @param projectId      the UUID of the target project
     * @param permission     the required permission
     * @return an immutable project authorization request
     */
    public static AuthorizationRequest project(UUID userId, UUID organizationId, UUID projectId, Permission permission) {
        return new AuthorizationRequest(userId, AuthorizationContext.project(organizationId, projectId), permission, null, Collections.emptyMap());
    }

    /**
     * Creates a project-scoped authorization request for a sub-resource within the project.
     *
     * @param userId         the UUID of the user
     * @param organizationId the UUID of the enclosing organization
     * @param projectId      the UUID of the target project
     * @param permission     the required permission
     * @param resourceId     an optional identifier of the target sub-resource (e.g. task ID)
     * @return an immutable project authorization request
     */
    public static AuthorizationRequest project(
            UUID userId, UUID organizationId, UUID projectId, Permission permission, String resourceId) {
        return new AuthorizationRequest(userId, AuthorizationContext.project(organizationId, projectId), permission, resourceId, Collections.emptyMap());
    }

    /**
     * Creates a project-scoped authorization request with full metadata.
     *
     * @param userId         the UUID of the user
     * @param organizationId the UUID of the enclosing organization
     * @param projectId      the UUID of the target project
     * @param permission     the required permission
     * @param resourceId     an optional identifier of the target sub-resource
     * @param attributes     contextual attributes
     * @return an immutable project authorization request
     */
    public static AuthorizationRequest project(
            UUID userId, UUID organizationId, UUID projectId, Permission permission, String resourceId, Map<String, Object> attributes) {
        return new AuthorizationRequest(userId, AuthorizationContext.project(organizationId, projectId), permission, resourceId, attributes);
    }

    // ── Generic Typed Context Factories ──────────────────────────────────────

    /**
     * Creates an authorization request for an explicit {@link AuthorizationContext}.
     *
     * @param userId     the UUID of the user
     * @param context    the authorization context
     * @param permission the required permission
     * @return an immutable authorization request
     */
    public static AuthorizationRequest of(UUID userId, AuthorizationContext context, Permission permission) {
        return new AuthorizationRequest(userId, context, permission, null, Collections.emptyMap());
    }

    /**
     * Creates an authorization request for an explicit {@link AuthorizationContext} and sub-resource.
     *
     * @param userId     the UUID of the user
     * @param context    the authorization context
     * @param permission the required permission
     * @param resourceId the target sub-resource ID
     * @return an immutable authorization request
     */
    public static AuthorizationRequest of(UUID userId, AuthorizationContext context, Permission permission, String resourceId) {
        return new AuthorizationRequest(userId, context, permission, resourceId, Collections.emptyMap());
    }

    // ── Backward-Compatible Convenience Factories ────────────────────────────

    /**
     * Convenience factory for organization-scoped authorization checks without sub-resource ID.
     *
     * @param userId         the UUID of the user
     * @param organizationId the UUID of the organization
     * @param permission     the required permission
     * @return an immutable organization-scoped request
     */
    public static AuthorizationRequest of(UUID userId, UUID organizationId, Permission permission) {
        return organization(userId, organizationId, permission);
    }

    /**
     * Convenience factory for organization-scoped authorization checks with sub-resource ID.
     *
     * @param userId         the UUID of the user
     * @param organizationId the UUID of the organization
     * @param permission     the required permission
     * @param resourceId     the target sub-resource ID
     * @return an immutable organization-scoped request
     */
    public static AuthorizationRequest of(UUID userId, UUID organizationId, Permission permission, String resourceId) {
        return organization(userId, organizationId, permission, resourceId);
    }

    // ── Query Accessors ──────────────────────────────────────────────────────

    /**
     * Gets the authorization scope of this request.
     *
     * @return the {@link RoleScope}
     */
    public RoleScope getScope() {
        return context.getScope();
    }

    /**
     * Gets the tenant organization ID if present.
     *
     * @return an {@link Optional} containing the organization UUID
     */
    public Optional<UUID> getOrganizationId() {
        return context.getOrganizationId();
    }

    /**
     * Gets the target project ID if present.
     *
     * @return an {@link Optional} containing the project UUID
     */
    public Optional<UUID> getProjectId() {
        return context.getProjectId();
    }

    /**
     * Gets the target sub-resource ID if present.
     *
     * @return an {@link Optional} containing the resourceId
     */
    public Optional<String> getResourceId() {
        return Optional.ofNullable(resourceId);
    }

    private static final Set<Class<?>> IMMUTABLE_NUMERIC_TYPES = Set.of(
            Integer.class,
            Long.class,
            Double.class,
            Float.class,
            Short.class,
            Byte.class,
            BigDecimal.class,
            BigInteger.class
    );

    // ── Immutability & Sanitization Helpers ───────────────────────────────────

    private static Map<String, Object> sanitizeAttributes(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            Objects.requireNonNull(key, "Attribute key must not be null");
            if (key.isBlank()) {
                throw new IllegalArgumentException("Attribute key must not be blank");
            }
            sanitized.put(key, sanitizeValue(key, entry.getValue()));
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private static Object sanitizeValue(String key, Object value) {
        Objects.requireNonNull(value, "Attribute value for key '" + key + "' must not be null");

        // Immutable scalars & value types
        if (value instanceof String || value instanceof Boolean || isImmutableNumber(value)
                || value instanceof UUID || value instanceof Enum<?>
                || value instanceof Instant || value instanceof LocalDate
                || value instanceof LocalTime || value instanceof LocalDateTime
                || value instanceof OffsetDateTime || value instanceof ZonedDateTime
                || value instanceof Duration) {
            return value;
        }

        // Recursively sanitized and defensively copied nested maps
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String childKey)) {
                    throw new IllegalArgumentException("Nested map key must be a non-null String, got: " + entry.getKey());
                }
                if (childKey.isBlank()) {
                    throw new IllegalArgumentException("Nested map key must not be blank");
                }
                nested.put(childKey, sanitizeValue(key + "." + childKey, entry.getValue()));
            }
            return Collections.unmodifiableMap(nested);
        }

        // Recursively sanitized and defensively copied nested collections
        if (value instanceof Collection<?> collection) {
            List<Object> nested = new ArrayList<>(collection.size());
            int idx = 0;
            for (Object item : collection) {
                nested.add(sanitizeValue(key + "[" + idx++ + "]", item));
            }
            return Collections.unmodifiableList(nested);
        }

        throw new IllegalArgumentException(
                "Unsupported attribute value type for key '" + key + "': " + value.getClass().getName()
                        + ". Supported types: String, Boolean, immutable Numbers (Integer, Long, Double, Float, Short, Byte, BigDecimal, BigInteger), UUID, Enum, Temporal types, and unmodifiable Maps/Collections thereof.");
    }

    private static boolean isImmutableNumber(Object value) {
        return IMMUTABLE_NUMERIC_TYPES.contains(value.getClass());
    }
}
