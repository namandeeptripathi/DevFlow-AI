package com.devflow.modules.auth.registry;

import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.domain.RoleScope;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.devflow.modules.auth.registry.PermissionRegistry.AI_CHAT;
import static com.devflow.modules.auth.registry.PermissionRegistry.AI_CONFIGURE;
import static com.devflow.modules.auth.registry.PermissionRegistry.AI_REVIEW;
import static com.devflow.modules.auth.registry.PermissionRegistry.AI_REVIEW_APPROVE;
import static com.devflow.modules.auth.registry.PermissionRegistry.ANALYTICS_CONFIGURE;
import static com.devflow.modules.auth.registry.PermissionRegistry.ANALYTICS_EXPORT;
import static com.devflow.modules.auth.registry.PermissionRegistry.ANALYTICS_VIEW;
import static com.devflow.modules.auth.registry.PermissionRegistry.AUTOMATION_CONFIGURE;
import static com.devflow.modules.auth.registry.PermissionRegistry.AUTOMATION_DELETE;
import static com.devflow.modules.auth.registry.PermissionRegistry.AUTOMATION_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.BILLING_MANAGE;
import static com.devflow.modules.auth.registry.PermissionRegistry.BILLING_VIEW;
import static com.devflow.modules.auth.registry.PermissionRegistry.COMMENT_CREATE;
import static com.devflow.modules.auth.registry.PermissionRegistry.COMMENT_DELETE;
import static com.devflow.modules.auth.registry.PermissionRegistry.COMMENT_EDIT;
import static com.devflow.modules.auth.registry.PermissionRegistry.COMMENT_MODERATE;
import static com.devflow.modules.auth.registry.PermissionRegistry.CYCLE_MANAGE;
import static com.devflow.modules.auth.registry.PermissionRegistry.KB_DELETE;
import static com.devflow.modules.auth.registry.PermissionRegistry.KB_MANAGE;
import static com.devflow.modules.auth.registry.PermissionRegistry.KB_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.KB_WRITE;
import static com.devflow.modules.auth.registry.PermissionRegistry.MEMBER_INVITE;
import static com.devflow.modules.auth.registry.PermissionRegistry.MEMBER_REMOVE;
import static com.devflow.modules.auth.registry.PermissionRegistry.NOTIFICATION_BROADCAST;
import static com.devflow.modules.auth.registry.PermissionRegistry.NOTIFICATION_CONFIGURE;
import static com.devflow.modules.auth.registry.PermissionRegistry.ORG_DELETE;
import static com.devflow.modules.auth.registry.PermissionRegistry.ORG_MANAGE;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_AUDIT_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_EMERGENCY_ACCESS_EXECUTE;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_FEATUREFLAG_MANAGE;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_OBSERVABILITY_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_SUPPORT_CASE_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_SUPPORT_TENANT_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_TENANT_DEPROVISION;
import static com.devflow.modules.auth.registry.PermissionRegistry.PLATFORM_TENANT_PROVISION;
import static com.devflow.modules.auth.registry.PermissionRegistry.PROJECT_DELETE;
import static com.devflow.modules.auth.registry.PermissionRegistry.PROJECT_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.PROJECT_WRITE;
import static com.devflow.modules.auth.registry.PermissionRegistry.REPO_CONFIGURE;
import static com.devflow.modules.auth.registry.PermissionRegistry.REPO_CONNECT;
import static com.devflow.modules.auth.registry.PermissionRegistry.REPO_DISCONNECT;
import static com.devflow.modules.auth.registry.PermissionRegistry.REPO_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.REPO_SCAN;
import static com.devflow.modules.auth.registry.PermissionRegistry.TASK_ASSIGN;
import static com.devflow.modules.auth.registry.PermissionRegistry.TASK_CREATE;
import static com.devflow.modules.auth.registry.PermissionRegistry.TASK_DELETE;
import static com.devflow.modules.auth.registry.PermissionRegistry.TASK_READ;
import static com.devflow.modules.auth.registry.PermissionRegistry.TASK_WRITE;

