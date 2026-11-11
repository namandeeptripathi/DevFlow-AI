package com.devflow.security.authorization.cache;

import com.devflow.modules.auth.api.AuthApi;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.organization.service.OrganizationMembershipService;
import com.devflow.role.domain.CustomRoleEntity;
import com.devflow.role.dto.CreateCustomRoleRequest;
import com.devflow.role.dto.UpdateCustomRoleRequest;
import com.devflow.role.service.CustomRoleService;
import com.devflow.role.service.RoleAssignmentService;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live Redis integration tests covering real {@link RedisAuthorizationCacheService}
 * behavior against a running Redis server (Stage 9 Phase 3, Scenarios 1-8 and 13-15).
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Stage 9 Phase 3: Live Redis Authorization Cache Integration Tests")
class LiveRedisAuthorizationCacheIntegrationTest {

    @Autowired
    private AuthApi authApi;

    @Autowired
    private AuthorizationCacheService cacheService;

    @Autowired
    private AuthorizationCacheProperties cacheProperties;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private CustomRoleService customRoleService;

    @Autowired
    private OrganizationMembershipService membershipService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdOrgIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdUserIds = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void verifyLiveRedisConnectionAndReset() {
        assertThat(cacheService)
                .as("Test context must wire real RedisAuthorizationCacheService")
                .isInstanceOf(RedisAuthorizationCacheService.class);

        String pong = stringRedisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
        assertThat(pong)
                .as("Live Redis server must respond with PONG")
                .isEqualToIgnoringCase("PONG");

        cacheService.clear();
    }

