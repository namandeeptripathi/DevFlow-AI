package com.devflow.audit.service;

import com.devflow.audit.domain.AuditIntegrityCheckpointEntity;
import com.devflow.audit.domain.AuthorizationAuditEventEntity;
import com.devflow.audit.dto.AuditIntegrityVerificationResponse;
import com.devflow.audit.repository.AuditIntegrityCheckpointRepository;
import com.devflow.audit.repository.AuthorizationAuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Stage 8: Cryptographic Audit Integrity & Tamper-Evident Chain Tests")
class AuditIntegrityServiceTest {

    private static final String INTEGRITY_KEY = "test-secret-key-for-audit-integrity-verification-32bytes";
    private static final UUID ORG_ID = UUID.randomUUID();

    @Mock
    private AuthorizationAuditEventRepository auditEventRepository;

    @Mock
    private AuditIntegrityCheckpointRepository checkpointRepository;

    private AuditIntegrityService integrityService;

    @BeforeEach
    void setUp() {
        integrityService = new AuditIntegrityService(auditEventRepository, checkpointRepository, INTEGRITY_KEY);
    }

    @Test
    @DisplayName("Verify valid cryptographic hash chain with multiple sequential events")
    void shouldVerifyValidHashChain() {
        List<AuthorizationAuditEventEntity> chain = buildMockChain(5, false, false, false);
        when(auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(ORG_ID.toString()))
                .thenReturn(chain);
        when(checkpointRepository.findByOrganizationIdAndStartSequenceGreaterThanEqualAndEndSequenceLessThanEqualOrderByEndSequenceAsc(
                eq(ORG_ID.toString()), eq(1L), eq(5L)))
                .thenReturn(List.of());

        AuditIntegrityVerificationResponse response = integrityService.verifyIntegrity(ORG_ID, null, null);

        assertThat(response.isValid()).isTrue();
        assertThat(response.isTampered()).isFalse();
        assertThat(response.getTotalRecordsVerified()).isEqualTo(5);
        assertThat(response.getTamperDetails()).isNull();
        assertThat(response.getLastVerifiedHash()).isEqualTo(chain.get(4).getRecordHash());
    }

    @Test
    @DisplayName("Detect modified audit event payload (tampered field)")
    void shouldDetectModifiedRecordPayload() {
        List<AuthorizationAuditEventEntity> chain = buildMockChain(5, false, false, false);
        // Tamper with record 3: change decision from ALLOW to DENY without updating hash
        chain.get(2).setDecision("DENY");

        when(auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(ORG_ID.toString()))
                .thenReturn(chain);

        AuditIntegrityVerificationResponse response = integrityService.verifyIntegrity(ORG_ID, null, null);

        assertThat(response.isValid()).isFalse();
        assertThat(response.isTampered()).isTrue();
        assertThat(response.getTamperDetails()).contains("Hash mismatch for modified record ID");
    }

    @Test
    @DisplayName("Detect missing audit record (broken sequence number gap)")
    void shouldDetectMissingRecord() {
        List<AuthorizationAuditEventEntity> chain = buildMockChain(5, false, false, false);
        // Remove record 3 (sequence 3 missing)
        chain.remove(2);

        when(auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(ORG_ID.toString()))
                .thenReturn(chain);

        AuditIntegrityVerificationResponse response = integrityService.verifyIntegrity(ORG_ID, null, null);

        assertThat(response.isValid()).isFalse();
        assertThat(response.isTampered()).isTrue();
        assertThat(response.getTamperDetails()).contains("Sequence gap or reordering detected");
    }

    @Test
    @DisplayName("Detect reordered audit records")
    void shouldDetectReorderedRecords() {
        List<AuthorizationAuditEventEntity> chain = buildMockChain(4, false, false, false);
        // Swap records 1 and 2
        AuthorizationAuditEventEntity temp = chain.get(1);
        chain.set(1, chain.get(2));
        chain.set(2, temp);

        when(auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(ORG_ID.toString()))
                .thenReturn(chain);

        AuditIntegrityVerificationResponse response = integrityService.verifyIntegrity(ORG_ID, null, null);

        assertThat(response.isValid()).isFalse();
        assertThat(response.isTampered()).isTrue();
        assertThat(response.getTamperDetails()).contains("Sequence gap or reordering detected");
    }