/**
 * Authoritative registry of role-to-permission mappings, scope-aware hierarchies,
 * organization-to-project inheritance, and ownership alternative policies.
 *
 * <p>Enforces the complete authorization policy matrix (§5, §6.5, §7, §9, §11, ADR-001):
 * <ul>
 *   <li>All 48 canonical permissions are mapped across all 10 canonical roles.</li>
 *   <li>Organization billing is isolated with {@code billing.view} and {@code billing.manage} and no engineering grants.</li>
 *   <li>Platform admin and platform support mappings are strictly defined.</li>
 *   <li>Organization-to-project inheritance allows Owner, Admin, and Viewer roles to exercise appropriate project rights without cross-scope role pollution.</li>
 *   <li>Organization members require explicit project assignment/role for project-scoped access.</li>
 *   <li>Ownership alternatives allow resource owners to perform owner-eligible actions provided base access is held.</li>
 * </ul>
 *
 * @see Role
 * @see RoleScope
 * @see Permission
 * @see PermissionRegistry
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §4.2, §5, §6.5</a>
 * @see <a href="docs/security/ADR-001-AUTHORIZATION-POLICY-RESOLUTION.md">ADR-001</a>
 */
public final class RolePermissionMapping {

    private static final RolePermissionMapping CANONICAL_INSTANCE = new RolePermissionMapping();

    private final Map<Role, Set<Role>> directInheritance = new EnumMap<>(Role.class);
    private final Map<Role, Set<Role>> transitiveHierarchy = new EnumMap<>(Role.class);
    private final Map<Role, Set<Permission>> directRoleGrants = new EnumMap<>(Role.class);
    private final Map<Role, Set<Permission>> effectiveRolePermissions = new EnumMap<>(Role.class);

    private static final Set<Permission> OWNERSHIP_ELIGIBLE_PERMISSIONS = Set.of(
            PROJECT_DELETE,
            TASK_DELETE,
            REPO_DISCONNECT,
            KB_DELETE,
            AUTOMATION_DELETE,
            COMMENT_EDIT,
            COMMENT_DELETE
    );

    public RolePermissionMapping() {
        initializeHierarchy();
        initializeDirectGrants();
        computeEffectivePermissions();
    }

    /**
     * Returns the singleton canonical role-permission mapping.
     *
     * @return the canonical mapping
     */
    public static RolePermissionMapping canonical() {
        return CANONICAL_INSTANCE;
    }

    private void initializeHierarchy() {
        for (Role role : Role.values()) {
            directInheritance.put(role, EnumSet.noneOf(Role.class));
        }

        // Organization Scope Hierarchy (§5.5)
        // ORGANIZATION_OWNER -> ORGANIZATION_ADMIN -> ORGANIZATION_MEMBER -> ORGANIZATION_VIEWER
        directInheritance.get(Role.ORGANIZATION_OWNER).add(Role.ORGANIZATION_ADMIN);
        directInheritance.get(Role.ORGANIZATION_ADMIN).add(Role.ORGANIZATION_MEMBER);
        directInheritance.get(Role.ORGANIZATION_MEMBER).add(Role.ORGANIZATION_VIEWER);

        // Project Scope Hierarchy (§5.5)
        // PROJECT_LEAD -> PROJECT_MEMBER -> PROJECT_VIEWER
        directInheritance.get(Role.PROJECT_LEAD).add(Role.PROJECT_MEMBER);
        directInheritance.get(Role.PROJECT_MEMBER).add(Role.PROJECT_VIEWER);

        // Compute transitive hierarchy strictly within scope boundaries
        for (Role role : Role.values()) {
            Set<Role> inherited = EnumSet.of(role);
            collectInheritedRoles(role, inherited);
            transitiveHierarchy.put(role, Collections.unmodifiableSet(inherited));
        }
    }

    private void collectInheritedRoles(Role currentRole, Set<Role> accumulator) {
        for (Role parent : directInheritance.get(currentRole)) {
            // Strict invariant: Hierarchy must never cross scope boundaries
            if (parent.getScope() != currentRole.getScope()) {
                throw new IllegalStateException(
                        "Security violation: Role hierarchy cannot cross scope boundaries: " + currentRole + " -> " + parent);
            }
            if (accumulator.add(parent)) {
                collectInheritedRoles(parent, accumulator);
            }
        }
    }

