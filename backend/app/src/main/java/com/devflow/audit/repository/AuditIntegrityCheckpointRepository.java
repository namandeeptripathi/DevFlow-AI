package com.devflow.audit.repository;

import com.devflow.audit.domain.AuditIntegrityCheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Data access repository for audit integrity checkpoints.
 */
@Repository
public interface AuditIntegrityCheckpointRepository extends JpaRepository<AuditIntegrityCheckpointEntity, String> {

    Optional<AuditIntegrityCheckpointEntity> findTopByOrganizationIdOrderByEndSequenceDesc(String organizationId);

    List<AuditIntegrityCheckpointEntity> findByOrganizationIdOrderByEndSequenceAsc(String organizationId);

    List<AuditIntegrityCheckpointEntity> findByOrganizationIdAndStartSequenceGreaterThanEqualAndEndSequenceLessThanEqualOrderByEndSequenceAsc(
            String organizationId, Long startSeq, Long endSeq);
}
