-- ──────────────────────────────────────────────────────────────────────────────
-- DevFlow — Stage 9 Final Hardening & Production Readiness
--
-- Migration: V16__harden_audit_and_concurrency_constraints.sql
--
-- Enforces database-level uniqueness guarantees for append-only audit sequence
-- numbering and integrity checkpoints across concurrent application transactions.
-- ──────────────────────────────────────────────────────────────────────────────

CREATE UNIQUE INDEX IF NOT EXISTS uk_authz_audit_org_seq
    ON authorization_audit_events (organization_id, sequence_number)
    WHERE organization_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_authz_audit_platform_seq
    ON authorization_audit_events (sequence_number)
    WHERE organization_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_audit_checkpoint_org_range
    ON audit_integrity_checkpoints (organization_id, start_sequence, end_sequence)
    WHERE organization_id IS NOT NULL;

