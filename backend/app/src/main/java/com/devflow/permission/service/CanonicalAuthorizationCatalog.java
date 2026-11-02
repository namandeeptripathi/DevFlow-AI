package com.devflow.permission.service;

import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative registry of stable canonical UUID identifiers and metadata for the
 * DevFlow authorization catalog.
 *
 * <p>Preserves deterministic UUID generation matching the baseline database migration
 * ({@code V13__create_authorization_tables.sql}). Guarantees that canonical database
 * records maintain stable primary keys across all environments.
 *
 * @see Role
 * @see Permission
 */
public final class CanonicalAuthorizationCatalog {

    /** RFC 4122 DNS namespace UUID used for deterministic identifier generation. */
    public static final UUID NAMESPACE = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    // ── Canonical Role UUID Constants ─────────────────────────────────────────
    public static final UUID ROLE_PLATFORM_ADMIN = UUID.fromString("184e8dbb-66fe-55c3-bd25-c3c47d49d520");
    public static final UUID ROLE_PLATFORM_SUPPORT = UUID.fromString("04131dca-7f4c-5131-b77d-fc5f5034436d");
    public static final UUID ROLE_ORGANIZATION_OWNER = UUID.fromString("30fbdce8-5d3e-5025-9fba-3af23689668b");
    public static final UUID ROLE_ORGANIZATION_ADMIN = UUID.fromString("8c830b56-e86c-5072-8471-2bd34e4e2522");
    public static final UUID ROLE_ORGANIZATION_MEMBER = UUID.fromString("18a58295-a572-51fe-a0a7-b9882954faec");
    public static final UUID ROLE_ORGANIZATION_VIEWER = UUID.fromString("272e64a4-aac5-57cb-bd88-3309d7ce7909");
    public static final UUID ROLE_ORGANIZATION_BILLING = UUID.fromString("50d48c19-396c-56b5-b175-d480bac033fa");
    public static final UUID ROLE_PROJECT_LEAD = UUID.fromString("baf57136-10e9-5505-9d80-bc335c0556b2");
    public static final UUID ROLE_PROJECT_MEMBER = UUID.fromString("9386ad97-19bd-53c0-82fc-a172c746e5d7");
    public static final UUID ROLE_PROJECT_VIEWER = UUID.fromString("2fd979fb-a361-5ff8-bb25-cd95d09dcd88");

    private static final Map<Role, UUID> ROLE_TO_UUID;
    private static final Map<UUID, Role> UUID_TO_ROLE;
    private static final Map<String, UUID> PERMISSION_TO_UUID;
    private static final Map<UUID, String> UUID_TO_PERMISSION;

    static {
        Map<Role, UUID> rMap = new EnumMap<>(Role.class);
        rMap.put(Role.PLATFORM_ADMIN, ROLE_PLATFORM_ADMIN);
        rMap.put(Role.PLATFORM_SUPPORT, ROLE_PLATFORM_SUPPORT);
        rMap.put(Role.ORGANIZATION_OWNER, ROLE_ORGANIZATION_OWNER);
        rMap.put(Role.ORGANIZATION_ADMIN, ROLE_ORGANIZATION_ADMIN);
        rMap.put(Role.ORGANIZATION_MEMBER, ROLE_ORGANIZATION_MEMBER);
        rMap.put(Role.ORGANIZATION_VIEWER, ROLE_ORGANIZATION_VIEWER);
        rMap.put(Role.ORGANIZATION_BILLING, ROLE_ORGANIZATION_BILLING);
        rMap.put(Role.PROJECT_LEAD, ROLE_PROJECT_LEAD);
        rMap.put(Role.PROJECT_MEMBER, ROLE_PROJECT_MEMBER);
        rMap.put(Role.PROJECT_VIEWER, ROLE_PROJECT_VIEWER);

        ROLE_TO_UUID = Collections.unmodifiableMap(rMap);

        Map<UUID, Role> uRoleMap = new ConcurrentHashMap<>();
        rMap.forEach((k, v) -> uRoleMap.put(v, k));
        UUID_TO_ROLE = Collections.unmodifiableMap(uRoleMap);

        Map<String, UUID> pMap = new ConcurrentHashMap<>();
        Map<UUID, String> uPermMap = new ConcurrentHashMap<>();

        // Populate deterministic permission UUIDs
        registerPermission(pMap, uPermMap, "org.manage");
        registerPermission(pMap, uPermMap, "org.delete");
        registerPermission(pMap, uPermMap, "member.invite");
        registerPermission(pMap, uPermMap, "member.remove");
        registerPermission(pMap, uPermMap, "project.read");
        registerPermission(pMap, uPermMap, "project.write");
        registerPermission(pMap, uPermMap, "project.delete");
        registerPermission(pMap, uPermMap, "task.read");
        registerPermission(pMap, uPermMap, "task.create");
        registerPermission(pMap, uPermMap, "task.write");
        registerPermission(pMap, uPermMap, "task.assign");
        registerPermission(pMap, uPermMap, "task.delete");
        registerPermission(pMap, uPermMap, "cycle.manage");
        registerPermission(pMap, uPermMap, "repo.read");
        registerPermission(pMap, uPermMap, "repo.connect");
        registerPermission(pMap, uPermMap, "repo.disconnect");
        registerPermission(pMap, uPermMap, "repo.scan");
        registerPermission(pMap, uPermMap, "repo.configure");
        registerPermission(pMap, uPermMap, "ai.chat");
        registerPermission(pMap, uPermMap, "ai.review");
        registerPermission(pMap, uPermMap, "ai.review.approve");
        registerPermission(pMap, uPermMap, "ai.configure");
        registerPermission(pMap, uPermMap, "kb.read");
        registerPermission(pMap, uPermMap, "kb.write");
        registerPermission(pMap, uPermMap, "kb.manage");
        registerPermission(pMap, uPermMap, "kb.delete");
        registerPermission(pMap, uPermMap, "analytics.view");
        registerPermission(pMap, uPermMap, "analytics.export");
        registerPermission(pMap, uPermMap, "analytics.configure");
        registerPermission(pMap, uPermMap, "automation.read");
        registerPermission(pMap, uPermMap, "automation.configure");
        registerPermission(pMap, uPermMap, "automation.delete");
        registerPermission(pMap, uPermMap, "comment.create");
        registerPermission(pMap, uPermMap, "comment.edit");
        registerPermission(pMap, uPermMap, "comment.delete");
        registerPermission(pMap, uPermMap, "comment.moderate");
        registerPermission(pMap, uPermMap, "notification.configure");
        registerPermission(pMap, uPermMap, "notification.broadcast");
        registerPermission(pMap, uPermMap, "billing.view");
        registerPermission(pMap, uPermMap, "billing.manage");
        registerPermission(pMap, uPermMap, "platform.tenant.provision");
        registerPermission(pMap, uPermMap, "platform.tenant.deprovision");
        registerPermission(pMap, uPermMap, "platform.featureflag.manage");
        registerPermission(pMap, uPermMap, "platform.observability.read");
        registerPermission(pMap, uPermMap, "platform.audit.read");
        registerPermission(pMap, uPermMap, "platform.support.case.read");
        registerPermission(pMap, uPermMap, "platform.support.tenant.read");
        registerPermission(pMap, uPermMap, "platform.emergency_access.execute");

        PERMISSION_TO_UUID = Collections.unmodifiableMap(pMap);
        UUID_TO_PERMISSION = Collections.unmodifiableMap(uPermMap);
    }

