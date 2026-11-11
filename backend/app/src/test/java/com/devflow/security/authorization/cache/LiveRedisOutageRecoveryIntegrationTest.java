package com.devflow.security.authorization.cache;

import com.devflow.modules.auth.api.AuthApi;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.service.RoleAssignmentService;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolated live-Redis outage, recovery, pending-eviction replay, and stale-L1 protection
 * integration test (Stage 9 Phase 3, Scenarios 9, 10, 11, 12, and 15).
 *
 * <p>Spawns a dedicated ephemeral {@code redis-server} subprocess on an unused localhost port so
 * terminating and restarting Redis does not disrupt the shared suite Redis instance.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Stage 9 Phase 3: Live Redis Outage, Recovery, Replay & Stale-L1 Tests")
class LiveRedisOutageRecoveryIntegrationTest {

    private static final int EPHEMERAL_PORT = allocateFreePort();
    private static final Path TEMP_REDIS_DIR = createTempRedisDir();
    private static volatile Process redisProcess;

    @DynamicPropertySource
    static void configureEphemeralRedis(DynamicPropertyRegistry registry) throws Exception {
        ensureRedisStarted();
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> EPHEMERAL_PORT);
        registry.add("spring.data.redis.timeout", () -> "1s");
    }

    @Autowired
    private AuthApi authApi;

    @Autowired
    private AuthorizationCacheService cacheService;

    @Autowired
    private AuthorizationCacheProperties cacheProperties;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdOrgIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdUserIds = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void ensureRedisHealthyAndCleanDb() throws Exception {
        ensureRedisStarted();
        for (UUID orgId : createdOrgIds) {
            cacheService.evictOrganization(orgId);
            String orgIdStr = orgId.toString();
            jdbcTemplate.update("DELETE FROM organization_role_assignments WHERE organization_id = ?", orgIdStr);
            jdbcTemplate.update("DELETE FROM organization_members WHERE organization_id = ?", orgIdStr);
            jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", orgIdStr);
        }
        createdOrgIds.clear();

        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId.toString());
        }
        createdUserIds.clear();
    }

    @AfterAll
    static void tearDownEphemeralRedis() throws IOException {
        stopRedisProcess();
        if (Files.exists(TEMP_REDIS_DIR)) {
            try (var stream = Files.walk(TEMP_REDIS_DIR)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("Scenarios 9-12: Redis outage falls back fail-safely to DB, queues revocations, and replays evictions against stale recovered Redis")
    void redisOutageFailSafeEvaluationRecoveryAndPendingEvictionReplay() throws Exception {
        User owner = createUser("outage-owner");
        User billingUser = createUser("outage-billing");
        Organization org = createOrganization(owner);
        addMembership(org, billingUser, OrganizationRole.DEVELOPER);
        roleAssignmentService.assignOrganizationRole(
                org.getId(), billingUser.getId(), Role.ORGANIZATION_BILLING, owner.getId(), null);

        String orgRolesKey = cacheProperties.getKeyPrefix() + "roles:org:" + org.getId() + ":" + billingUser.getId();

        // 1. Warm live Redis and snapshot RDB so stale key survives simulated server outage/restart
        assertThat(authApi.isAuthorized(billingUser.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE))
                .isTrue();
        assertThat(stringRedisTemplate.opsForValue().get(orgRolesKey)).contains("ORGANIZATION_BILLING");

        stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().save();
            return null;
        });

        // 2. Scenario 9: Stop Redis (SHUTDOWN NOSAVE) mid-operation without deleting dump.rdb
        shutdownRedisNoSaveAndWaitDown();

        // 3. Scenario 10: Authorization succeeds against DB for still-active billingUser,
        // and revoking a role during Redis outage must fail closed immediately without relying on stale cache
        assertThat(authApi.isAuthorized(billingUser.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE))
                .isTrue();

        roleAssignmentService.revokeOrganizationRole(
                org.getId(), billingUser.getId(), Role.ORGANIZATION_BILLING, owner.getId(), "Revoked during Redis outage");

        assertThat(authApi.isAuthorized(billingUser.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE))
                .isFalse();

        // 4. Scenarios 11 & 12: Restart Redis reloading stale dump.rdb containing ORGANIZATION_BILLING
        ensureRedisStarted();
        waitForAppRedisConnectionHealthy();
        String rawStaleValueBeforeReplay = rawRedisGet(orgRolesKey);
        assertThat(rawStaleValueBeforeReplay)
                .as("Restarted Redis must initially contain the stale pre-outage RDB entry prior to replay")
                .contains("ORGANIZATION_BILLING");

        // Trigger AuthApi / cache lookup -> replayPendingRedisEvictionsIfNeeded() deletes stale key BEFORE read
        assertThat(authApi.isAuthorized(billingUser.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE))
                .isFalse();

        String rawValueAfterReplay = rawRedisGet(orgRolesKey);
        if (rawValueAfterReplay != null) {
            assertThat(rawValueAfterReplay).doesNotContain("ORGANIZATION_BILLING");
        }
        assertThat(cacheService.getOrganizationRoles(org.getId(), billingUser.getId())
                .map(roles -> roles.contains(Role.ORGANIZATION_BILLING))
                .orElse(false))
                .isFalse();
    }

    @Test
    @DisplayName("Scenario 15: Healthy Redis cache miss never returns stale local fallback authorization data")
    void healthyRedisMissNeverReturnsStaleLocalAuthorizationData() throws Exception {
        User owner = createUser("l1-owner");
        User target = createUser("l1-target");
        Organization org = createOrganization(owner);
        addMembership(org, target, OrganizationRole.DEVELOPER);
        roleAssignmentService.assignOrganizationRole(
                org.getId(), target.getId(), Role.ORGANIZATION_BILLING, owner.getId(), null);

        // 1. Outage read evaluates true from DB and populates fallback localCache with ORGANIZATION_BILLING
        shutdownRedisNoSaveAndWaitDown();
        assertThat(authApi.isAuthorized(target.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE))
                .isTrue();

        // 2. Simulate out-of-band DB revocation (no cacheService.evictUser call on this JVM) + wipe RDB
        int revokedRows = jdbcTemplate.update(
                "UPDATE organization_role_assignments SET revoked_at = NOW(), revocation_reason = 'Out-of-band revocation' "
                        + "WHERE organization_id = ? AND user_id = ? AND revoked_at IS NULL",
                org.getId().toString(),
                target.getId().toString());
        assertThat(revokedRows).isGreaterThanOrEqualTo(1);
        Files.deleteIfExists(TEMP_REDIS_DIR.resolve("dump.rdb"));

        // 3. Restart Redis completely empty and verify app's Lettuce client is healthy (cache miss, localCache still stale)
        ensureRedisStarted();
        waitForAppRedisConnectionHealthy();
        String orgRolesKey = cacheProperties.getKeyPrefix() + "roles:org:" + org.getId() + ":" + target.getId();
        assertThat(rawRedisGet(orgRolesKey)).isNull();

        // Healthy Redis miss must bypass stale localCache and query DB -> DENY
        assertThat(authApi.isAuthorized(target.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE))
                .isFalse();
    }

    private void waitForAppRedisConnectionHealthy() throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            try {
                String pong = stringRedisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
                if ("PONG".equalsIgnoreCase(pong)) {
                    return;
                }
            } catch (Exception ignored) {
                // Waiting for Lettuce ConnectionWatchdog exponential backoff reconnect after server restart
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Application StringRedisTemplate did not reconnect to restarted Redis within 15s");
    }

    private static synchronized void ensureRedisStarted() throws Exception {
        if (redisProcess != null && redisProcess.isAlive() && isRedisResponding()) {
            return;
        }
        String binary = resolveRedisBinary();
        List<String> cmd = List.of(
                binary,
                "--port", String.valueOf(EPHEMERAL_PORT),
                "--bind", "127.0.0.1",
                "--dir", TEMP_REDIS_DIR.toAbsolutePath().toString(),
                "--dbfilename", "dump.rdb",
                "--save", "",
                "--appendonly", "no",
                "--maxmemory", "128mb",
                "--maxmemory-policy", "allkeys-lru"
        );
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(TEMP_REDIS_DIR.resolve("redis-server.log").toFile());
        redisProcess = pb.start();

        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (!redisProcess.isAlive()) {
                String logOutput = Files.exists(TEMP_REDIS_DIR.resolve("redis-server.log"))
                        ? Files.readString(TEMP_REDIS_DIR.resolve("redis-server.log"))
                        : "";
                throw new IllegalStateException("Ephemeral redis-server exited prematurely: " + logOutput);
            }
            if (isRedisResponding()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Timed out waiting for ephemeral redis-server on port " + EPHEMERAL_PORT);
    }

    private static void shutdownRedisNoSaveAndWaitDown() throws Exception {
        if (redisProcess == null || !redisProcess.isAlive()) {
            return;
        }
        try (Socket socket = new Socket("127.0.0.1", EPHEMERAL_PORT)) {
            socket.setSoTimeout(1500);
            OutputStream out = socket.getOutputStream();
            out.write("*2\r\n$8\r\nSHUTDOWN\r\n$6\r\nNOSAVE\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            redisProcess.destroy();
        }
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (redisProcess.isAlive() && System.nanoTime() < deadlineNanos) {
            Thread.sleep(50);
        }
        if (redisProcess.isAlive()) {
            redisProcess.destroyForcibly();
        }
        long closeDeadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (isRedisResponding() && System.nanoTime() < closeDeadline) {
            Thread.sleep(50);
        }
        assertThat(isRedisResponding()).isFalse();
    }

    private static void stopRedisProcess() {
        try {
            shutdownRedisNoSaveAndWaitDown();
        } catch (Exception e) {
            if (redisProcess != null && redisProcess.isAlive()) {
                redisProcess.destroyForcibly();
            }
        }
    }

    private static boolean isRedisResponding() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", EPHEMERAL_PORT), 250);
            socket.setSoTimeout(250);
            socket.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            return "+PONG".equalsIgnoreCase(line);
        } catch (IOException e) {
            return false;
        }
    }

    private static String rawRedisGet(String key) throws IOException {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        String header = "*2\r\n$3\r\nGET\r\n$" + keyBytes.length + "\r\n";
        try (Socket socket = new Socket("127.0.0.1", EPHEMERAL_PORT)) {
            socket.setSoTimeout(1500);
            OutputStream out = socket.getOutputStream();
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(keyBytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String lengthLine = reader.readLine();
            if (lengthLine == null || lengthLine.equals("$-1")) {
                return null;
            }
            if (!lengthLine.startsWith("$")) {
                throw new IllegalStateException("Unexpected RESP reply for GET: " + lengthLine);
            }
            int len = Integer.parseInt(lengthLine.substring(1));
            char[] buf = new char[len];
            int totalRead = 0;
            while (totalRead < len) {
                int r = reader.read(buf, totalRead, len - totalRead);
                if (r < 0) {
                    throw new IOException("Unexpected EOF reading RESP bulk string");
                }
                totalRead += r;
            }
            return new String(buf);
        }
    }

    private static String resolveRedisBinary() {
        for (String candidate : List.of("/opt/homebrew/bin/redis-server", "/usr/local/bin/redis-server", "/usr/bin/redis-server")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "redis-server";
    }

    private static int allocateFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to allocate free localhost port for Redis test", e);
        }
    }

    private static Path createTempRedisDir() {
        try {
            return Files.createTempDirectory("devflow-stage9-redis-outage-");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create temp Redis data dir", e);
        }
    }

    private User createUser(String prefix) {
        User user = userRepository.saveAndFlush(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@devflow.io")
                .username((prefix + "_" + UUID.randomUUID().toString().substring(0, 8)).replace('-', '_'))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());
        createdUserIds.add(user.getId());
        return user;
    }

    private Organization createOrganization(User owner) {
        Organization org = organizationRepository.saveAndFlush(Organization.builder()
                .name("Outage Redis Org " + UUID.randomUUID().toString().substring(0, 6))
                .slug("outage-redis-" + UUID.randomUUID())
                .owner(owner)
                .build());
        createdOrgIds.add(org.getId());
        addMembership(org, owner, OrganizationRole.OWNER);
        return org;
    }

    private void addMembership(Organization org, User user, OrganizationRole role) {
        organizationMemberRepository.saveAndFlush(OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(role)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());
    }
}

