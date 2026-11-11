package com.devflow.performance;

import com.devflow.audit.service.AuthorizationAuditService;
import com.devflow.modules.auth.api.AuthApi;
import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.modules.auth.audit.AuthorizationAuditEventType;
import com.devflow.modules.auth.audit.AuthorizationAuditRecorder;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.domain.RoleScope;
import com.devflow.modules.auth.evaluator.AuthorizationEvaluator;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.modules.auth.registry.RolePermissionMapping;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.domain.CustomRoleEntity;
import com.devflow.role.dto.CreateCustomRoleRequest;
import com.devflow.role.service.CustomRoleService;
import com.devflow.role.service.RoleAssignmentService;
import com.devflow.security.authorization.cache.AuthorizationCacheService;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Stage 9: Authorization & Audit Performance Benchmark Suite")
class Stage9AuthorizationBenchmarkTest {

    @Autowired
    private AuthApi authApi;

    @Autowired
    private AuthorizationCacheService cacheService;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private CustomRoleService customRoleService;

    @Autowired
    private AuthorizationAuditService auditService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> createdOrgIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdUserIds = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void cleanup() {
        cacheService.clear();
        for (UUID orgId : createdOrgIds) {
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
    @DisplayName("Measures latency and throughput across policy engine, DB misses, cache hits, custom roles, role mutations, and durable audits")
    void runAuthorizationBenchmarks() {
        // 1. Setup test tenant in PostgreSQL
        User owner = createUser("bench-owner");
        User dev = createUser("bench-dev");
        Organization org = organizationRepository.saveAndFlush(Organization.builder()
                .name("Benchmark Org")
                .slug("bench-" + UUID.randomUUID())
                .owner(owner)
                .build());
        createdOrgIds.add(org.getId());

        organizationMemberRepository.saveAndFlush(OrganizationMember.builder()
                .organization(org).user(owner).role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE).build());
        organizationMemberRepository.saveAndFlush(OrganizationMember.builder()
                .organization(org).user(dev).role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE).build());

        roleAssignmentService.assignOrganizationRole(org.getId(), owner.getId(), Role.ORGANIZATION_OWNER, owner.getId(), null);
        roleAssignmentService.assignOrganizationRole(org.getId(), dev.getId(), Role.ORGANIZATION_MEMBER, owner.getId(), null);

        UUID projectId = UUID.randomUUID();
        roleAssignmentService.assignProjectRole(org.getId(), projectId, dev.getId(), Role.PROJECT_LEAD, owner.getId(), null);

        CustomRoleEntity customRole = customRoleService.createCustomRole(
                org.getId(),
                owner.getId(),
                CreateCustomRoleRequest.builder()
                        .name("BENCH_ROLE_" + UUID.randomUUID().toString().substring(0, 6))
                        .displayName("Benchmark Role")
                        .permissions(Set.of("repo.scan", "analytics.view", "analytics.export"))
                        .build()
        );
        roleAssignmentService.assignCustomRole(org.getId(), dev.getId(), customRole.getId(), owner.getId(), null);

        // A. Pure in-process devflow-auth AuthorizationEvaluator benchmark (isolated from DB/Redis I/O)
        AuthorizationEvaluator pureEvaluator = AuthorizationEvaluator.builder()
                .permissionRegistry(PermissionRegistry.canonical())
                .rolePermissionMapping(RolePermissionMapping.canonical())
                .roleResolver((u, c) -> c.getScope() == RoleScope.PROJECT
                        ? Set.of(Role.PROJECT_LEAD) : Set.of(Role.ORGANIZATION_MEMBER))
                .customRolePermissionResolver((u, c) -> Set.of(PermissionRegistry.REPO_SCAN))
                .membershipValidator((u, o) -> true)
                .tenantBoundaryValidator((o, p) -> true)
                .projectAssignmentValidator((u, o, p) -> true)
                .auditRecorder(AuthorizationAuditRecorder.noOp())
                .build();

        AuthorizationRequest pureOrgReq = AuthorizationRequest.organization(dev.getId(), org.getId(), PermissionRegistry.PROJECT_READ);
        AuthorizationRequest pureProjReq = AuthorizationRequest.project(dev.getId(), org.getId(), projectId, PermissionRegistry.TASK_ASSIGN);
        AuthorizationRequest pureCustomReq = AuthorizationRequest.organization(dev.getId(), org.getId(), PermissionRegistry.REPO_SCAN);

        for (int i = 0; i < 1000; i++) {
            pureEvaluator.authorize(pureOrgReq);
            pureEvaluator.authorize(pureProjReq);
            pureEvaluator.authorize(pureCustomReq);
        }

        BenchmarkStats pureOrgStats = measureNanos(5000, () -> assertAllowed(pureEvaluator.authorize(pureOrgReq)));
        BenchmarkStats pureProjStats = measureNanos(5000, () -> assertAllowed(pureEvaluator.authorize(pureProjReq)));
        BenchmarkStats pureCustomStats = measureNanos(5000, () -> assertAllowed(pureEvaluator.authorize(pureCustomReq)));

        // B. Full Spring AuthApi with cache warm hit vs cold miss (PostgreSQL)
        AuthorizationRequest orgAuthReq = AuthorizationRequest.organization(dev.getId(), org.getId(), PermissionRegistry.PROJECT_READ);
        AuthorizationRequest projAuthReq = AuthorizationRequest.project(dev.getId(), org.getId(), projectId, PermissionRegistry.TASK_ASSIGN);
        AuthorizationRequest customAuthReq = AuthorizationRequest.organization(dev.getId(), org.getId(), PermissionRegistry.REPO_SCAN);

        // Warmup DB/JPA
        for (int i = 0; i < 10; i++) {
            authApi.authorize(orgAuthReq);
            authApi.authorize(projAuthReq);
            authApi.authorize(customAuthReq);
        }

        BenchmarkStats cacheHitOrgStats = measureNanos(100, () -> assertAllowed(authApi.authorize(orgAuthReq)));
        BenchmarkStats cacheHitProjStats = measureNanos(100, () -> assertAllowed(authApi.authorize(projAuthReq)));
        BenchmarkStats cacheHitCustomStats = measureNanos(100, () -> assertAllowed(authApi.authorize(customAuthReq)));

        BenchmarkStats cacheMissOrgStats = measureNanos(50, () -> {
            cacheService.evictUser(org.getId(), dev.getId());
            assertAllowed(authApi.authorize(orgAuthReq));
        });

        // C. Role assignment & revocation lifecycle (includes PESSIMISTIC_WRITE + DB + cache invalidation + durable fail-closed audit)
        BenchmarkStats roleMutationStats = measureNanos(25, () -> {
            roleAssignmentService.assignOrganizationRole(org.getId(), dev.getId(), Role.ORGANIZATION_BILLING, owner.getId(), null);
            roleAssignmentService.revokeOrganizationRole(org.getId(), dev.getId(), Role.ORGANIZATION_BILLING, owner.getId(), "bench");
        });

        // D. Durable PostgreSQL audit event persistence (advisory lock + max seq + SHA-256 hash chain + insert + flush)
        UUID auditOrgId = UUID.randomUUID();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        BenchmarkStats auditPersistStats = measureNanos(100, () -> {
            AuthorizationAuditEvent evt = AuthorizationAuditEvent.builder()
                    .eventType(AuthorizationAuditEventType.AUTHORIZATION_ALLOWED)
                    .userId(dev.getId())
                    .scope(RoleScope.ORGANIZATION)
                    .organizationId(auditOrgId)
                    .resourceType("project")
                    .resourceId("bench")
                    .decision(AuthorizationDecision.allow())
                    .build();
            txTemplate.executeWithoutResult(status -> auditService.persistAuditEvent(evt));
        });

        System.out.println("=== STAGE 9 BENCHMARK RESULTS ===");
        System.out.println(pureOrgStats.format("PureEvaluator Organization Authz (5000 ops)"));
        System.out.println(pureProjStats.format("PureEvaluator Project Authz (5000 ops)"));
        System.out.println(pureCustomStats.format("PureEvaluator Custom Role Authz (5000 ops)"));
        System.out.println(cacheHitOrgStats.format("Full AuthApi Organization Cache Hit (100 ops)"));
        System.out.println(cacheHitProjStats.format("Full AuthApi Project Cache Hit (100 ops)"));
        System.out.println(cacheHitCustomStats.format("Full AuthApi Custom Role Cache Hit (100 ops)"));
        System.out.println(cacheMissOrgStats.format("Full AuthApi Organization Cache Miss + DB (50 ops)"));
        System.out.println(roleMutationStats.format("Role Grant+Revoke Cycle w/ Lock+Audit (25 cycles)"));
        System.out.println(auditPersistStats.format("Durable PostgreSQL Audit Persist+HashChain (100 ops)"));
        System.out.println("=================================");

        assertThat(pureOrgStats.meanMs()).isLessThan(1.0);
        assertThat(pureProjStats.meanMs()).isLessThan(1.0);
    }