    private static void registerPermission(Map<String, UUID> pMap, Map<UUID, String> uPermMap, String perm) {
        UUID id = deterministicUUID("devflow.permission." + perm);
        pMap.put(perm, id);
        uPermMap.put(id, perm);
    }

    /**
     * Generates a stable UUID v5 name-based UUID using SHA-1.
     *
     * @param name input string
     * @return deterministic UUID
     */
    public static UUID deterministicUUID(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return uuidV5(NAMESPACE, name.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID uuidV5(UUID namespace, byte[] nameBytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            long msb = namespace.getMostSignificantBits();
            long lsb = namespace.getLeastSignificantBits();
            byte[] nsBytes = new byte[16];
            for (int i = 0; i < 8; i++) {
                nsBytes[i] = (byte) ((msb >> (8 * (7 - i))) & 0xFF);
                nsBytes[8 + i] = (byte) ((lsb >> (8 * (7 - i))) & 0xFF);
            }
            md.update(nsBytes);
            md.update(nameBytes);
            byte[] hash = md.digest();

            long rawMsb = 0;
            long rawLsb = 0;
            for (int i = 0; i < 8; i++) {
                rawMsb = (rawMsb << 8) | (hash[i] & 0xFF);
                rawLsb = (rawLsb << 8) | (hash[8 + i] & 0xFF);
            }

            // Set version to 5 (0101)
            rawMsb = (rawMsb & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000005000L;
            // Set variant to IETF (10xx)
            rawLsb = (rawLsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;

            return new UUID(rawMsb, rawLsb);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private CanonicalAuthorizationCatalog() {
    }

    /**
     * Returns the stable UUID identifier for the given canonical role.
     *
     * @param role the canonical role
     * @return the stable UUID
     */
    public static UUID getRoleId(Role role) {
        Objects.requireNonNull(role, "role must not be null");
        UUID id = ROLE_TO_UUID.get(role);
        if (id == null) {
            throw new IllegalArgumentException("Unknown role: " + role);
        }
        return id;
    }

    /**
     * Resolves a canonical role from its stable UUID identifier.
     *
     * @param roleId the role UUID
     * @return the Role, or null if not found
     */
    public static Role getRoleById(UUID roleId) {
        return UUID_TO_ROLE.get(roleId);
    }

    /**
     * Returns the stable UUID identifier for the given canonical permission.
     *
     * @param permission the canonical permission
     * @return the stable UUID
     */
    public static UUID getPermissionId(Permission permission) {
        Objects.requireNonNull(permission, "permission must not be null");
        return getPermissionId(permission.value());
    }

    /**
     * Returns the stable UUID identifier for the given permission string.
     *
     * @param permissionString the permission value (e.g. "project.read")
     * @return the stable UUID
     */
    public static UUID getPermissionId(String permissionString) {
        Objects.requireNonNull(permissionString, "permissionString must not be null");
        UUID id = PERMISSION_TO_UUID.get(permissionString);
        if (id == null) {
            throw new IllegalArgumentException("Unknown permission: " + permissionString);
        }
        return id;
    }
}