    @Test
    @DisplayName("Detect broken hash chain link (modified previousRecordHash)")
    void shouldDetectBrokenHashChainLink() {
        List<AuthorizationAuditEventEntity> chain = buildMockChain(4, false, false, false);
        // Alter previousRecordHash of record 4
        chain.get(3).setPreviousRecordHash("1111111111111111111111111111111111111111111111111111111111111111");

        when(auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(ORG_ID.toString()))
                .thenReturn(chain);

        AuditIntegrityVerificationResponse response = integrityService.verifyIntegrity(ORG_ID, null, null);

        assertThat(response.isValid()).isFalse();
        assertThat(response.isTampered()).isTrue();
        assertThat(response.getTamperDetails()).contains("Broken hash chain link");
    }

    @Test
    @DisplayName("Verify checkpoint HMAC signature and cumulative hash match")
    void shouldVerifyValidCheckpoint() {
        List<AuthorizationAuditEventEntity> chain = buildMockChain(5, false, false, false);
        when(auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(ORG_ID.toString()))
                .thenReturn(chain);

        String cumulativeHash = chain.get(4).getRecordHash();
        String validHmac = integrityService.computeCheckpointHmac(ORG_ID.toString(), 1L, 5L, 5L, cumulativeHash);

        AuditIntegrityCheckpointEntity checkpoint = AuditIntegrityCheckpointEntity.builder()
                .id(UUID.randomUUID().toString())
                .organizationId(ORG_ID.toString())
                .checkpointType("PERIODIC")
                .startSequence(1L)
                .endSequence(5L)
                .recordCount(5L)
                .cumulativeHash(cumulativeHash)
                .hmacSignature(validHmac)
                .createdBy("SYSTEM")
                .createdAt(Instant.now())
                .build();

        when(checkpointRepository.findByOrganizationIdAndStartSequenceGreaterThanEqualAndEndSequenceLessThanEqualOrderByEndSequenceAsc(
                eq(ORG_ID.toString()), eq(1L), eq(5L)))
                .thenReturn(List.of(checkpoint));

        AuditIntegrityVerificationResponse response = integrityService.verifyIntegrity(ORG_ID, null, null);

        assertThat(response.isValid()).isTrue();
        assertThat(response.getCheckpointsVerified()).isEqualTo(1);
    }

    @Test
    @DisplayName("Detect invalid checkpoint HMAC signature (tampered checkpoint)")
    void shouldDetectInvalidCheckpointHmac() {
        List<AuthorizationAuditEventEntity> chain = buildMockChain(5, false, false, false);
        when(auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(ORG_ID.toString()))
                .thenReturn(chain);

        AuditIntegrityCheckpointEntity checkpoint = AuditIntegrityCheckpointEntity.builder()
                .id(UUID.randomUUID().toString())
                .organizationId(ORG_ID.toString())
                .checkpointType("PERIODIC")
                .startSequence(1L)
                .endSequence(5L)
                .recordCount(5L)
                .cumulativeHash(chain.get(4).getRecordHash())
                .hmacSignature("bad_tampered_hmac_signature_00000000000000000000000000000000000000")
                .createdBy("SYSTEM")
                .createdAt(Instant.now())
                .build();

        when(checkpointRepository.findByOrganizationIdAndStartSequenceGreaterThanEqualAndEndSequenceLessThanEqualOrderByEndSequenceAsc(
                eq(ORG_ID.toString()), eq(1L), eq(5L)))
                .thenReturn(List.of(checkpoint));

        AuditIntegrityVerificationResponse response = integrityService.verifyIntegrity(ORG_ID, null, null);

        assertThat(response.isValid()).isFalse();
        assertThat(response.isTampered()).isTrue();
        assertThat(response.getTamperDetails()).contains("Checkpoint HMAC verification failed");
    }