    private void initializeDirectGrants() {
        for (Role role : Role.values()) {
            directRoleGrants.put(role, new HashSet<>());
        }

        // ── Organization Roles (§5.2, §6.5, §9, ADR-001) ────────────────────

        // ORGANIZATION_VIEWER (§5.2, §6.5, §9.1-§9.6)
        directRoleGrants.get(Role.ORGANIZATION_VIEWER).addAll(Set.of(
                PROJECT_READ,
                TASK_READ,
                REPO_READ,
                KB_READ,
                ANALYTICS_VIEW
        ));

        // ORGANIZATION_MEMBER (delta on top of VIEWER, §5.2, §6.5, §9.1-§9.7)
        directRoleGrants.get(Role.ORGANIZATION_MEMBER).addAll(Set.of(
                PROJECT_WRITE,
                TASK_CREATE,
                TASK_WRITE,
                TASK_ASSIGN,
                REPO_SCAN,
                AI_CHAT,
                AI_REVIEW,
                KB_WRITE,
                AUTOMATION_READ,
                COMMENT_CREATE
        ));

        // ORGANIZATION_ADMIN (delta on top of MEMBER, §5.2, §6.5, §9.1-§9.8)
        directRoleGrants.get(Role.ORGANIZATION_ADMIN).addAll(Set.of(
                PROJECT_DELETE,
                TASK_DELETE,
                CYCLE_MANAGE,
                REPO_CONNECT,
                REPO_DISCONNECT,
                REPO_CONFIGURE,
                AI_REVIEW_APPROVE,
                AI_CONFIGURE,
                KB_MANAGE,
                KB_DELETE,
                ANALYTICS_EXPORT,
                ANALYTICS_CONFIGURE,
                AUTOMATION_CONFIGURE,
                AUTOMATION_DELETE,
                COMMENT_MODERATE,
                MEMBER_INVITE,
                MEMBER_REMOVE,
                NOTIFICATION_CONFIGURE,
                NOTIFICATION_BROADCAST
        ));

        // ORGANIZATION_OWNER (delta on top of ADMIN, §5.2, §6.5, §7.1, §11.2, ADR-001)
        directRoleGrants.get(Role.ORGANIZATION_OWNER).addAll(Set.of(
                ORG_MANAGE,
                ORG_DELETE,
                BILLING_VIEW,
                BILLING_MANAGE
        ));

        // ORGANIZATION_BILLING (Isolated role, §5.2, §12.4, ADR-001)
        directRoleGrants.get(Role.ORGANIZATION_BILLING).addAll(Set.of(
                BILLING_VIEW,
                BILLING_MANAGE
        ));

        // ── Project Roles (§5.3, §6.5, §9.1-§9.8, ADR-001) ──────────────────

        // PROJECT_VIEWER (§5.3, §9.1, §9.2)
        directRoleGrants.get(Role.PROJECT_VIEWER).addAll(Set.of(
                PROJECT_READ,
                TASK_READ,
                KB_READ,
                ANALYTICS_VIEW
        ));

        // PROJECT_MEMBER (§5.3, §9.2, §9.3, §9.4, §9.5, §9.7)
        directRoleGrants.get(Role.PROJECT_MEMBER).addAll(Set.of(
                TASK_CREATE,
                TASK_WRITE,
                REPO_SCAN,
                REPO_READ,
                AI_CHAT,
                AI_REVIEW,
                KB_WRITE,
                AUTOMATION_READ,
                COMMENT_CREATE
        ));

        // PROJECT_LEAD (§5.3, §6.5, §9.1-§9.7)
        directRoleGrants.get(Role.PROJECT_LEAD).addAll(Set.of(
                PROJECT_WRITE,
                PROJECT_DELETE, // owner-gated
                TASK_ASSIGN,
                TASK_DELETE,
                CYCLE_MANAGE,
                AI_REVIEW_APPROVE,
                KB_MANAGE,
                KB_DELETE,
                ANALYTICS_EXPORT,
                AUTOMATION_CONFIGURE,
                COMMENT_MODERATE
        ));

        // ── Platform Roles (§5.1, §11.1, ADR-001) ───────────────────────────

        // PLATFORM_SUPPORT (Read-only cross-tenant support, §5.1, ADR-001)
        directRoleGrants.get(Role.PLATFORM_SUPPORT).addAll(Set.of(
                PLATFORM_SUPPORT_CASE_READ,
                PLATFORM_SUPPORT_TENANT_READ
        ));

        // PLATFORM_ADMIN (§5.1, §11.1, ADR-001)
        directRoleGrants.get(Role.PLATFORM_ADMIN).addAll(Set.of(
                PLATFORM_TENANT_PROVISION,
                PLATFORM_TENANT_DEPROVISION,
                PLATFORM_FEATUREFLAG_MANAGE,
                PLATFORM_OBSERVABILITY_READ,
                PLATFORM_AUDIT_READ,
                PLATFORM_SUPPORT_CASE_READ,
                PLATFORM_SUPPORT_TENANT_READ,
                PLATFORM_EMERGENCY_ACCESS_EXECUTE
        ));
    }

