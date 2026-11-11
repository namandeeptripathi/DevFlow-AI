package com.devflow.security.authorization.cache;

import com.devflow.modules.auth.domain.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Stage 9: Redis Authorization Cache Reliability & Recovery Tests")
class RedisAuthorizationCacheHardeningTest {

    private AuthorizationCacheProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new AuthorizationCacheProperties();
        properties.setEnabled(true);
        properties.setTtlSeconds(300);
        properties.setKeyPrefix("devflow:auth:test:");
        properties.setMaxLocalEntries(50);
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void cleanUpTxSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("Healthy Redis miss does not fall back to local cache and avoids split-brain mirroring")
    void healthyRedisMissDoesNotServeLocalCache() {
        Map<String, String> redisBackingStore = new ConcurrentHashMap<>();
        StringRedisTemplate redisTemplate = mockRedisBackedByMap(redisBackingStore, new AtomicBoolean(true));
        RedisAuthorizationCacheService service = new RedisAuthorizationCacheService(redisTemplate, properties, objectMapper);

        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Populate cache while Redis is healthy -> writes to Redis
        service.putOrganizationRoles(orgId, userId, Set.of(Role.ORGANIZATION_OWNER));
        assertThat(service.getOrganizationRoles(orgId, userId)).contains(Set.of(Role.ORGANIZATION_OWNER));

        // Simulate external invalidation (e.g. from another pod) directly in Redis
        redisBackingStore.clear();

        // Healthy Redis returns null -> must return empty so DB is queried, never stale local cache
        assertThat(service.getOrganizationRoles(orgId, userId)).isEmpty();
    }

    @Test
    @DisplayName("Failed Redis evictions during outage are replayed cleanly on recovery before serving Redis reads")
    void outageEvictionsReplayedUponRedisRecovery() {
        Map<String, String> redisBackingStore = new ConcurrentHashMap<>();
        AtomicBoolean redisUp = new AtomicBoolean(true);
        StringRedisTemplate redisTemplate = mockRedisBackedByMap(redisBackingStore, redisUp);
        RedisAuthorizationCacheService service = new RedisAuthorizationCacheService(redisTemplate, properties, objectMapper);

        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // 1. Healthy cache populated with OWNER role in Redis
        service.putOrganizationRoles(orgId, userId, Set.of(Role.ORGANIZATION_OWNER));
        assertThat(redisBackingStore).isNotEmpty();

        // 2. Redis goes offline; role is revoked -> eviction fails on Redis and queues pending invalidation
        redisUp.set(false);
        service.evictUser(orgId, userId);

        // Local cache must be evicted immediately during outage so fail-closed DB lookup occurs
        assertThat(service.getOrganizationRoles(orgId, userId)).isEmpty();
        // Redis backing store still contains stale pre-outage OWNER entry
        assertThat(redisBackingStore).isNotEmpty();

        // 3. Redis recovers -> next read must replay pending evictions BEFORE reading Redis!
        redisUp.set(true);
        Optional<Set<Role>> rolesAfterRecovery = service.getOrganizationRoles(orgId, userId);

        assertThat(rolesAfterRecovery).isEmpty();
        assertThat(redisBackingStore).isEmpty();
    }

    @Test
    @DisplayName("Wildcard eviction uses non-blocking cursor SCAN instead of blocking KEYS")
    void evictionUsesScanInsteadOfBlockingKeys() {
        Map<String, String> redisBackingStore = new ConcurrentHashMap<>();
        StringRedisTemplate redisTemplate = mockRedisBackedByMap(redisBackingStore, new AtomicBoolean(true));
        RedisAuthorizationCacheService service = new RedisAuthorizationCacheService(redisTemplate, properties, objectMapper);

        UUID orgId = UUID.randomUUID();
        UUID proj1 = UUID.randomUUID();
        UUID proj2 = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        service.putOrganizationRoles(orgId, userId, Set.of(Role.ORGANIZATION_MEMBER));
        service.putProjectRoles(orgId, proj1, userId, Set.of(Role.PROJECT_LEAD));
        service.putProjectRoles(orgId, proj2, userId, Set.of(Role.PROJECT_MEMBER));
        assertThat(redisBackingStore).hasSize(3);

        service.evictUser(orgId, userId);

        assertThat(redisBackingStore).isEmpty();
        verify(redisTemplate, never()).keys(anyString());
        verify(redisTemplate, atLeastOnce()).scan(any(ScanOptions.class));
    }