    @Test
    @DisplayName("Verify on-demand checkpoint creation over latest sequence")
    void shouldCreateCheckpointSuccessfully() {
        List<AuthorizationAuditEventEntity> chain = buildMockChain(3, false, false, false);
        when(checkpointRepository.findTopByOrganizationIdOrderByEndSequenceDesc(ORG_ID.toString()))
                .thenReturn(Optional.empty());
        when(auditEventRepository.findTopByOrganizationIdOrderBySequenceNumberDesc(ORG_ID.toString()))
                .thenReturn(Optional.of(chain.get(2)));
        when(checkpointRepository.save(any(AuditIntegrityCheckpointEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AuditIntegrityCheckpointEntity created = integrityService.createCheckpoint(ORG_ID, "admin-user", "ON_DEMAND");

        assertThat(created).isNotNull();
        assertThat(created.getStartSequence()).isEqualTo(1L);
        assertThat(created.getEndSequence()).isEqualTo(3L);
        assertThat(created.getRecordCount()).isEqualTo(3L);
        assertThat(created.getCumulativeHash()).isEqualTo(chain.get(2).getRecordHash());
        assertThat(created.getHmacSignature()).isNotBlank();
    }

    @Test
    @DisplayName("Concurrent event creation produces verifiable monotonic hash sequence")
    void shouldMaintainMonotonicHashSequenceUnderConcurrency() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount * operationsPerThread);

        List<AuthorizationAuditEventEntity> threadSafeChain = new ArrayList<>();
        Object lock = new Object();
        AtomicInteger seqGen = new AtomicInteger(0);

        for (int i = 0; i < threadCount * operationsPerThread; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    synchronized (lock) {
                        int seq = seqGen.incrementAndGet();
                        String prevHash = threadSafeChain.isEmpty()
                                ? AuditIntegrityService.GENESIS_HASH
                                : threadSafeChain.get(threadSafeChain.size() - 1).getRecordHash();

                        Instant time = Instant.now().plusMillis(seq);
                        String eventId = UUID.randomUUID().toString();
                        String hash = integrityService.computeRecordHash(
                                seq, eventId, time, "AUTHORIZATION_ALLOWED", "user-1",
                                "ORGANIZATION", ORG_ID.toString(), null, "project", "p-1",
                                "ALLOW", null, null, null, null, null, null, prevHash
                        );

                        AuthorizationAuditEventEntity entity = AuthorizationAuditEventEntity.builder()
                                .id(UUID.randomUUID().toString())
                                .sequenceNumber((long) seq)
                                .eventId(eventId)
                                .timestamp(time)
                                .eventType("AUTHORIZATION_ALLOWED")
                                .userId("user-1")
                                .scope("ORGANIZATION")
                                .organizationId(ORG_ID.toString())
                                .resourceType("project")
                                .resourceId("p-1")
                                .decision("ALLOW")
                                .recordHash(hash)
                                .previousRecordHash(prevHash)
                                .build();

                        threadSafeChain.add(entity);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(threadSafeChain).hasSize(200);

        // Verify that the concurrently constructed chain passes integrity verification
        when(auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(ORG_ID.toString()))
                .thenReturn(threadSafeChain);
        when(checkpointRepository.findByOrganizationIdAndStartSequenceGreaterThanEqualAndEndSequenceLessThanEqualOrderByEndSequenceAsc(
                eq(ORG_ID.toString()), eq(1L), eq(200L)))
                .thenReturn(List.of());

        AuditIntegrityVerificationResponse response = integrityService.verifyIntegrity(ORG_ID, null, null);
        assertThat(response.isValid()).isTrue();
        assertThat(response.isTampered()).isFalse();
        assertThat(response.getTotalRecordsVerified()).isEqualTo(200);
    }

    private List<AuthorizationAuditEventEntity> buildMockChain(int count, boolean tamperPayload, boolean breakLink, boolean gap) {
        List<AuthorizationAuditEventEntity> list = new ArrayList<>();
        String prevHash = AuditIntegrityService.GENESIS_HASH;
        Instant baseTime = Instant.parse("2026-11-10T10:00:00Z");

        for (int i = 1; i <= count; i++) {
            Instant time = baseTime.plusSeconds(i * 10L);
            String eventId = UUID.randomUUID().toString();
            String hash = integrityService.computeRecordHash(
                    i, eventId, time, "AUTHORIZATION_ALLOWED", "user-1",
                    "ORGANIZATION", ORG_ID.toString(), null, "project", "p-1",
                    "ALLOW", null, null, null, null, null, null, prevHash
            );

            AuthorizationAuditEventEntity entity = AuthorizationAuditEventEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .sequenceNumber((long) i)
                    .eventId(eventId)
                    .timestamp(time)
                    .eventType("AUTHORIZATION_ALLOWED")
                    .userId("user-1")
                    .scope("ORGANIZATION")
                    .organizationId(ORG_ID.toString())
                    .resourceType("project")
                    .resourceId("p-1")
                    .decision("ALLOW")
                    .recordHash(hash)
                    .previousRecordHash(prevHash)
                    .build();

            list.add(entity);
            prevHash = hash;
        }

        return list;
    }
}