    private void computeEffectivePermissions() {
        for (Role role : Role.values()) {
            Set<Permission> effective = new HashSet<>();
            for (Role inheritedRole : transitiveHierarchy.get(role)) {
                effective.addAll(directRoleGrants.get(inheritedRole));
            }
            effectiveRolePermissions.put(role, Collections.unmodifiableSet(effective));
        }
    }

    /**
     * Gets all transitive roles inherited by the specified role within its scope.
     *
     * @param role the target role
     * @return unmodifiable set containing the role and all inherited roles
     */
    public Set<Role> getInheritedRoles(Role role) {
        Objects.requireNonNull(role, "role must not be null");
        return transitiveHierarchy.get(role);
    }

    /**
     * Returns the complete effective set of permissions granted to a single role.
     *
     * @param role the target role
     * @return unmodifiable set of granted permissions
     */
    public Set<Permission> getPermissions(Role role) {
        Objects.requireNonNull(role, "role must not be null");
        return effectiveRolePermissions.get(role);
    }

    /**
     * Combines permissions for a collection of roles matching the requested authorization scope.
     *
     * <p>Roles from other scopes are strictly ignored to prevent cross-scope privilege escalation.
     * Grants are combined using set union.
     *
     * @param roles the roles assigned to the user
     * @param scope the required authorization scope
     * @return unmodifiable set of combined permissions
     */
    public Set<Permission> getPermissionsForRoles(Collection<Role> roles, RoleScope scope) {
        if (roles == null || roles.isEmpty() || scope == null) {
            return Collections.emptySet();
        }

        Set<Permission> union = new HashSet<>();
        for (Role role : roles) {
            if (role != null && role.getScope() == scope) {
                union.addAll(getPermissions(role));
            }
        }
        return Collections.unmodifiableSet(union);
    }

    /**
     * Combines permissions for a collection of roles that all belong to the same scope.
     *
     * @param roles the roles assigned to the user
     * @return unmodifiable set of combined permissions
     */
    public Set<Permission> getPermissionsForRoles(Collection<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Permission> union = new HashSet<>();
        for (Role role : roles) {
            if (role != null) {
                union.addAll(getPermissions(role));
            }
        }
        return Collections.unmodifiableSet(union);
    }

    /**
     * Returns the project permissions inherited by an organization-level role into projects (§4.2, ADR-001).
     *
     * <ul>
     *   <li>{@link Role#ORGANIZATION_OWNER} and {@link Role#ORGANIZATION_ADMIN} inherit project administration
     *       permissions into all projects within their organization.</li>
     *   <li>{@link Role#ORGANIZATION_VIEWER} inherits read-only access to project boards and resources.</li>
     *   <li>{@link Role#ORGANIZATION_MEMBER} and {@link Role#ORGANIZATION_BILLING} do not automatically inherit
     *       project-level permissions (members require explicit project assignment).</li>
     * </ul>
     *
     * @param orgRole the organization role
     * @return unmodifiable set of inherited project permissions
     */
    public Set<Permission> getInheritedProjectPermissions(Role orgRole) {
        if (orgRole == null || orgRole.getScope() != RoleScope.ORGANIZATION) {
            return Collections.emptySet();
        }

        if (orgRole == Role.ORGANIZATION_OWNER || orgRole == Role.ORGANIZATION_ADMIN) {
            return effectiveRolePermissions.get(orgRole).stream()
                    .filter(p -> !p.resource().equals("org")
                            && !p.resource().equals("member")
                            && !p.resource().equals("billing")
                            && !p.resource().equals("platform"))
                    .collect(Collectors.toUnmodifiableSet());
        }

        if (orgRole == Role.ORGANIZATION_VIEWER) {
            return Set.of(PROJECT_READ, TASK_READ, REPO_READ, KB_READ, ANALYTICS_VIEW);
        }

        return Collections.emptySet();
    }

    /**
     * Checks whether a permission is eligible for resource ownership fulfillment (§7, ADR-001).
     *
     * @param permission the permission to check
     * @return {@code true} if ownership can grant or gate the operation
     */
    public boolean isOwnershipEligible(Permission permission) {
        return permission != null && OWNERSHIP_ELIGIBLE_PERMISSIONS.contains(permission);
    }