    @AfterEach
    void cleanUpDatabaseAndRedis() {
        for (UUID orgId : createdOrgIds) {
            cacheService.evictOrganization(orgId);
            String orgIdStr = orgId.toString();
            jdbcTemplate.update("DELETE FROM custom_role_assignments WHERE organization_id = ?", orgIdStr);
            jdbcTemplate.update(
                    "DELETE FROM custom_role_permissions WHERE custom_role_id IN (SELECT id FROM custom_roles WHERE organization_id = ?)",
                    orgIdStr);
            jdbcTemplate.update("DELETE FROM custom_roles WHERE organization_id = ?", orgIdStr);
            jdbcTemplate.update("DELETE FROM project_role_assignments WHERE organization_id = ?", orgIdStr);
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

    @Test
    @DisplayName("Scenarios 1 & 2: Cache miss populates real Redis keys and subsequent read hits live Redis")
    void cacheMissPopulateAndHitAgainstLiveRedis() {
        User owner = createUser("redis-s1-owner");
        User billingUser = createUser("redis-s1-billing");
        Organization org = createOrganization(owner, OrganizationRole.OWNER);
        addMembership(org, billingUser, OrganizationRole.DEVELOPER);
        roleAssignmentService.assignOrganizationRole(
                org.getId(), billingUser.getId(), Role.ORGANIZATION_BILLING, owner.getId(), null);

        String prefix = cacheProperties.getKeyPrefix();
        String orgRolesKey = prefix + "roles:org:" + org.getId() + ":" + billingUser.getId();
        String memberKey = prefix + "member:" + org.getId() + ":" + billingUser.getId();
        String customPermsKey = prefix + "perms:custom:org:" + org.getId() + ":" + billingUser.getId();

        // Scenario 1: Miss before first authorization evaluation
        assertThat(stringRedisTemplate.hasKey(orgRolesKey)).isFalse();
        assertThat(cacheService.getOrganizationRoles(org.getId(), billingUser.getId())).isEmpty();

        boolean allowed = authApi.isAuthorized(billingUser.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE);
        assertThat(allowed).isTrue();

        // Live Redis must now store cached org roles, membership status, and custom perms
        assertThat(stringRedisTemplate.hasKey(orgRolesKey)).isTrue();
        assertThat(stringRedisTemplate.hasKey(memberKey)).isTrue();
        assertThat(stringRedisTemplate.hasKey(customPermsKey)).isTrue();
        assertThat(stringRedisTemplate.opsForValue().get(orgRolesKey)).contains("ORGANIZATION_BILLING");

        // Scenario 2: Subsequent cache lookups hit live Redis directly
        assertThat(cacheService.getOrganizationRoles(org.getId(), billingUser.getId()))
                .isPresent()
                .contains(Set.of(Role.ORGANIZATION_BILLING));
        assertThat(cacheService.isMemberActive(org.getId(), billingUser.getId()))
                .contains(Boolean.TRUE);
        assertThat(authApi.isAuthorized(billingUser.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE))
                .isTrue();
    }

    @Test
    @DisplayName("Scenario 3: Redis TTL expiration evicts cached authorization state cleanly")
    void ttlExpirationEvictsRedisEntryAndCausesCleanMiss() throws Exception {
        AuthorizationCacheProperties shortProps = new AuthorizationCacheProperties();
        shortProps.setEnabled(true);
        shortProps.setTtlSeconds(1L);
        shortProps.setKeyPrefix("devflow:auth:ttl:" + UUID.randomUUID() + ":");
        shortProps.setMaxLocalEntries(100);

        RedisAuthorizationCacheService shortTtlCache =
                new RedisAuthorizationCacheService(stringRedisTemplate, shortProps, objectMapper);

        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String rawKey = shortProps.getKeyPrefix() + "roles:org:" + orgId + ":" + userId;

        shortTtlCache.putOrganizationRoles(orgId, userId, Set.of(Role.ORGANIZATION_OWNER));

        assertThat(stringRedisTemplate.hasKey(rawKey)).isTrue();
        Long expireSeconds = stringRedisTemplate.getExpire(rawKey, TimeUnit.SECONDS);
        assertThat(expireSeconds).isNotNull().isBetween(0L, 2L);
        assertThat(shortTtlCache.getOrganizationRoles(orgId, userId))
                .contains(Set.of(Role.ORGANIZATION_OWNER));

        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (Boolean.TRUE.equals(stringRedisTemplate.hasKey(rawKey)) && System.nanoTime() < deadlineNanos) {
            Thread.sleep(150);
        }

        assertThat(stringRedisTemplate.hasKey(rawKey)).isFalse();
        assertThat(shortTtlCache.getOrganizationRoles(orgId, userId)).isEmpty();
    }

    @Test
    @DisplayName("Scenarios 4 & 5: Organization role grant and org/project role revocations invalidate live Redis keys")
    void roleGrantAndRevokeInvalidateLiveRedisKeys() {
        User owner = createUser("redis-s4-owner");
        User dev = createUser("redis-s4-dev");
        Organization org = createOrganization(owner, OrganizationRole.OWNER);
        addMembership(org, dev, OrganizationRole.DEVELOPER);
        roleAssignmentService.assignOrganizationRole(
                org.getId(), dev.getId(), Role.ORGANIZATION_MEMBER, owner.getId(), null);

        UUID projectId = UUID.randomUUID();
        String prefix = cacheProperties.getKeyPrefix();
        String orgRolesKey = prefix + "roles:org:" + org.getId() + ":" + dev.getId();
        String projRolesKey = prefix + "roles:proj:" + org.getId() + ":" + projectId + ":" + dev.getId();

        // Populate initial org roles cache with non-billing role -> DENY
        assertThat(authApi.isAuthorized(dev.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE)).isFalse();
        assertThat(stringRedisTemplate.hasKey(orgRolesKey)).isTrue();

        // Scenario 4: Grant ORGANIZATION_BILLING -> afterCommit evicts live Redis key -> immediately ALLOW
        roleAssignmentService.assignOrganizationRole(
                org.getId(), dev.getId(), Role.ORGANIZATION_BILLING, owner.getId(), null);
        assertThat(stringRedisTemplate.hasKey(orgRolesKey)).isFalse();
        assertThat(authApi.isAuthorized(dev.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE)).isTrue();
        assertThat(stringRedisTemplate.opsForValue().get(orgRolesKey)).contains("ORGANIZATION_BILLING");

        // Scenario 5a: Revoke ORGANIZATION_BILLING -> evicts live Redis key -> immediately DENY
        roleAssignmentService.revokeOrganizationRole(
                org.getId(), dev.getId(), Role.ORGANIZATION_BILLING, owner.getId(), "Revocation test");
        assertThat(stringRedisTemplate.hasKey(orgRolesKey)).isFalse();
        assertThat(authApi.isAuthorized(dev.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE)).isFalse();

        // Scenario 5b: Project role grant and revocation invalidates project-scoped Redis cache key
        roleAssignmentService.assignProjectRole(
                org.getId(), projectId, dev.getId(), Role.PROJECT_LEAD, owner.getId(), null);
        assertThat(authApi.isAuthorized(dev.getId(), org.getId(), projectId, PermissionRegistry.TASK_DELETE))
                .isTrue();
        assertThat(stringRedisTemplate.hasKey(projRolesKey)).isTrue();
        assertThat(stringRedisTemplate.opsForValue().get(projRolesKey)).contains("PROJECT_LEAD");

        roleAssignmentService.revokeProjectRole(
                org.getId(), projectId, dev.getId(), Role.PROJECT_LEAD, owner.getId(), "Project revocation test");
        assertThat(stringRedisTemplate.hasKey(projRolesKey)).isFalse();
        assertThat(authApi.isAuthorized(dev.getId(), org.getId(), projectId, PermissionRegistry.TASK_DELETE))
                .isFalse();
    }

    @Test
    @DisplayName("Scenario 6: Custom-role permission update invalidates custom permission keys in live Redis")
    void customRolePermissionUpdateInvalidatesLiveRedisKeys() {
        User owner = createUser("redis-s6-owner");
        User dev = createUser("redis-s6-dev");
        Organization org = createOrganization(owner, OrganizationRole.OWNER);
        addMembership(org, dev, OrganizationRole.VIEWER);
        roleAssignmentService.assignOrganizationRole(
                org.getId(), dev.getId(), Role.ORGANIZATION_VIEWER, owner.getId(), null);

        CustomRoleEntity customRole = customRoleService.createCustomRole(
                org.getId(),
                owner.getId(),
                CreateCustomRoleRequest.builder()
                        .name("RELEASE_OPS")
                        .displayName("Release Ops")
                        .permissions(Set.of("project.read"))
                        .build()
        );
        roleAssignmentService.assignCustomRole(org.getId(), dev.getId(), customRole.getId(), owner.getId(), null);

        String customPermsKey = cacheProperties.getKeyPrefix() + "perms:custom:org:" + org.getId() + ":" + dev.getId();

        assertThat(authApi.isAuthorized(dev.getId(), org.getId(), PermissionRegistry.TASK_WRITE)).isFalse();
        assertThat(stringRedisTemplate.hasKey(customPermsKey)).isTrue();

        // Expand custom role permissions -> evictCustomRole wipes Redis key -> immediate ALLOW
        customRoleService.updateCustomRole(
                org.getId(),
                customRole.getId(),
                owner.getId(),
                UpdateCustomRoleRequest.builder()
                        .permissions(Set.of("project.read", "task.write"))
                        .build()
        );
        assertThat(stringRedisTemplate.hasKey(customPermsKey)).isFalse();
        assertThat(authApi.isAuthorized(dev.getId(), org.getId(), PermissionRegistry.TASK_WRITE)).isTrue();
        assertThat(stringRedisTemplate.opsForValue().get(customPermsKey)).contains("task.write");

        // Shrink custom role permissions -> immediate DENY
        customRoleService.updateCustomRole(
                org.getId(),
                customRole.getId(),
                owner.getId(),
                UpdateCustomRoleRequest.builder()
                        .permissions(Set.of("project.read"))
                        .build()
        );
        assertThat(stringRedisTemplate.hasKey(customPermsKey)).isFalse();
        assertThat(authApi.isAuthorized(dev.getId(), org.getId(), PermissionRegistry.TASK_WRITE)).isFalse();
    }

    @Test
    @DisplayName("Scenario 7: Membership removal invalidates org, project, membership, and custom-role Redis keys")
    void membershipRemovalInvalidatesAllLiveRedisKeysForUser() {
        User owner = createUser("redis-s7-owner");
        User dev = createUser("redis-s7-dev");
        Organization org = createOrganization(owner, OrganizationRole.OWNER);
        addMembership(org, dev, OrganizationRole.DEVELOPER);

        UUID projectId = UUID.randomUUID();
        roleAssignmentService.assignOrganizationRole(
                org.getId(), dev.getId(), Role.ORGANIZATION_BILLING, owner.getId(), null);
        roleAssignmentService.assignProjectRole(
                org.getId(), projectId, dev.getId(), Role.PROJECT_LEAD, owner.getId(), null);

        assertThat(authApi.isAuthorized(dev.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE)).isTrue();
        assertThat(authApi.isAuthorized(dev.getId(), org.getId(), projectId, PermissionRegistry.TASK_DELETE))
                .isTrue();

        String prefix = cacheProperties.getKeyPrefix();
        String orgRolesKey = prefix + "roles:org:" + org.getId() + ":" + dev.getId();
        String projRolesKey = prefix + "roles:proj:" + org.getId() + ":" + projectId + ":" + dev.getId();
        String memberKey = prefix + "member:" + org.getId() + ":" + dev.getId();
        String customPermsKey = prefix + "perms:custom:org:" + org.getId() + ":" + dev.getId();

        assertThat(stringRedisTemplate.hasKey(orgRolesKey)).isTrue();
        assertThat(stringRedisTemplate.hasKey(projRolesKey)).isTrue();
        assertThat(stringRedisTemplate.hasKey(memberKey)).isTrue();
        assertThat(stringRedisTemplate.hasKey(customPermsKey)).isTrue();

        membershipService.removeMember(org.getId(), dev.getId(), owner.getId());

        assertThat(stringRedisTemplate.hasKey(orgRolesKey)).isFalse();
        assertThat(stringRedisTemplate.hasKey(projRolesKey)).isFalse();
        assertThat(stringRedisTemplate.hasKey(memberKey)).isFalse();
        assertThat(stringRedisTemplate.hasKey(customPermsKey)).isFalse();

        assertThat(authApi.isAuthorized(dev.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE)).isFalse();
        assertThat(authApi.isAuthorized(dev.getId(), org.getId(), projectId, PermissionRegistry.TASK_DELETE))
                .isFalse();
    }

    @Test
    @DisplayName("Scenario 8: Cache keys and invalidations remain strictly isolated per tenant organization")
    void tenantIsolationOfCacheKeysAcrossOrganizations() {
        User ownerA = createUser("redis-s8-ownA");
        User ownerB = createUser("redis-s8-ownB");
        User sharedUser = createUser("redis-s8-shared");

        Organization orgA = createOrganization(ownerA, OrganizationRole.OWNER);
        Organization orgB = createOrganization(ownerB, OrganizationRole.OWNER);
        addMembership(orgA, sharedUser, OrganizationRole.DEVELOPER);
        addMembership(orgB, sharedUser, OrganizationRole.VIEWER);

        roleAssignmentService.assignOrganizationRole(
                orgA.getId(), sharedUser.getId(), Role.ORGANIZATION_BILLING, ownerA.getId(), null);
        roleAssignmentService.assignOrganizationRole(
                orgB.getId(), sharedUser.getId(), Role.ORGANIZATION_VIEWER, ownerB.getId(), null);

        assertThat(authApi.isAuthorized(sharedUser.getId(), orgA.getId(), PermissionRegistry.BILLING_MANAGE)).isTrue();
        assertThat(authApi.isAuthorized(sharedUser.getId(), orgB.getId(), PermissionRegistry.BILLING_MANAGE)).isFalse();

        String prefix = cacheProperties.getKeyPrefix();
        String orgAKey = prefix + "roles:org:" + orgA.getId() + ":" + sharedUser.getId();
        String orgBKey = prefix + "roles:org:" + orgB.getId() + ":" + sharedUser.getId();

        assertThat(stringRedisTemplate.hasKey(orgAKey)).isTrue();
        assertThat(stringRedisTemplate.hasKey(orgBKey)).isTrue();
        assertThat(stringRedisTemplate.opsForValue().get(orgAKey)).contains("ORGANIZATION_BILLING");
        assertThat(stringRedisTemplate.opsForValue().get(orgBKey)).contains("ORGANIZATION_VIEWER");

        // Evicting Org A must not delete Org B's cached roles
        cacheService.evictOrganization(orgA.getId());
        assertThat(stringRedisTemplate.hasKey(orgAKey)).isFalse();
        assertThat(stringRedisTemplate.hasKey(orgBKey)).isTrue();
        assertThat(cacheService.getOrganizationRoles(orgB.getId(), sharedUser.getId()))
                .contains(Set.of(Role.ORGANIZATION_VIEWER));
    }

    @Test
    @DisplayName("Scenario 13: Non-blocking cursor SCAN deletes >1,200 tenant keys across multiple cursor batches")
    void scanBasedInvalidationDeletesOver1200TenantKeysAcrossBatches() {
        UUID targetOrgId = UUID.randomUUID();
        UUID untouchedOrgId = UUID.randomUUID();

        for (int i = 0; i < 1_250; i++) {
            cacheService.putOrganizationRoles(targetOrgId, UUID.randomUUID(), Set.of(Role.ORGANIZATION_MEMBER));
        }
        for (int i = 0; i < 10; i++) {
            cacheService.putOrganizationRoles(untouchedOrgId, UUID.randomUUID(), Set.of(Role.ORGANIZATION_VIEWER));
        }

        try {
            assertThat(countKeysMatching(cacheProperties.getKeyPrefix() + "*" + targetOrgId + "*"))
                    .isEqualTo(1_250);
            assertThat(countKeysMatching(cacheProperties.getKeyPrefix() + "*" + untouchedOrgId + "*"))
                    .isEqualTo(10);

            cacheService.evictOrganization(targetOrgId);

            assertThat(countKeysMatching(cacheProperties.getKeyPrefix() + "*" + targetOrgId + "*"))
                    .isZero();
            assertThat(countKeysMatching(cacheProperties.getKeyPrefix() + "*" + untouchedOrgId + "*"))
                    .isEqualTo(10);
        } finally {
            cacheService.evictOrganization(targetOrgId);
            cacheService.evictOrganization(untouchedOrgId);
        }
    }

    @Test
    @DisplayName("Scenario 14: Concurrent live-Redis reads and invalidations remain exception-free and consistent")
    void concurrentCacheReadsAndInvalidationsMaintainConsistency() throws Exception {
        User owner = createUser("redis-s14-owner");
        User target = createUser("redis-s14-target");
        Organization org = createOrganization(owner, OrganizationRole.OWNER);
        addMembership(org, target, OrganizationRole.DEVELOPER);
        roleAssignmentService.assignOrganizationRole(
                org.getId(), target.getId(), Role.ORGANIZATION_BILLING, owner.getId(), null);

        int threads = 6;
        int opsPerThread = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger readCount = new AtomicInteger();
        AtomicInteger evictCount = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final boolean isReader = (t % 2 == 0);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        if (isReader) {
                            boolean allowed = authApi.isAuthorized(
                                    target.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE);
                            assertThat(allowed).isTrue();
                            readCount.incrementAndGet();
                        } else {
                            cacheService.evictUser(org.getId(), target.getId());
                            evictCount.incrementAndGet();
                        }
                    }
                } catch (Throwable ex) {
                    errors.add(ex);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(errors).isEmpty();
        assertThat(readCount.get()).isEqualTo(3 * opsPerThread);
        assertThat(evictCount.get()).isEqualTo(3 * opsPerThread);

        // Final revoke settles cleanly to DENY on both Redis and DB
        roleAssignmentService.revokeOrganizationRole(
                org.getId(), target.getId(), Role.ORGANIZATION_BILLING, owner.getId(), "Post-concurrency check");
        assertThat(authApi.isAuthorized(target.getId(), org.getId(), PermissionRegistry.BILLING_MANAGE)).isFalse();
    }

    private int countKeysMatching(String pattern) {
        Set<String> matched = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();
        stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                while (cursor.hasNext()) {
                    matched.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return null;
        });
        return matched.size();
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

    private Organization createOrganization(User owner, OrganizationRole ownerRole) {
        Organization org = organizationRepository.saveAndFlush(Organization.builder()
                .name("Live Redis Org " + UUID.randomUUID().toString().substring(0, 6))
                .slug("live-redis-" + UUID.randomUUID())
                .owner(owner)
                .build());
        createdOrgIds.add(org.getId());
        addMembership(org, owner, ownerRole);
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

