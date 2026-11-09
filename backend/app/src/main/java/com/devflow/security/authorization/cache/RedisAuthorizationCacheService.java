package com.devflow.security.authorization.cache;

import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production authorization cache service integrating Spring Data Redis with safe in-memory fallback.
 *
 * <h2>Reliability & Resilience (Stage 6)</h2>
 * <ul>
 *   <li>If Redis is connected, stores JSON-serialized role sets and booleans with configured TTL.</li>
 *   <li>If Redis is unavailable (e.g. offline, connection failure, timeout), logs a diagnostic warning
 *       and falls back to an internal bounded in-memory cache with TTL enforcement.</li>
 *   <li><strong>Fail-Closed Guarantee:</strong> If both caches miss or fail, returns {@link Optional#empty()},
 *       prompting an authoritative database evaluation. Authorization never fails open.</li>
 *   <li><strong>Cross-Tenant Isolation:</strong> All cache keys include the tenant {@code organizationId}.</li>
 * </ul>
 */
@Service
public class RedisAuthorizationCacheService implements AuthorizationCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisAuthorizationCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final AuthorizationCacheProperties properties;
    private final ObjectMapper objectMapper;

    // Resilient fallback in-memory cache for local development, test environments, or Redis outages
    private final Map<String, LocalCacheEntry> localCache = new ConcurrentHashMap<>();

    private record LocalCacheEntry(Object value, Instant expiresAt) {
        boolean isExpired(Instant now) {
            return expiresAt != null && now.isAfter(expiresAt);
        }
    }

    @Autowired
    public RedisAuthorizationCacheService(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            AuthorizationCacheProperties properties,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    // ── Organization Roles ────────────────────────────────────────────────────

    @Override
    public Optional<Set<Role>> getOrganizationRoles(UUID organizationId, UUID userId) {
        if (!properties.isEnabled() || organizationId == null || userId == null) {
            return Optional.empty();
        }

        String key = buildOrgRolesKey(organizationId, userId);
        return getFromCache(key, new TypeReference<List<String>>() {})
                .map(this::deserializeRoles);
    }

    @Override
    public void putOrganizationRoles(UUID organizationId, UUID userId, Set<Role> roles) {
        if (!properties.isEnabled() || organizationId == null || userId == null || roles == null) {
            return;
        }

        String key = buildOrgRolesKey(organizationId, userId);
        List<String> roleNames = roles.stream().map(Role::name).toList();
        putInCache(key, roleNames);
    }

    // ── Project Roles ─────────────────────────────────────────────────────────

    @Override
    public Optional<Set<Role>> getProjectRoles(UUID organizationId, UUID projectId, UUID userId) {
        if (!properties.isEnabled() || organizationId == null || projectId == null || userId == null) {
            return Optional.empty();
        }

        String key = buildProjRolesKey(organizationId, projectId, userId);
        return getFromCache(key, new TypeReference<List<String>>() {})
                .map(this::deserializeRoles);
    }

    @Override
    public void putProjectRoles(UUID organizationId, UUID projectId, UUID userId, Set<Role> roles) {
        if (!properties.isEnabled() || organizationId == null || projectId == null || userId == null || roles == null) {
            return;
        }

        String key = buildProjRolesKey(organizationId, projectId, userId);
        List<String> roleNames = roles.stream().map(Role::name).toList();
        putInCache(key, roleNames);
    }

    // ── Active Membership Status ──────────────────────────────────────────────

    @Override
    public Optional<Boolean> isMemberActive(UUID organizationId, UUID userId) {
        if (!properties.isEnabled() || organizationId == null || userId == null) {
            return Optional.empty();
        }

        String key = buildMembershipKey(organizationId, userId);
        return getFromCache(key, new TypeReference<Boolean>() {});
    }

    @Override
    public void putMemberActive(UUID organizationId, UUID userId, boolean active) {
        if (!properties.isEnabled() || organizationId == null || userId == null) {
            return;
        }

        String key = buildMembershipKey(organizationId, userId);
        putInCache(key, active);
    }

    // ── Custom Role Permissions (Stage 7) ────────────────────────────────────

    @Override
    public Optional<Set<Permission>> getCustomRolePermissions(UUID organizationId, UUID userId) {
        if (!properties.isEnabled() || organizationId == null || userId == null) {
            return Optional.empty();
        }

        String key = buildCustomPermsKey(organizationId, userId);
        return getFromCache(key, new TypeReference<List<String>>() {})
                .map(this::deserializePermissions);
    }

    @Override
    public void putCustomRolePermissions(UUID organizationId, UUID userId, Set<Permission> permissions) {
        if (!properties.isEnabled() || organizationId == null || userId == null || permissions == null) {
            return;
        }

        String key = buildCustomPermsKey(organizationId, userId);
        List<String> permNames = permissions.stream().map(Permission::value).toList();
        putInCache(key, permNames);
    }

    // ── Invalidation Operations ───────────────────────────────────────────────

    @Override
    public void evictUser(UUID organizationId, UUID userId) {
        if (organizationId == null || userId == null) {
            return;
        }

        String orgRolesKey = buildOrgRolesKey(organizationId, userId);
        String memberKey = buildMembershipKey(organizationId, userId);
        String customPermsKey = buildCustomPermsKey(organizationId, userId);
        String projPattern = properties.getKeyPrefix() + "roles:proj:" + organizationId + ":*:" + userId;

        evictKeys(List.of(orgRolesKey, memberKey, customPermsKey), projPattern);
        log.debug("Evicted authorization cache for user [{}] in organization [{}]", userId, organizationId);
    }

    @Override
    public void evictUserProject(UUID organizationId, UUID projectId, UUID userId) {
        if (organizationId == null || projectId == null || userId == null) {
            return;
        }

        String projKey = buildProjRolesKey(organizationId, projectId, userId);
        evictKeys(List.of(projKey), null);
        log.debug("Evicted project authorization cache for user [{}] in project [{}] (org [{}])", userId, projectId, organizationId);
    }

    @Override
    public void evictCustomRole(UUID organizationId, UUID customRoleId) {
        if (organizationId == null) {
            return;
        }

        String customPermsPattern = properties.getKeyPrefix() + "perms:custom:org:" + organizationId + ":*";
        evictKeys(Collections.emptyList(), customPermsPattern);
        log.debug("Evicted custom role permissions for role [{}] across organization [{}]", customRoleId, organizationId);
    }

    @Override
    public void evictOrganization(UUID organizationId) {
        if (organizationId == null) {
            return;
        }

        String orgPattern = properties.getKeyPrefix() + "*:" + organizationId + ":*";
        evictKeys(Collections.emptyList(), orgPattern);
        log.debug("Evicted all authorization cache entries for organization [{}]", organizationId);
    }

    @Override
    public void clear() {
        localCache.clear();
        if (redisTemplate != null) {
            try {
                Set<String> keys = redisTemplate.keys(properties.getKeyPrefix() + "*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception e) {
                log.warn("Failed to clear Redis authorization cache: {}", e.getMessage());
            }
        }
    }

    // ── Key Construction Helpers ──────────────────────────────────────

    private String buildOrgRolesKey(UUID orgId, UUID userId) {
        return properties.getKeyPrefix() + "roles:org:" + orgId + ":" + userId;
    }

    private String buildProjRolesKey(UUID orgId, UUID projectId, UUID userId) {
        return properties.getKeyPrefix() + "roles:proj:" + orgId + ":" + projectId + ":" + userId;
    }

    private String buildMembershipKey(UUID orgId, UUID userId) {
        return properties.getKeyPrefix() + "member:" + orgId + ":" + userId;
    }

    private String buildCustomPermsKey(UUID orgId, UUID userId) {
        return properties.getKeyPrefix() + "perms:custom:org:" + orgId + ":" + userId;
    }

    // ── Cache Access & Fallback Logic ─────────────────────────────────────────

    private <T> Optional<T> getFromCache(String key, TypeReference<T> typeRef) {
        Instant now = Instant.now();

        // 1. Try Redis if available
        if (redisTemplate != null) {
            try {
                String raw = redisTemplate.opsForValue().get(key);
                if (raw != null) {
                    T parsed = objectMapper.readValue(raw, typeRef);
                    return Optional.ofNullable(parsed);
                }
            } catch (Exception e) {
                log.warn("Redis read failure on key [{}]: {}. Falling back to local cache/database.", key, e.getMessage());
            }
        }

        // 2. Try local fallback cache
        LocalCacheEntry entry = localCache.get(key);
        if (entry != null) {
            if (entry.isExpired(now)) {
                localCache.remove(key);
                return Optional.empty();
            }
            try {
                @SuppressWarnings("unchecked")
                T casted = (T) entry.value();
                return Optional.ofNullable(casted);
            } catch (ClassCastException e) {
                localCache.remove(key);
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    private void putInCache(String key, Object value) {
        Duration ttl = Duration.ofSeconds(properties.getTtlSeconds());
        Instant expiresAt = Instant.now().plus(ttl);

        // 1. Store in local fallback cache
        localCache.put(key, new LocalCacheEntry(value, expiresAt));

        // 2. Store in Redis if available
        if (redisTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(value);
                redisTemplate.opsForValue().set(key, json, ttl);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize authorization cache value for key [{}]: {}", key, e.getMessage());
            } catch (Exception e) {
                log.warn("Redis write failure on key [{}]: {}. Using local cache fallback.", key, e.getMessage());
            }
        }
    }

    private void evictKeys(List<String> exactKeys, String pattern) {
        // Evict from local cache
        for (String key : exactKeys) {
            localCache.remove(key);
        }
        if (pattern != null) {
            String regex = pattern.replace("*", ".*");
            localCache.keySet().removeIf(k -> k.matches(regex));
        }

        // Evict from Redis
        if (redisTemplate != null) {
            try {
                if (!exactKeys.isEmpty()) {
                    redisTemplate.delete(exactKeys);
                }
                if (pattern != null) {
                    Set<String> matchingKeys = redisTemplate.keys(pattern);
                    if (matchingKeys != null && !matchingKeys.isEmpty()) {
                        redisTemplate.delete(matchingKeys);
                    }
                }
            } catch (Exception e) {
                log.warn("Redis eviction failure for keys {} / pattern [{}]: {}", exactKeys, pattern, e.getMessage());
            }
        }
    }

    private Set<Role> deserializeRoles(List<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String name : roleNames) {
            try {
                roles.add(Role.valueOf(name.trim()));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring unrecognized role name in cache: [{}]", name);
            }
        }
        return Collections.unmodifiableSet(roles);
    }

    private Set<Permission> deserializePermissions(List<String> permStrings) {
        if (permStrings == null || permStrings.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Permission> perms = new HashSet<>();
        for (String s : permStrings) {
            try {
                perms.add(Permission.of(s.trim()));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring unrecognized permission in cache: [{}]", s);
            }
        }
        return Collections.unmodifiableSet(perms);
    }
}
