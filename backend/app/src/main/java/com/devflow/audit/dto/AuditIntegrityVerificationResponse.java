package com.devflow.audit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Result of cryptographic audit trail integrity verification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditIntegrityVerificationResponse {

    private boolean valid;
    private boolean tampered;
    private long totalRecordsVerified;
    private int checkpointsVerified;
    private String tamperDetails;
    private String lastVerifiedHash;
    private Instant verifiedFrom;
    private Instant verifiedTo;
}
