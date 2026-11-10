package com.devflow.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Cryptographic integrity checkpoint record verifying append-only audit trail continuity.
 *
 * <p>Contains cumulative SHA-256 hash across a sequence range and an HMAC-SHA256 signature
 * computed with an isolated, independently managed secret key.
 */
@Entity
@Table(name = "audit_integrity_checkpoints")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditIntegrityCheckpointEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "organization_id", length = 36)
    private String organizationId;

    @Column(name = "checkpoint_type", length = 30, nullable = false)
    private String checkpointType;

    @Column(name = "start_sequence", nullable = false)
    private Long startSequence;

    @Column(name = "end_sequence", nullable = false)
    private Long endSequence;

    @Column(name = "record_count", nullable = false)
    private Long recordCount;

    @Column(name = "cumulative_hash", length = 64, nullable = false)
    private String cumulativeHash;

    @Column(name = "hmac_signature", length = 64, nullable = false)
    private String hmacSignature;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