    @Test
    @DisplayName("Local fallback cache enforces maxLocalEntries bound during Redis outage")
    void localCacheEnforcesMaxLocalEntries() {
        properties.setMaxLocalEntries(5);
        RedisAuthorizationCacheService service = new RedisAuthorizationCacheService(null, properties, objectMapper);

        UUID orgId = UUID.randomUUID();
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID userId = UUID.randomUUID();
            users.add(userId);
            service.putOrganizationRoles(orgId, userId, Set.of(Role.ORGANIZATION_MEMBER));
        }

        int hits = 0;
        for (UUID userId : users) {
            if (service.getOrganizationRoles(orgId, userId).isPresent()) {
                hits++;
            }
        }
        assertThat(hits).isLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("Eviction inside an active transaction re-evicts on afterCommit to close pre-commit race")
    void transactionEvictionReplaysAfterCommit() {
        Map<String, String> redisBackingStore = new ConcurrentHashMap<>();
        StringRedisTemplate redisTemplate = mockRedisBackedByMap(redisBackingStore, new AtomicBoolean(true));
        RedisAuthorizationCacheService service = new RedisAuthorizationCacheService(redisTemplate, properties, objectMapper);

        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        service.putOrganizationRoles(orgId, userId, Set.of(Role.ORGANIZATION_ADMIN));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.evictUser(orgId, userId);

        // Immediate eviction happened inside TX
        assertThat(service.getOrganizationRoles(orgId, userId)).isEmpty();

        // Simulate concurrent reader repopulating stale entry before TX commit
        service.putOrganizationRoles(orgId, userId, Set.of(Role.ORGANIZATION_ADMIN));
        assertThat(service.getOrganizationRoles(orgId, userId)).isPresent();

        // TX commits -> afterCommit synchronization evicts stale entry
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThat(service.getOrganizationRoles(orgId, userId)).isEmpty();
        verify(redisTemplate, times(2)).scan(any(ScanOptions.class));
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate mockRedisBackedByMap(Map<String, String> store, AtomicBoolean healthy) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(template.opsForValue()).thenReturn(valueOps);

        when(valueOps.get(anyString())).thenAnswer(inv -> {
            if (!healthy.get()) {
                throw new RedisConnectionFailureException("Simulated Redis outage");
            }
            return store.get(inv.<String>getArgument(0));
        });

        doAnswer(inv -> {
            if (!healthy.get()) {
                throw new RedisConnectionFailureException("Simulated Redis outage");
            }
            String key = inv.getArgument(0);
            String val = inv.getArgument(1);
            Duration ttl = inv.getArgument(2);
            if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                store.put(key, val);
            }
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        when(template.delete(any(Collection.class))).thenAnswer(inv -> {
            if (!healthy.get()) {
                throw new RedisConnectionFailureException("Simulated Redis outage");
            }
            Collection<String> keys = inv.getArgument(0);
            long count = 0;
            for (String k : keys) {
                if (store.remove(k) != null) {
                    count++;
                }
            }
            return count;
        });

        when(template.delete(anyString())).thenAnswer(inv -> {
            if (!healthy.get()) {
                throw new RedisConnectionFailureException("Simulated Redis outage");
            }
            return store.remove(inv.<String>getArgument(0)) != null;
        });

        when(template.scan(any(ScanOptions.class))).thenAnswer(inv -> {
            if (!healthy.get()) {
                throw new RedisConnectionFailureException("Simulated Redis outage");
            }
            ScanOptions options = inv.getArgument(0);
            String pattern = options.getPattern() != null ? options.getPattern() : "*";
            String regex = "^" + pattern.replace(".", "\\.").replace("*", ".*") + "$";
            List<String> matched = store.keySet().stream().filter(k -> k.matches(regex)).toList();
            return new InMemoryCursor(matched);
        });

        return template;
    }

    private static final class InMemoryCursor implements Cursor<String> {
        private final Iterator<String> delegate;
        private long position = 0;
        private boolean closed = false;

        private InMemoryCursor(List<String> items) {
            this.delegate = items.iterator();
        }

        @Override
        public boolean hasNext() {
            return !closed && delegate.hasNext();
        }

        @Override
        public String next() {
            position++;
            return delegate.next();
        }

        @Override
        public long getCursorId() {
            return 0;
        }

        @Override
        public CursorId getId() {
            return CursorId.initial();
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public long getPosition() {
            return position;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}

