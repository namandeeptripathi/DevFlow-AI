package com.devflow.security;

import com.devflow.audit.domain.AuditIntegrityCheckpointEntity;
import com.devflow.audit.domain.AuthorizationAuditEventEntity;
import com.devflow.audit.dto.AuditIntegrityVerificationResponse;
import com.devflow.audit.repository.AuditIntegrityCheckpointRepository;
import com.devflow.audit.repository.AuthorizationAuditEventRepository;
import com.devflow.audit.service.AuditIntegrityService;
import com.devflow.audit.service.AuthorizationAuditService;
import com.devflow.modules.auth.api.AuthApi;
import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.modules.auth.audit.AuthorizationAuditEventType;
import com.devflow.modules.auth.audit.AuthorizationAuditRecorder;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.domain.RoleScope;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.modules.auth.registry.RolePermissionMapping;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.exception.LastOrganizationOwnerRemovalException;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.organization.service.OrganizationMembershipService;
import com.devflow.role.domain.RoleEntity;
import com.devflow.role.exception.InvalidRoleAssignmentException;
import com.devflow.role.exception.RoleAssignmentAccessDeniedException;
import com.devflow.role.exception.RoleAssignmentAlreadyExistsException;
import com.devflow.role.exception.RoleAssignmentNotFoundException;
import com.devflow.role.repository.OrganizationRoleAssignmentRepository;
import com.devflow.role.repository.RoleRepository;
import com.devflow.role.service.RoleAssignmentService;
import com.devflow.security.authorization.cache.AuthorizationCacheService;
import com.devflow.security.monitoring.domain.SecurityAlertEntity;
import com.devflow.security.monitoring.domain.SecurityEventEntity;
import com.devflow.security.monitoring.domain.SecuritySeverity;
import com.devflow.security.monitoring.repository.SecurityAlertRepository;
import com.devflow.security.monitoring.repository.SecurityEventRepository;
import com.devflow.security.monitoring.service.SecurityAlertService;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Stage 9: Concurrency Races, Audit Integrity, Triggers & Flyway Readiness Tests")
class Stage9ConcurrencyAndAuditIntegrityIntegrationTest {

    @Autowired
    private OrganizationMembershipService membershipService;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private AuthorizationAuditService auditService;

    @Autowired
    private AuditIntegrityService integrityService;

    @Autowired
    private SecurityAlertService alertService;

    @Autowired
    private AuthorizationCacheService cacheService;

    @Autowired
    private AuthorizationAuditEventRepository auditEventRepository;

    @Autowired
    private AuditIntegrityCheckpointRepository checkpointRepository;

    @Autowired
    private SecurityAlertRepository securityAlertRepository;

    @Autowired
    private SecurityEventRepository securityEventRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AuthApi authApi;

    @Autowired
    private AuthorizationAuditRecorder authorizationAuditRecorder;

    @Autowired
    private OrganizationRoleAssignmentRepository organizationRoleAssignmentRepository;

    @Autowired
    private RoleRepository roleRepository;

    private final List<UUID> createdOrgIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdUserIds = Collections.synchronizedList(new ArrayList<>());
    private final List<String> createdAlertIds = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void clearCaches() {
        cacheService.clear();
    }