    private static void assertAllowed(AuthorizationDecision decision) {
        if (!decision.allowed()) {
            throw new AssertionError("Expected ALLOW decision during benchmark but got: " + decision.denialReason());
        }
    }

    private User createUser(String prefix) {
        User u = userRepository.saveAndFlush(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@devflow.io")
                .username((prefix + "_" + UUID.randomUUID().toString().substring(0, 8)).replace('-', '_'))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());
        createdUserIds.add(u.getId());
        return u;
    }

    private BenchmarkStats measureNanos(int iterations, Runnable action) {
        long[] samples = new long[iterations];
        long totalStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            action.run();
            samples[i] = System.nanoTime() - start;
        }
        long wallNanos = System.nanoTime() - totalStart;
        Arrays.sort(samples);
        return new BenchmarkStats(iterations, wallNanos, samples);
    }

    private static final class BenchmarkStats {
        private final int count;
        private final long wallNanos;
        private final long[] sortedNanos;

        private BenchmarkStats(int count, long wallNanos, long[] sortedNanos) {
            this.count = count;
            this.wallNanos = wallNanos;
            this.sortedNanos = sortedNanos;
        }

        double meanMs() {
            double sum = 0;
            for (long n : sortedNanos) {
                sum += n;
            }
            return (sum / count) / 1_000_000.0;
        }

        double percentileMs(double p) {
            int idx = (int) Math.min(sortedNanos.length - 1, Math.max(0, Math.ceil(p * sortedNanos.length) - 1));
            return sortedNanos[idx] / 1_000_000.0;
        }

        double opsPerSec() {
            return count / (wallNanos / 1_000_000_000.0);
        }

        String format(String label) {
            return String.format(
                    "%s -> throughput=%.1f ops/s, mean=%.4f ms, p50=%.4f ms, p95=%.4f ms, p99=%.4f ms",
                    label, opsPerSec(), meanMs(), percentileMs(0.50), percentileMs(0.95), percentileMs(0.99));
        }
    }
}
