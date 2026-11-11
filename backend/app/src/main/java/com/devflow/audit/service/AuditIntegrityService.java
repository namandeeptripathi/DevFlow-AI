package com.devflow.audit.service;

import com.devflow.audit.domain.AuditIntegrityCheckpointEntity;
import com.devflow.audit.domain.AuthorizationAuditEventEntity;
import com.devflow.audit.dto.AuditIntegrityVerificationResponse;
import com.devflow.audit.repository.AuditIntegrityCheckpointRepository;
import com.devflow.audit.repository.AuthorizationAuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Service providing cryptographic tamper-evident hashing, append-only chain verification,
 * and HMAC-signed integrity checkpoints for the DevFlow authorization audit ledger.
 *
 * <p>Reference: Stage 8 Workstream C, AUTHORIZATION_MODEL.md §13.2.
 */
@Service
public class AuditIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(AuditIntegrityService.class);

    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AuthorizationAuditEventRepository auditEventRepository;
    private final AuditIntegrityCheckpointRepository checkpointRepository;
    private final byte[] integrityKeyBytes;

    public AuditIntegrityService(
            AuthorizationAuditEventRepository auditEventRepository,
            AuditIntegrityCheckpointRepository checkpointRepository,
            @Value("${devflow.security.audit.integrity-key:devflow-audit-default-secret-key-32-bytes-minimum!}") String integrityKey
    ) {
        this.auditEventRepository = Objects.requireNonNull(auditEventRepository, "auditEventRepository must not be null");
        this.checkpointRepository = Objects.requireNonNull(checkpointRepository, "checkpointRepository must not be null");
        this.integrityKeyBytes = integrityKey.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Computes the deterministic SHA-256 hash for an audit record linked to the previous record hash.
     */
    public String computeRecordHash(
            long sequenceNumber,
            String eventId,
            Instant timestamp,
            String eventType,
            String userId,
            String scope,
            String organizationId,
            String projectId,
            String resourceType,
            String resourceId,
            String decision,
            String denialReason,
            String targetId,
            String targetType,
            String previousState,
            String newState,
            String reason,
            String previousRecordHash
    ) {
        String payload = String.join("|",
                String.valueOf(sequenceNumber),
                nullToEmpty(eventId),
                timestamp != null ? String.valueOf(timestamp.toEpochMilli()) : "",
                nullToEmpty(eventType),
                nullToEmpty(userId),
                nullToEmpty(scope),
                nullToEmpty(organizationId),
                nullToEmpty(projectId),
                nullToEmpty(resourceType),
                nullToEmpty(resourceId),
                nullToEmpty(decision),
                nullToEmpty(denialReason),
                nullToEmpty(targetId),
                nullToEmpty(targetType),
                nullToEmpty(previousState),
                nullToEmpty(newState),
                nullToEmpty(reason),
                nullToEmpty(previousRecordHash)
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest algorithm not available", e);
        }
    }

    /**
     * Computes SHA-256 hash directly from a persisted entity.
     */
    public String computeEntityHash(AuthorizationAuditEventEntity entity) {
        return computeRecordHash(
                entity.getSequenceNumber(),
                entity.getEventId(),
                entity.getTimestamp(),
                entity.getEventType(),
                entity.getUserId(),
                entity.getScope(),
                entity.getOrganizationId(),
                entity.getProjectId(),
                entity.getResourceType(),
                entity.getResourceId(),
                entity.getDecision(),
                entity.getDenialReason(),
                entity.getTargetId(),
                entity.getTargetType(),
                entity.getPreviousState(),
                entity.getNewState(),
                entity.getReason(),
                entity.getPreviousRecordHash()
        );
    }

    /**
     * Computes HMAC-SHA256 signature over checkpoint metadata.
     */
    public String computeCheckpointHmac(
            String organizationId,
            long startSequence,
            long endSequence,
            long recordCount,
            String cumulativeHash
    ) {
        String data = String.join("|",
                nullToEmpty(organizationId),
                String.valueOf(startSequence),
                String.valueOf(endSequence),
                String.valueOf(recordCount),
                nullToEmpty(cumulativeHash)
        );

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(integrityKeyBytes, HMAC_ALGORITHM));
            byte[] signature = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signature);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate checkpoint HMAC", e);
        }
    }

    /**
     * Verifies the cryptographic integrity of audit records for an organization over an optional time range.
     */
    @Transactional(readOnly = true)
    public AuditIntegrityVerificationResponse verifyIntegrity(UUID organizationId, Instant from, Instant to) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");

        String orgIdStr = organizationId.toString();
        boolean fullChainCheck = (from == null && to == null);
        List<AuthorizationAuditEventEntity> records;
        if (!fullChainCheck) {
            records = auditEventRepository.findByOrganizationIdAndTimestampBetweenOrderBySequenceNumberAsc(orgIdStr, from, to);
        } else {
            records = auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(orgIdStr);
        }

        return verifyRecordsChain(records, orgIdStr, fullChainCheck);
    }

    /**
     * Verifies the cryptographic integrity of audit records for an organization over a sequence range.
     */
    @Transactional(readOnly = true)
    public AuditIntegrityVerificationResponse verifyIntegrityBySequence(UUID organizationId, Long fromSeq, Long toSeq) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");

        String orgIdStr = organizationId.toString();
        boolean fullChainCheck = (fromSeq == null && toSeq == null);
        List<AuthorizationAuditEventEntity> records;
        if (!fullChainCheck) {
            records = auditEventRepository.findByOrganizationIdAndSequenceNumberBetweenOrderBySequenceNumberAsc(orgIdStr, fromSeq, toSeq);
        } else {
            records = auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(orgIdStr);
        }

        return verifyRecordsChain(records, orgIdStr, fullChainCheck);
    }

    private AuditIntegrityVerificationResponse verifyRecordsChain(
            List<AuthorizationAuditEventEntity> records,
            String organizationId,
            boolean fullChainCheck
    ) {
        if (records.isEmpty()) {
            AuditIntegrityCheckpointEntity latestCheckpoint = fullChainCheck
                    ? checkpointRepository.findTopByOrganizationIdOrderByEndSequenceDesc(organizationId).orElse(null)
                    : null;
            if (latestCheckpoint != null) {
                String detail = String.format(
                        "Audit records missing for organization [%s] despite integrity checkpoint [%s] ending at sequence [%d]",
                        organizationId, latestCheckpoint.getId(), latestCheckpoint.getEndSequence());
                log.warn("AUDIT_INTEGRITY_TAMPER: {}", detail);
                return buildTamperedResponse(0L, detail, null, null, null);
            }

            return AuditIntegrityVerificationResponse.builder()
                    .valid(true)
                    .tampered(false)
                    .totalRecordsVerified(0)
                    .checkpointsVerified(0)
                    .lastVerifiedHash(null)
                    .verifiedFrom(null)
                    .verifiedTo(null)
                    .build();
        }

        Instant verifiedFrom = records.get(0).getTimestamp();
        Instant verifiedTo = records.get(records.size() - 1).getTimestamp();
        long count = 0;

        AuthorizationAuditEventEntity firstRecord = records.get(0);
        boolean startsAtGenesis = fullChainCheck || firstRecord.getSequenceNumber() == 1L;
        String expectedPreviousHash = startsAtGenesis ? GENESIS_HASH : firstRecord.getPreviousRecordHash();
        long expectedSequence = fullChainCheck ? 1L : firstRecord.getSequenceNumber();
        Map<Long, AuthorizationAuditEventEntity> recordsBySeq = new HashMap<>(records.size());

        for (AuthorizationAuditEventEntity record : records) {
            count++;

            // 1. Verify sequence monotonicity
            if (record.getSequenceNumber() != expectedSequence) {
                String detail = String.format("Sequence gap or reordering detected at record ID [%s]: expected sequence [%d] but found [%d]",
                        record.getId(), expectedSequence, record.getSequenceNumber());
                log.warn("AUDIT_INTEGRITY_TAMPER: {}", detail);
                return buildTamperedResponse(count, detail, record.getRecordHash(), verifiedFrom, verifiedTo);
            }

            // 2. Verify previous record hash link
            if (!Objects.equals(record.getPreviousRecordHash(), expectedPreviousHash)) {
                String detail = String.format("Broken hash chain link at record ID [%s] (seq [%d]): expected previous hash [%s] but found [%s]",
                        record.getId(), record.getSequenceNumber(), expectedPreviousHash, record.getPreviousRecordHash());
                log.warn("AUDIT_INTEGRITY_TAMPER: {}", detail);
                return buildTamperedResponse(count, detail, record.getRecordHash(), verifiedFrom, verifiedTo);
            }

            // 3. Recompute and verify payload hash
            String recomputedHash = computeEntityHash(record);
            if (!Objects.equals(record.getRecordHash(), recomputedHash)) {
                String detail = String.format("Hash mismatch for modified record ID [%s] (seq [%d]): stored [%s] vs recomputed [%s]",
                        record.getId(), record.getSequenceNumber(), record.getRecordHash(), recomputedHash);
                log.warn("AUDIT_INTEGRITY_TAMPER: {}", detail);
                return buildTamperedResponse(count, detail, record.getRecordHash(), verifiedFrom, verifiedTo);
            }

            recordsBySeq.put(record.getSequenceNumber(), record);
            expectedPreviousHash = record.getRecordHash();
            expectedSequence++;
        }

        // 4. Verify any checkpoints within range (and detect tail truncation against latest checkpoint)
        long startSeq = firstRecord.getSequenceNumber();
        long endSeq = records.get(records.size() - 1).getSequenceNumber();
        List<AuditIntegrityCheckpointEntity> checkpoints = checkpointRepository
                .findByOrganizationIdAndStartSequenceGreaterThanEqualAndEndSequenceLessThanEqualOrderByEndSequenceAsc(
                        organizationId, startSeq, endSeq);

        if (fullChainCheck) {
            AuditIntegrityCheckpointEntity latestCheckpoint = checkpointRepository
                    .findTopByOrganizationIdOrderByEndSequenceDesc(organizationId)
                    .orElse(null);
            if (latestCheckpoint != null && latestCheckpoint.getEndSequence() > endSeq) {
                String detail = String.format(
                        "Audit ledger truncated: latest record sequence is [%d] but checkpoint [%s] requires sequence [%d]",
                        endSeq, latestCheckpoint.getId(), latestCheckpoint.getEndSequence());
                log.warn("AUDIT_INTEGRITY_TAMPER: {}", detail);
                return buildTamperedResponse(count, detail, expectedPreviousHash, verifiedFrom, verifiedTo);
            }
        }

        int checkpointsVerified = 0;
        if (checkpoints != null) {
            for (AuditIntegrityCheckpointEntity cp : checkpoints) {
                String recomputedHmac = computeCheckpointHmac(
                        cp.getOrganizationId(), cp.getStartSequence(), cp.getEndSequence(),
                        cp.getRecordCount(), cp.getCumulativeHash());

                byte[] storedHmacBytes = cp.getHmacSignature() != null
                        ? cp.getHmacSignature().getBytes(StandardCharsets.UTF_8) : null;
                byte[] computedHmacBytes = recomputedHmac.getBytes(StandardCharsets.UTF_8);

                if (storedHmacBytes == null || !MessageDigest.isEqual(storedHmacBytes, computedHmacBytes)) {
                    String detail = String.format("Checkpoint HMAC verification failed for checkpoint ID [%s] over range [%d-%d]",
                            cp.getId(), cp.getStartSequence(), cp.getEndSequence());
                    log.warn("AUDIT_INTEGRITY_TAMPER: {}", detail);
                    return buildTamperedResponse(count, detail, expectedPreviousHash, verifiedFrom, verifiedTo);
                }

                long expectedRangeCount = cp.getEndSequence() - cp.getStartSequence() + 1L;
                if (cp.getRecordCount() != expectedRangeCount) {
                    String detail = String.format("Checkpoint ID [%s] recordCount [%d] does not match sequence range [%d-%d]",
                            cp.getId(), cp.getRecordCount(), cp.getStartSequence(), cp.getEndSequence());
                    log.warn("AUDIT_INTEGRITY_TAMPER: {}", detail);
                    return buildTamperedResponse(count, detail, expectedPreviousHash, verifiedFrom, verifiedTo);
                }

                AuthorizationAuditEventEntity endRecord = recordsBySeq.get(cp.getEndSequence());
                if (endRecord == null) {
                    String detail = String.format("Audit record missing for checkpoint ID [%s] end sequence [%d] (possible tail truncation)",
                            cp.getId(), cp.getEndSequence());
                    log.warn("AUDIT_INTEGRITY_TAMPER: {}", detail);
                    return buildTamperedResponse(count, detail, expectedPreviousHash, verifiedFrom, verifiedTo);
                }

                if (!Objects.equals(endRecord.getRecordHash(), cp.getCumulativeHash())) {
                    String detail = String.format("Checkpoint ID [%s] cumulative hash mismatch at sequence [%d]: checkpoint [%s] vs record [%s]",
                            cp.getId(), cp.getEndSequence(), cp.getCumulativeHash(), endRecord.getRecordHash());
                    log.warn("AUDIT_INTEGRITY_TAMPER: {}", detail);
                    return buildTamperedResponse(count, detail, expectedPreviousHash, verifiedFrom, verifiedTo);
                }

                checkpointsVerified++;
            }
        }

        return AuditIntegrityVerificationResponse.builder()
                .valid(true)
                .tampered(false)
                .totalRecordsVerified(count)
                .checkpointsVerified(checkpointsVerified)
                .lastVerifiedHash(expectedPreviousHash)
                .verifiedFrom(verifiedFrom)
                .verifiedTo(verifiedTo)
                .build();
    }

    private AuditIntegrityVerificationResponse buildTamperedResponse(
            long recordsProcessed, String detail, String lastHash, Instant from, Instant to) {
        return AuditIntegrityVerificationResponse.builder()
                .valid(false)
                .tampered(true)
                .totalRecordsVerified(recordsProcessed)
                .tamperDetails(detail)
                .lastVerifiedHash(lastHash)
                .verifiedFrom(from)
                .verifiedTo(to)
                .build();
    }

    /**
     * Creates an HMAC-signed checkpoint over the uncheckpointed audit trail of an organization.
     */
    @Transactional
    public AuditIntegrityCheckpointEntity createCheckpoint(UUID organizationId, String createdBy, String checkpointType) {
        String orgIdStr = organizationId != null ? organizationId.toString() : null;

        AuditIntegrityCheckpointEntity latestCheckpoint = orgIdStr != null
                ? checkpointRepository.findTopByOrganizationIdOrderByEndSequenceDesc(orgIdStr).orElse(null)
                : null;

        long startSequence = (latestCheckpoint == null) ? 1L : latestCheckpoint.getEndSequence() + 1L;
        AuthorizationAuditEventEntity latestRecord = orgIdStr != null
                ? auditEventRepository.findTopByOrganizationIdOrderBySequenceNumberDesc(orgIdStr).orElse(null)
                : auditEventRepository.findTopByOrganizationIdIsNullOrderBySequenceNumberDesc().orElse(null);

        if (latestRecord == null || latestRecord.getSequenceNumber() < startSequence) {
            log.info("No new audit records to checkpoint for org [{}]", orgIdStr);
            return null;
        }

        long endSequence = latestRecord.getSequenceNumber();
        long recordCount = endSequence - startSequence + 1L;
        String cumulativeHash = latestRecord.getRecordHash();
        String hmacSignature = computeCheckpointHmac(orgIdStr, startSequence, endSequence, recordCount, cumulativeHash);

        AuditIntegrityCheckpointEntity checkpoint = AuditIntegrityCheckpointEntity.builder()
                .id(UUID.randomUUID().toString())
                .organizationId(orgIdStr)
                .checkpointType(checkpointType != null ? checkpointType : "ON_DEMAND")
                .startSequence(startSequence)
                .endSequence(endSequence)
                .recordCount(recordCount)
                .cumulativeHash(cumulativeHash)
                .hmacSignature(hmacSignature)
                .createdBy(createdBy)
                .createdAt(Instant.now())
                .build();

        AuditIntegrityCheckpointEntity saved = checkpointRepository.save(checkpoint);
        log.info("Created audit integrity checkpoint [{}] for org [{}] range [{}-{}] cumulativeHash [{}]",
                saved.getId(), orgIdStr, startSequence, endSequence, cumulativeHash);
        return saved;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
