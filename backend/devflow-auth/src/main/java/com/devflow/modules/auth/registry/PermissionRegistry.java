package com.devflow.modules.auth.registry;

import com.devflow.modules.auth.domain.Permission;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Centralized, authoritative registry of canonical authorization permissions.
 *
 * <p>Enforces strict registration invariants:
 * <ul>
 *   <li>Pre-defines all 48 canonical permissions explicitly documented in {@code AUTHORIZATION_MODEL.md} and ADR-001.</li>
 *   <li>Prevents accidental duplicate permission registrations.</li>
 *   <li>Provides high-performance lookup and enumeration by resource domain.</li>
 *   <li>Preserves the canonical {@code {resource}.{action}} naming grammar.</li>
 *   <li>Serves as the authoritative source for rejecting unknown permissions during evaluation.</li>
 * </ul>
 *
 * @see Permission
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §6</a>
 */
public final class PermissionRegistry {

    // ── Canonical Permission Constants (§6.2, §6.5, §9) ───────────────────────

    // Identity & Access (§6.2)
    public static final Permission ORG_MANAGE = Permission.of("org", "manage");
    public static final Permission ORG_DELETE = Permission.of("org", "delete");
    public static final Permission MEMBER_INVITE = Permission.of("member", "invite");
    public static final Permission MEMBER_REMOVE = Permission.of("member", "remove");

    // Project Management (§6.2, §9.1, §9.2)
    public static final Permission PROJECT_READ = Permission.of("project", "read");
    public static final Permission PROJECT_WRITE = Permission.of("project", "write");
    public static final Permission PROJECT_DELETE = Permission.of("project", "delete");
    public static final Permission TASK_READ = Permission.of("task", "read");
    public static final Permission TASK_CREATE = Permission.of("task", "create");
    public static final Permission TASK_WRITE = Permission.of("task", "write");
    public static final Permission TASK_ASSIGN = Permission.of("task", "assign");
    public static final Permission TASK_DELETE = Permission.of("task", "delete");
    public static final Permission CYCLE_MANAGE = Permission.of("cycle", "manage");

    // Repository Intelligence (§6.2, §9.3)
    public static final Permission REPO_READ = Permission.of("repo", "read");
    public static final Permission REPO_CONNECT = Permission.of("repo", "connect");
    public static final Permission REPO_DISCONNECT = Permission.of("repo", "disconnect");
    public static final Permission REPO_SCAN = Permission.of("repo", "scan");
    public static final Permission REPO_CONFIGURE = Permission.of("repo", "configure");

    // AI Engine (§6.2, §9.4)
    public static final Permission AI_CHAT = Permission.of("ai", "chat");
    public static final Permission AI_REVIEW = Permission.of("ai", "review");
    public static final Permission AI_REVIEW_APPROVE = Permission.of("ai", "review.approve");
    public static final Permission AI_CONFIGURE = Permission.of("ai", "configure");

    // Knowledge Base (§6.2, §9.5)
    public static final Permission KB_READ = Permission.of("kb", "read");
    public static final Permission KB_WRITE = Permission.of("kb", "write");
    public static final Permission KB_MANAGE = Permission.of("kb", "manage");
    public static final Permission KB_DELETE = Permission.of("kb", "delete");

    // Developer Analytics (§6.2, §9.6)
    public static final Permission ANALYTICS_VIEW = Permission.of("analytics", "view");
    public static final Permission ANALYTICS_EXPORT = Permission.of("analytics", "export");
    public static final Permission ANALYTICS_CONFIGURE = Permission.of("analytics", "configure");

    // Workflow Automation (§6.2, §9.7)
    public static final Permission AUTOMATION_READ = Permission.of("automation", "read");
    public static final Permission AUTOMATION_CONFIGURE = Permission.of("automation", "configure");
    public static final Permission AUTOMATION_DELETE = Permission.of("automation", "delete");

    // Collaboration (§6.2)
    public static final Permission COMMENT_CREATE = Permission.of("comment", "create");
    public static final Permission COMMENT_EDIT = Permission.of("comment", "edit");
    public static final Permission COMMENT_DELETE = Permission.of("comment", "delete");
    public static final Permission COMMENT_MODERATE = Permission.of("comment", "moderate");

    // Notifications (§6.2, §9.8)
    public static final Permission NOTIFICATION_CONFIGURE = Permission.of("notification", "configure");
    public static final Permission NOTIFICATION_BROADCAST = Permission.of("notification", "broadcast");

    // Billing (§6.2)
    public static final Permission BILLING_VIEW = Permission.of("billing", "view");
    public static final Permission BILLING_MANAGE = Permission.of("billing", "manage");

    // Platform Administration & Operations (§5.1, §11.1, ADR-001)
    public static final Permission PLATFORM_TENANT_PROVISION = Permission.of("platform", "tenant.provision");
    public static final Permission PLATFORM_TENANT_DEPROVISION = Permission.of("platform", "tenant.deprovision");
    public static final Permission PLATFORM_FEATUREFLAG_MANAGE = Permission.of("platform", "featureflag.manage");
    public static final Permission PLATFORM_OBSERVABILITY_READ = Permission.of("platform", "observability.read");
    public static final Permission PLATFORM_AUDIT_READ = Permission.of("platform", "audit.read");
    public static final Permission PLATFORM_SUPPORT_CASE_READ = Permission.of("platform", "support.case.read");
    public static final Permission PLATFORM_SUPPORT_TENANT_READ = Permission.of("platform", "support.tenant.read");
    public static final Permission PLATFORM_EMERGENCY_ACCESS_EXECUTE = Permission.of("platform", "emergency_access.execute");