    /**
     * Verifies whether the assigned roles provide base resource access to the target resource type (§7.2, ADR-001).
     *
     * <p>Ownership cannot bypass default-deny for resource types the user's role has no access to.
     *
     * @param roles      the user's assigned roles
     * @param permission the target permission
     * @return {@code true} if base access is held; {@code false} otherwise
     */
    public boolean hasBaseResourceAccess(Collection<Role> roles, Permission permission) {
        if (roles == null || roles.isEmpty() || permission == null) {
            return false;
        }

        for (Role role : roles) {
            if (role == null) continue;
            // ORGANIZATION_ADMIN and ORGANIZATION_OWNER always hold base access
            if (role == Role.ORGANIZATION_ADMIN || role == Role.ORGANIZATION_OWNER) {
                return true;
            }

            String resource = permission.resource();
            switch (resource) {
                case "project" -> {
                    if (role == Role.PROJECT_LEAD || role == Role.PROJECT_MEMBER || role == Role.ORGANIZATION_MEMBER) {
                        return true;
                    }
                }
                case "task", "comment" -> {
                    if (role == Role.PROJECT_LEAD || role == Role.PROJECT_MEMBER || role == Role.ORGANIZATION_MEMBER) {
                        return true;
                    }
                }
                case "repo" -> {
                    if (role == Role.ORGANIZATION_MEMBER || role == Role.PROJECT_MEMBER || role == Role.PROJECT_LEAD) {
                        return true;
                    }
                }
                case "kb" -> {
                    if (role == Role.PROJECT_LEAD || role == Role.PROJECT_MEMBER || role == Role.ORGANIZATION_MEMBER) {
                        return true;
                    }
                }
                case "automation" -> {
                    if (role == Role.PROJECT_LEAD || role == Role.ORGANIZATION_MEMBER || role == Role.PROJECT_MEMBER) {
                        return true;
                    }
                }
                default -> {
                    // check if the role has any permission in that resource
                    boolean hasResourcePerm = effectiveRolePermissions.get(role).stream()
                            .anyMatch(p -> p.resource().equals(resource));
                    if (hasResourcePerm) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Determines whether the given permission is owner-gated for the provided roles.
     *
     * <p>If a role holds direct unconditioned privileged authority (e.g. {@link Role#ORGANIZATION_ADMIN}
     * or {@link Role#ORGANIZATION_OWNER} for {@code project.delete}), ownership is not required.
     * Otherwise, owner-eligible operations require verified ownership (§6.5, §7.1, ADR-001).
     *
     * @param roles      the user's roles
     * @param permission the requested permission
     * @return {@code true} if an ownership check is required; {@code false} otherwise
     */
    public boolean isOwnerGated(Collection<Role> roles, Permission permission) {
        if (roles == null || permission == null) {
            return false;
        }

        if (PROJECT_DELETE.equals(permission)) {
            boolean hasAdminOrOwner = roles.contains(Role.ORGANIZATION_ADMIN) || roles.contains(Role.ORGANIZATION_OWNER);
            return !hasAdminOrOwner;
        }

        if (TASK_DELETE.equals(permission)) {
            boolean hasAdminOrLead = roles.contains(Role.ORGANIZATION_ADMIN)
                    || roles.contains(Role.ORGANIZATION_OWNER)
                    || roles.contains(Role.PROJECT_LEAD);
            return !hasAdminOrLead;
        }

        if (REPO_DISCONNECT.equals(permission)) {
            boolean hasAdminOrOwner = roles.contains(Role.ORGANIZATION_ADMIN) || roles.contains(Role.ORGANIZATION_OWNER);
            return !hasAdminOrOwner;
        }

        if (KB_DELETE.equals(permission)) {
            boolean hasAdminOrLead = roles.contains(Role.ORGANIZATION_ADMIN)
                    || roles.contains(Role.ORGANIZATION_OWNER)
                    || roles.contains(Role.PROJECT_LEAD);
            return !hasAdminOrLead;
        }

        if (AUTOMATION_DELETE.equals(permission)) {
            boolean hasAdminOrOwner = roles.contains(Role.ORGANIZATION_ADMIN) || roles.contains(Role.ORGANIZATION_OWNER);
            return !hasAdminOrOwner;
        }

        if (COMMENT_EDIT.equals(permission) || COMMENT_DELETE.equals(permission)) {
            boolean hasModerator = roles.contains(Role.ORGANIZATION_ADMIN)
                    || roles.contains(Role.ORGANIZATION_OWNER)
                    || roles.contains(Role.PROJECT_LEAD);
            return !hasModerator;
        }

        return false;
    }
}