    @AfterEach
    void cleanUpCommittedRows() {
        // Note: audit events & checkpoints are append-only via DB triggers, so we isolate each test by random org UUID.
        for (String alertId : createdAlertIds) {
            jdbcTemplate.update("DELETE FROM security_alerts WHERE id = ?", alertId);
        }
        createdAlertIds.clear();

        for (UUID orgId : createdOrgIds) {
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

    @Test
    @DisplayName("Concurrent removal of the two active owners guarantees one succeeds and at least one owner survives")
    void concurrentOwnerRemovalPreservesLastActiveOwner() throws Exception {
        User owner1 = createCommittedUser("c-own1");
        User owner2 = createCommittedUser("c-own2");
        Organization org = createCommittedOrg(owner1, owner2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    membershipService.removeMember(org.getId(), owner1.getId(), owner2.getId());
                    successes.incrementAndGet();
                } catch (LastOrganizationOwnerRemovalException expected) {
                    rejections.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    done.countDown();
                }
            });

            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    membershipService.removeMember(org.getId(), owner2.getId(), owner1.getId());
                    successes.incrementAndGet();
                } catch (LastOrganizationOwnerRemovalException expected) {
                    rejections.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    done.countDown();
                }
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpectedErrors).isEmpty();
        assertThat(successes.get()).isEqualTo(1);
        assertThat(rejections.get()).isEqualTo(1);

        long remainingOwners = organizationMemberRepository.countByOrganizationIdAndRoleAndStatus(
                org.getId(), OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);
        assertThat(remainingOwners).isEqualTo(1L);
    }

    @Test
    @DisplayName("Concurrent revocation of the two active ORGANIZATION_OWNER role assignments preserves sole owner")
    void concurrentOwnerRoleRevocationPreservesLastOwner() throws Exception {
        User owner1 = createCommittedUser("c-rev1");
        User owner2 = createCommittedUser("c-rev2");
        Organization org = createCommittedOrg(owner1, owner2);

        roleAssignmentService.assignOrganizationRole(org.getId(), owner1.getId(), Role.ORGANIZATION_OWNER, owner1.getId(), null);
        roleAssignmentService.assignOrganizationRole(org.getId(), owner2.getId(), Role.ORGANIZATION_OWNER, owner1.getId(), null);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    roleAssignmentService.revokeOrganizationRole(
                            org.getId(), owner1.getId(), Role.ORGANIZATION_OWNER, owner2.getId(), "Concurrent test 1");
                    successes.incrementAndGet();
                } catch (RoleAssignmentAccessDeniedException expected) {
                    rejections.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    done.countDown();
                }
            });

            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    roleAssignmentService.revokeOrganizationRole(
                            org.getId(), owner2.getId(), Role.ORGANIZATION_OWNER, owner1.getId(), "Concurrent test 2");
                    successes.incrementAndGet();
                } catch (RoleAssignmentAccessDeniedException expected) {
                    rejections.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    done.countDown();
                }
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpectedErrors).isEmpty();
        assertThat(successes.get()).isEqualTo(1);
        assertThat(rejections.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent multi-thread audit persistence against PostgreSQL builds gapless valid hash chain and supports idempotent retry")
    void concurrentAuditPersistenceCreatesValidHashChain() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        int threads = 4;
        int perThread = 15;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int worker = t;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        for (int i = 0; i < perThread; i++) {
                            AuthorizationAuditEvent evt = AuthorizationAuditEvent.builder()
                                    .eventType(AuthorizationAuditEventType.AUTHORIZATION_ALLOWED)
                                    .userId(userId)
                                    .scope(RoleScope.ORGANIZATION)
                                    .organizationId(orgId)
                                    .resourceType("project")
                                    .resourceId("p-" + worker + "-" + i)
                                    .decision(AuthorizationDecision.allow())
                                    .build();
                            txTemplate.executeWithoutResult(status -> auditService.persistAuditEvent(evt));
                        }
                    } catch (Throwable ex) {
                        errors.add(ex);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors).isEmpty();

        List<AuthorizationAuditEventEntity> persisted =
                auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(orgId.toString());
        assertThat(persisted).hasSize(threads * perThread);
        for (int idx = 0; idx < persisted.size(); idx++) {
            assertThat(persisted.get(idx).getSequenceNumber()).isEqualTo((long) idx + 1);
        }

        // Create checkpoint and verify full integrity
        AuditIntegrityCheckpointEntity checkpoint = integrityService.createCheckpoint(orgId, userId.toString(), "ON_DEMAND");
        assertThat(checkpoint).isNotNull();
        assertThat(checkpoint.getEndSequence()).isEqualTo(60L);

        AuditIntegrityVerificationResponse verified = integrityService.verifyIntegrity(orgId, null, null);
        assertThat(verified.isValid()).isTrue();
        assertThat(verified.isTampered()).isFalse();
        assertThat(verified.getTotalRecordsVerified()).isEqualTo(60L);
        assertThat(verified.getCheckpointsVerified()).isEqualTo(1);

        // Idempotent retry on duplicate eventId must not advance sequence or duplicate record
        UUID duplicateId = UUID.fromString(persisted.get(0).getEventId());
        AuthorizationAuditEvent retryEvent = new AuthorizationAuditEvent(
                duplicateId,
                Instant.now(),
                AuthorizationAuditEventType.AUTHORIZATION_ALLOWED,
                userId,
                RoleScope.ORGANIZATION,
                orgId,
                null,
                "project",
                "retry",
                Set.of(PermissionRegistry.PROJECT_READ),
                AuthorizationDecision.allow(),
                null,
                "retry-cid",
                null, null, null, null, null
        );
        AuthorizationAuditEventEntity retried = auditService.persistAuditEvent(retryEvent);
        assertThat(retried.getSequenceNumber()).isEqualTo(1L);
        assertThat(auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(orgId.toString())).hasSize(60);

        // Query nullable PostgreSQL JPQL/Criteria helpers cleanly with all-null optional filters
        assertThat(auditEventRepository.findTenantAuditLogs(
                orgId.toString(), null, null, null, null, null, null, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(60L);
        assertThat(auditEventRepository.findRecentEvidenceEventIds(
                orgId.toString(), userId.toString(), null, Instant.now().minusSeconds(60), PageRequest.of(0, 10)))
                .hasSize(10);

        // SQL triggers prevent UPDATE/DELETE on audit events & checkpoints
        String sampleEventId = persisted.get(0).getId();
        String checkpointId = checkpoint.getId();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE authorization_audit_events SET decision = 'DENY' WHERE id = ?", sampleEventId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM authorization_audit_events WHERE id = ?", sampleEventId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE audit_integrity_checkpoints SET record_count = 999 WHERE id = ?", checkpointId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM audit_integrity_checkpoints WHERE id = ?", checkpointId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("Concurrent security events deduplicate cleanly under PostgreSQL advisory lock into a single alert")
    void concurrentSecurityEventsDeduplicateIntoSingleAlert() throws Exception {
        String orgId = UUID.randomUUID().toString();
        String actorId = UUID.randomUUID().toString();
        int threads = 4;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        SecurityEventEntity secEvent = SecurityEventEntity.builder()
                                .id(UUID.randomUUID().toString())
                                .organizationId(orgId)
                                .ruleId("AUTHZ_REPEATED_DENIALS")
                                .ruleName("Repeated Authorization Denials")
                                .severity(SecuritySeverity.MEDIUM)
                                .title("Repeated Denials")
                                .description("Concurrent burst event " + idx)
                                .actorId(actorId)
                                .detectedAt(Instant.now())
                                .build();
                        SecurityAlertEntity alert = alertService.processSecurityEvent(secEvent);
                        createdAlertIds.add(alert.getId());
                    } catch (Throwable ex) {
                        errors.add(ex);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors).isEmpty();

        List<SecurityAlertEntity> alerts = securityAlertRepository
                .findTenantSecurityAlerts(orgId, null, null, null, null, PageRequest.of(0, 20))
                .getContent();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getOccurrenceCount()).isEqualTo(threads);

        assertThat(securityEventRepository.findTenantSecurityEvents(
                orgId, null, null, null, null, PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    @Test
    @DisplayName("Audit integrity verifier detects head deletion, tail truncation, full wipe against checkpoint, and forged SHA-256 rewrites")
    void integrityServiceDetectsAllTamperAndTruncationVectors() {
        AuthorizationAuditEventRepository mockAuditRepo = mock(AuthorizationAuditEventRepository.class);
        AuditIntegrityCheckpointRepository mockCpRepo = mock(AuditIntegrityCheckpointRepository.class);
        AuditIntegrityService localVerifier = new AuditIntegrityService(
                mockAuditRepo, mockCpRepo, "stage9-verifier-test-secret-key-at-least-32bytes");

        UUID orgId = UUID.randomUUID();
        List<AuthorizationAuditEventEntity> validChain = buildValidChain(localVerifier, orgId.toString(), 5);
        String tailHash = validChain.get(4).getRecordHash();
        AuditIntegrityCheckpointEntity cp = AuditIntegrityCheckpointEntity.builder()
                .id("cp-1")
                .organizationId(orgId.toString())
                .checkpointType("ON_DEMAND")
                .startSequence(1L)
                .endSequence(5L)
                .recordCount(5L)
                .cumulativeHash(tailHash)
                .hmacSignature(localVerifier.computeCheckpointHmac(orgId.toString(), 1L, 5L, 5L, tailHash))
                .createdAt(Instant.now())
                .build();

        // 1. Head deletion: seq 1 removed, records start at seq 2
        when(mockAuditRepo.findByOrganizationIdOrderBySequenceNumberAsc(orgId.toString()))
                .thenReturn(validChain.subList(1, 5));
        AuditIntegrityVerificationResponse headDeleted = localVerifier.verifyIntegrity(orgId, null, null);
        assertThat(headDeleted.isValid()).isFalse();
        assertThat(headDeleted.isTampered()).isTrue();

        // 2. Empty ledger while checkpoint exists
        when(mockAuditRepo.findByOrganizationIdOrderBySequenceNumberAsc(orgId.toString()))
                .thenReturn(List.of());
        when(mockCpRepo.findTopByOrganizationIdOrderByEndSequenceDesc(orgId.toString()))
                .thenReturn(Optional.of(cp));
        AuditIntegrityVerificationResponse wipedLedger = localVerifier.verifyIntegrity(orgId, null, null);
        assertThat(wipedLedger.isValid()).isFalse();
        assertThat(wipedLedger.getTamperDetails()).contains("Audit records missing");

        // 3. Tail truncation: records 1..3 remain while checkpoint ends at 5
        when(mockAuditRepo.findByOrganizationIdOrderBySequenceNumberAsc(orgId.toString()))
                .thenReturn(validChain.subList(0, 3));
        AuditIntegrityVerificationResponse tailTruncated = localVerifier.verifyIntegrity(orgId, null, null);
        assertThat(tailTruncated.isValid()).isFalse();
        assertThat(tailTruncated.getTamperDetails()).contains("truncated");

        // 4. Forged SHA-256 rewrite of entire chain while keeping old HMAC-signed checkpoint
        List<AuthorizationAuditEventEntity> rewritten = buildValidChain(localVerifier, orgId.toString(), 5);
        when(mockAuditRepo.findByOrganizationIdOrderBySequenceNumberAsc(orgId.toString()))
                .thenReturn(rewritten);
        when(mockCpRepo.findByOrganizationIdAndStartSequenceGreaterThanEqualAndEndSequenceLessThanEqualOrderByEndSequenceAsc(
                orgId.toString(), 1L, 5L)).thenReturn(List.of(cp));
        AuditIntegrityVerificationResponse rewrittenResp = localVerifier.verifyIntegrity(orgId, null, null);
        assertThat(rewrittenResp.isValid()).isFalse();
        assertThat(rewrittenResp.getTamperDetails()).contains("cumulative hash mismatch");
    }

    @Test
    @DisplayName("Flyway V1..V16 schema history, canonical role/permission seeds (10/48), and unique sequence indexes are consistent")
    void validatesFlywaySchemaHistoryAndSeedConsistency() {
        Integer failedMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertThat(failedMigrations).isZero();

        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
                String.class);
        assertThat(versions).contains("13", "14", "15", "16");

        Integer roleCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM roles", Integer.class);
        Integer permCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM permissions", Integer.class);
        assertThat(roleCount).isEqualTo(Role.values().length); // 10 canonical roles
        assertThat(permCount).isEqualTo(PermissionRegistry.canonical().size()); // 48 canonical permissions

        Set<String> indexes = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename IN ('authorization_audit_events', 'audit_integrity_checkpoints')",
                String.class));
        assertThat(indexes).contains("uk_authz_audit_org_seq", "uk_authz_audit_platform_seq", "uk_audit_checkpoint_org_range");
    }

    private User createCommittedUser(String prefix) {
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

    private Organization createCommittedOrg(User owner1, User owner2) {
        Organization org = organizationRepository.saveAndFlush(Organization.builder()
                .name("Concurrency Org " + UUID.randomUUID().toString().substring(0, 6))
                .slug("conc-" + UUID.randomUUID())
                .owner(owner1)
                .build());
        createdOrgIds.add(org.getId());

        organizationMemberRepository.saveAndFlush(OrganizationMember.builder()
                .organization(org).user(owner1).role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE).build());
        organizationMemberRepository.saveAndFlush(OrganizationMember.builder()
                .organization(org).user(owner2).role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE).build());
        return org;
    }

    @Test
    @DisplayName("Transaction rollback prevents orphaned role mutations/audits and preserves hash chain continuity; audit failures fail closed")
    void transactionRollbackPreservesAuditContinuityAndPreventsOrphanedMutations() {
        User owner1 = createCommittedUser("rb-owner1");
        User owner2 = createCommittedUser("rb-owner2");
        User member = createCommittedUser("rb-member");
        Organization org = createCommittedOrg(owner1, owner2);
        organizationMemberRepository.saveAndFlush(OrganizationMember.builder()
                .organization(org).user(member).role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE).build());

        // 1. Baseline committed role grant establishes sequence 1
        roleAssignmentService.assignOrganizationRole(
                org.getId(), member.getId(), Role.ORGANIZATION_MEMBER, owner1.getId(), null);

        List<AuthorizationAuditEventEntity> baselineEvents = auditEventRepository
                .findByOrganizationIdOrderBySequenceNumberAsc(org.getId().toString());
        assertThat(baselineEvents).hasSize(1);
        assertThat(integrityService.verifyIntegrity(org.getId(), null, null).isValid()).isTrue();

        // 2. Downstream exception in domain transaction rolls back both role grant AND tentative ROLE_GRANTED audit row
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            roleAssignmentService.assignOrganizationRole(
                    org.getId(), member.getId(), Role.ORGANIZATION_BILLING, owner1.getId(), null);
            throw new IllegalStateException("Simulated downstream domain failure after role grant");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated downstream domain failure");

        assertThat(roleAssignmentService.getActiveOrganizationRoles(org.getId(), member.getId()))
                .doesNotContain(Role.ORGANIZATION_BILLING);
        assertThat(auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(org.getId().toString()))
                .hasSize(1);
        assertThat(authApi.isAuthorized(member.getId(), org.getId(), Permission.of("billing", "manage")))
                .isFalse();

        // 3. Subsequent committed role grant receives contiguous sequence 3 (after seq 1 grant and seq 2 authz deny)
        roleAssignmentService.assignOrganizationRole(
                org.getId(), member.getId(), Role.ORGANIZATION_BILLING, owner1.getId(), null);

        List<AuthorizationAuditEventEntity> afterRecoveryEvents = auditEventRepository
                .findByOrganizationIdOrderBySequenceNumberAsc(org.getId().toString());
        assertThat(afterRecoveryEvents).hasSize(3);
        assertThat(afterRecoveryEvents.get(0).getSequenceNumber()).isEqualTo(1L);
        assertThat(afterRecoveryEvents.get(1).getSequenceNumber()).isEqualTo(2L);
        assertThat(afterRecoveryEvents.get(2).getSequenceNumber()).isEqualTo(3L);
        assertThat(integrityService.verifyIntegrity(org.getId(), null, null).isValid()).isTrue();
        assertThat(authApi.isAuthorized(member.getId(), org.getId(), Permission.of("billing", "manage")))
                .isTrue();

        // 4. Fail-closed durable audit failure during administrative mutation rolls back role grant completely
        try {
            roleAssignmentService.setAuditRecorder(event -> {
                throw new IllegalStateException("Simulated durable audit storage failure");
            });

            assertThatThrownBy(() -> roleAssignmentService.assignOrganizationRole(
                    org.getId(), member.getId(), Role.ORGANIZATION_ADMIN, owner1.getId(), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Simulated durable audit storage failure");
        } finally {
            roleAssignmentService.setAuditRecorder(authorizationAuditRecorder);
        }

        assertThat(roleAssignmentService.getActiveOrganizationRoles(org.getId(), member.getId()))
                .doesNotContain(Role.ORGANIZATION_ADMIN);
        assertThat(auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(org.getId().toString()))
                .hasSize(4);
        assertThat(integrityService.verifyIntegrity(org.getId(), null, null).isValid()).isTrue();
    }

    @Test
    @DisplayName("Concurrent role grant and revoke race maintains DB uniqueness, AuthApi consistency, and contiguous hash chain")
    void concurrentRoleGrantAndRevokeMaintainConsistentState() throws Exception {
        User owner1 = createCommittedUser("race-owner1");
        User owner2 = createCommittedUser("race-owner2");
        User target = createCommittedUser("race-target");
        Organization org = createCommittedOrg(owner1, owner2);
        organizationMemberRepository.saveAndFlush(OrganizationMember.builder()
                .organization(org).user(target).role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE).build());

        RoleEntity billingRole = roleRepository.findByName(Role.ORGANIZATION_BILLING.name())
                .orElseThrow();

        int threadCount = 4;
        int iterationsPerThread = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger grantsSucceeded = new AtomicInteger();
        AtomicInteger revokesSucceeded = new AtomicInteger();

        for (int t = 0; t < threadCount; t++) {
            final int workerIndex = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        boolean tryGrant = ((workerIndex + i) % 2 == 0);
                        try {
                            if (tryGrant) {
                                roleAssignmentService.assignOrganizationRole(
                                        org.getId(), target.getId(), Role.ORGANIZATION_BILLING, owner1.getId(), null);
                                grantsSucceeded.incrementAndGet();
                            } else {
                                roleAssignmentService.revokeOrganizationRole(
                                        org.getId(), target.getId(), Role.ORGANIZATION_BILLING, owner1.getId(), "Concurrent race check");
                                revokesSucceeded.incrementAndGet();
                            }
                        } catch (RoleAssignmentAlreadyExistsException
                                | InvalidRoleAssignmentException
                                | RoleAssignmentNotFoundException expectedRace) {
                            // Expected under serialized concurrent grant-while-active or revoke-while-inactive contention
                        }
                    }
                } catch (Throwable ex) {
                    unexpectedErrors.add(ex);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(unexpectedErrors).isEmpty();
        assertThat(grantsSucceeded.get()).isGreaterThan(0);

        boolean activeAfterRace = organizationRoleAssignmentRepository.hasActiveAssignment(
                org.getId(), target.getId(), billingRole.getId(), Instant.now());
        assertThat(roleAssignmentService.getActiveOrganizationRoles(org.getId(), target.getId())
                .contains(Role.ORGANIZATION_BILLING)).isEqualTo(activeAfterRace);
        assertThat(authApi.isAuthorized(target.getId(), org.getId(), Permission.of("billing", "manage")))
                .isEqualTo(activeAfterRace);

        // Deterministic post-race reconciliation: end active -> check ALLOW, then revoke -> check DENY
        if (activeAfterRace) {
            roleAssignmentService.revokeOrganizationRole(
                    org.getId(), target.getId(), Role.ORGANIZATION_BILLING, owner1.getId(), "Post-race reset");
            revokesSucceeded.incrementAndGet();
            assertThat(organizationRoleAssignmentRepository.hasActiveAssignment(
                    org.getId(), target.getId(), billingRole.getId(), Instant.now())).isFalse();
        }
        assertThat(authApi.isAuthorized(target.getId(), org.getId(), Permission.of("billing", "manage")))
                .isFalse();

        roleAssignmentService.assignOrganizationRole(
                org.getId(), target.getId(), Role.ORGANIZATION_BILLING, owner1.getId(), null);
        assertThat(organizationRoleAssignmentRepository.hasActiveAssignment(
                org.getId(), target.getId(), billingRole.getId(), Instant.now())).isTrue();
        assertThat(authApi.isAuthorized(target.getId(), org.getId(), Permission.of("billing", "manage")))
                .isTrue();

        roleAssignmentService.revokeOrganizationRole(
                org.getId(), target.getId(), Role.ORGANIZATION_BILLING, owner1.getId(), "Post-race verify deny");
        assertThat(organizationRoleAssignmentRepository.hasActiveAssignment(
                org.getId(), target.getId(), billingRole.getId(), Instant.now())).isFalse();
        assertThat(authApi.isAuthorized(target.getId(), org.getId(), Permission.of("billing", "manage")))
                .isFalse();

        AuditIntegrityVerificationResponse verification = integrityService.verifyIntegrity(org.getId(), null, null);
        assertThat(verification.isValid()).isTrue();
        assertThat(verification.getTotalRecordsVerified()).isGreaterThanOrEqualTo(grantsSucceeded.get() + revokesSucceeded.get());
    }

    private List<AuthorizationAuditEventEntity> buildValidChain(AuditIntegrityService svc, String orgId, int count) {
        List<AuthorizationAuditEventEntity> list = new ArrayList<>();
        String prev = AuditIntegrityService.GENESIS_HASH;
        Instant base = Instant.parse("2026-11-11T00:00:00Z");
        for (int i = 1; i <= count; i++) {
            String eventId = UUID.randomUUID().toString();
            Instant ts = base.plusSeconds(i * 10L);
            String hash = svc.computeRecordHash(
                    i, eventId, ts, "AUTHORIZATION_ALLOWED", "user-1",
                    "ORGANIZATION", orgId, null, "project", "p-1",
                    "ALLOW", null, null, null, null, null, null, prev);
            list.add(AuthorizationAuditEventEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .sequenceNumber((long) i)
                    .eventId(eventId)
                    .timestamp(ts)
                    .eventType("AUTHORIZATION_ALLOWED")
                    .userId("user-1")
                    .scope("ORGANIZATION")
                    .organizationId(orgId)
                    .resourceType("project")
                    .resourceId("p-1")
                    .decision("ALLOW")
                    .recordHash(hash)
                    .previousRecordHash(prev)
                    .build());
            prev = hash;
        }
        return list;
    }
}