    private static final List<Permission> ALL_CANONICAL_PERMISSIONS = List.of(
            ORG_MANAGE, ORG_DELETE, MEMBER_INVITE, MEMBER_REMOVE,
            PROJECT_READ, PROJECT_WRITE, PROJECT_DELETE,
            TASK_READ, TASK_CREATE, TASK_WRITE, TASK_ASSIGN, TASK_DELETE, CYCLE_MANAGE,
            REPO_READ, REPO_CONNECT, REPO_DISCONNECT, REPO_SCAN, REPO_CONFIGURE,
            AI_CHAT, AI_REVIEW, AI_REVIEW_APPROVE, AI_CONFIGURE,
            KB_READ, KB_WRITE, KB_MANAGE, KB_DELETE,
            ANALYTICS_VIEW, ANALYTICS_EXPORT, ANALYTICS_CONFIGURE,
            AUTOMATION_READ, AUTOMATION_CONFIGURE, AUTOMATION_DELETE,
            COMMENT_CREATE, COMMENT_EDIT, COMMENT_DELETE, COMMENT_MODERATE,
            NOTIFICATION_CONFIGURE, NOTIFICATION_BROADCAST,
            BILLING_VIEW, BILLING_MANAGE,
            PLATFORM_TENANT_PROVISION, PLATFORM_TENANT_DEPROVISION,
            PLATFORM_FEATUREFLAG_MANAGE, PLATFORM_OBSERVABILITY_READ, PLATFORM_AUDIT_READ,
            PLATFORM_SUPPORT_CASE_READ, PLATFORM_SUPPORT_TENANT_READ,
            PLATFORM_EMERGENCY_ACCESS_EXECUTE
    );

    private static final PermissionRegistry CANONICAL_INSTANCE = new PermissionRegistry(true);

    private final Map<String, Permission> permissions = new ConcurrentHashMap<>();

    public PermissionRegistry() {
        this(false);
    }

    private PermissionRegistry(boolean populateDefaults) {
        if (populateDefaults) {
            for (Permission p : ALL_CANONICAL_PERMISSIONS) {
                permissions.put(p.value(), p);
            }
        }
    }

    /**
     * Returns the singleton canonical permission registry initialized with all 48 approved permissions.
     *
     * @return the canonical registry
     */
    public static PermissionRegistry canonical() {
        return CANONICAL_INSTANCE;
    }

    /**
     * Creates an empty permission registry instance for test isolation.
     *
     * @return a new empty registry
     */
    public static PermissionRegistry empty() {
        return new PermissionRegistry(false);
    }

    /**
     * Registers a new permission in this registry.
     *
     * @param permission the permission to register
     * @throws NullPointerException     if permission is null
     * @throws IllegalArgumentException if a permission with the same canonical string is already registered
     */
    public void register(Permission permission) {
        Objects.requireNonNull(permission, "permission must not be null");
        Permission existing = permissions.putIfAbsent(permission.value(), permission);
        if (existing != null) {
            throw new IllegalArgumentException("Permission already registered: " + permission.value());
        }
    }

    /**
     * Checks whether the given permission is registered.
     *
     * @param permission the permission to check
     * @return {@code true} if registered; {@code false} otherwise
     */
    public boolean isRegistered(Permission permission) {
        if (permission == null) {
            return false;
        }
        return permissions.containsKey(permission.value());
    }

    /**
     * Checks whether a permission with the given canonical string is registered.
     *
     * @param permissionValue the permission string to check
     * @return {@code true} if registered; {@code false} otherwise
     */
    public boolean isRegistered(String permissionValue) {
        if (permissionValue == null || permissionValue.isBlank()) {
            return false;
        }
        return permissions.containsKey(permissionValue.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Looks up a registered permission by its canonical string value.
     *
     * @param permissionValue the string value (e.g. "project.read")
     * @return an {@link Optional} containing the permission if found
     */
    public Optional<Permission> find(String permissionValue) {
        if (permissionValue == null || permissionValue.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(permissions.get(permissionValue.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Gets a registered permission by its canonical string value, throwing an exception if not found.
     *
     * @param permissionValue the string value
     * @return the registered permission
     * @throws IllegalArgumentException if the permission is not registered
     */
    public Permission get(String permissionValue) {
        return find(permissionValue)
                .orElseThrow(() -> new IllegalArgumentException("Unknown permission: " + permissionValue));
    }

    /**
     * Returns an unmodifiable snapshot of all registered permissions.
     *
     * @return set of all registered permissions
     */
    public Set<Permission> getAll() {
        return Set.copyOf(permissions.values());
    }

    /**
     * Returns an unmodifiable set of all registered permissions belonging to the specified resource domain.
     *
     * @param resource the resource name (e.g. "project", "task", "repo")
     * @return set of permissions for the resource
     */
    public Set<Permission> getByResource(String resource) {
        if (resource == null || resource.isBlank()) {
            return Collections.emptySet();
        }
        String normalizedResource = resource.trim().toLowerCase(Locale.ROOT);
        return permissions.values().stream()
                .filter(p -> p.resource().equals(normalizedResource))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns the total count of registered permissions.
     *
     * @return total permission count
     */
    public int size() {
        return permissions.size();
    }
}

