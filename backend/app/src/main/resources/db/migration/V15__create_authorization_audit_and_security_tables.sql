-- ──────────────────────────────────────────────────────────────────────────────
-- DevFlow — Authorization Audit & Security Monitoring (Stage 8)
--
-- Migration: V15__create_authorization_audit_and_security_tables.sql
-- Author:    DevFlow Core Security & Platform Engineering
-- Date:      2026-11-10
--
-- Establishes:
--   1. authorization_audit_events: append-only, tamper-evident audit ledger
--   2. audit_integrity_checkpoints: HMAC-signed cryptographic verification points
--   3. security_events: detected suspicious security events with rule and evidence links
--   4. security_alerts: tenant-isolated alert lifecycle management and deduplication
--
-- Reference:
--   - docs/security/AUTHORIZATION_MODEL.md §13, §14
--   - docs/security/STAGE-8-AUDIT-SECURITY-MONITORING.md
-- ──────────────────────────────────────────────────────────────────────────────

-- ── 1. Authorization Audit Events Table ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS authorization_audit_events (
    id                    VARCHAR(36)              NOT NULL,
    sequence_number       BIGINT                   NOT NULL,
    event_id              VARCHAR(36)              NOT NULL,
    timestamp             TIMESTAMP WITH TIME ZONE NOT NULL,
    event_type            VARCHAR(60)              NOT NULL,
    user_id               VARCHAR(36)              NOT NULL,
    scope                 VARCHAR(30)              NOT NULL,
    organization_id       VARCHAR(36),
    project_id            VARCHAR(36),
    resource_type         VARCHAR(100),
    resource_id           VARCHAR(100),
    requested_permissions TEXT,
    decision              VARCHAR(20)              NOT NULL,
    denial_reason         VARCHAR(60),
    correlation_id        VARCHAR(100),
    target_id             VARCHAR(36),
    target_type           VARCHAR(50),
    previous_state        TEXT,
    new_state             TEXT,
    reason                VARCHAR(500),
    record_hash           VARCHAR(64)              NOT NULL,
    previous_record_hash  VARCHAR(64)              NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_authorization_audit_events PRIMARY KEY (id),
    CONSTRAINT uk_authz_audit_event_id       UNIQUE (event_id)
);

CREATE INDEX IF NOT EXISTS idx_authz_audit_org_seq     ON authorization_audit_events (organization_id, sequence_number);
CREATE INDEX IF NOT EXISTS idx_authz_audit_org_time    ON authorization_audit_events (organization_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_authz_audit_user_time   ON authorization_audit_events (user_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_authz_audit_target      ON authorization_audit_events (target_id);
CREATE INDEX IF NOT EXISTS idx_authz_audit_org_type    ON authorization_audit_events (organization_id, event_type);
CREATE INDEX IF NOT EXISTS idx_authz_audit_correlation ON authorization_audit_events (correlation_id);

-- ── 2. Audit Integrity Checkpoints Table ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS audit_integrity_checkpoints (
    id              VARCHAR(36)              NOT NULL,
    organization_id VARCHAR(36),
    checkpoint_type VARCHAR(30)              NOT NULL,
    start_sequence  BIGINT                   NOT NULL,
    end_sequence    BIGINT                   NOT NULL,
    record_count    BIGINT                   NOT NULL,
    cumulative_hash VARCHAR(64)              NOT NULL,
    hmac_signature  VARCHAR(64)              NOT NULL,
    created_by      VARCHAR(36),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_audit_integrity_checkpoints PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_checkpoints_org_time ON audit_integrity_checkpoints (organization_id, created_at);
CREATE INDEX IF NOT EXISTS idx_checkpoints_org_seq  ON audit_integrity_checkpoints (organization_id, end_sequence);

-- ── 3. Security Events Table ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS security_events (
    id                 VARCHAR(36)              NOT NULL,
    organization_id    VARCHAR(36)              NOT NULL,
    rule_id            VARCHAR(60)              NOT NULL,
    rule_name          VARCHAR(120)             NOT NULL,
    severity           VARCHAR(20)              NOT NULL,
    title              VARCHAR(200)             NOT NULL,
    description        VARCHAR(1000)            NOT NULL,
    actor_id           VARCHAR(36)              NOT NULL,
    target_id          VARCHAR(36),
    evidence_event_ids TEXT,
    metadata           TEXT,
    detected_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_security_events PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_sec_events_org_time  ON security_events (organization_id, detected_at);
CREATE INDEX IF NOT EXISTS idx_sec_events_rule      ON security_events (organization_id, rule_id);
CREATE INDEX IF NOT EXISTS idx_sec_events_actor     ON security_events (organization_id, actor_id);
CREATE INDEX IF NOT EXISTS idx_sec_events_severity  ON security_events (organization_id, severity);

-- ── 4. Security Alerts Table ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS security_alerts (
    id                VARCHAR(36)              NOT NULL,
    organization_id   VARCHAR(36)              NOT NULL,
    rule_id           VARCHAR(60)              NOT NULL,
    severity          VARCHAR(20)              NOT NULL,
    status            VARCHAR(20)              NOT NULL DEFAULT 'OPEN',
    title             VARCHAR(200)             NOT NULL,
    description       VARCHAR(1000)            NOT NULL,
    actor_id          VARCHAR(36)              NOT NULL,
    target_id         VARCHAR(36),
    first_seen_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    occurrence_count  INT                      NOT NULL DEFAULT 1,
    latest_event_id   VARCHAR(36)              NOT NULL,
    acknowledged_by   VARCHAR(36),
    acknowledged_at   TIMESTAMP WITH TIME ZONE,
    resolved_by       VARCHAR(36),
    resolved_at       TIMESTAMP WITH TIME ZONE,
    resolution_reason VARCHAR(500),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version           BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_security_alerts PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_sec_alerts_org_status     ON security_alerts (organization_id, status);
CREATE INDEX IF NOT EXISTS idx_sec_alerts_org_severity   ON security_alerts (organization_id, severity);
CREATE INDEX IF NOT EXISTS idx_sec_alerts_org_rule_actor ON security_alerts (organization_id, rule_id, actor_id, status);
CREATE INDEX IF NOT EXISTS idx_sec_alerts_last_seen      ON security_alerts (organization_id, last_seen_at);

-- ── 5. Append-Only Immutability Database Triggers ─────────────────────────────
-- Prevents UPDATE or DELETE on historical audit and checkpoint logs through SQL.
CREATE OR REPLACE FUNCTION prevent_audit_log_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit records are append-only. UPDATE and DELETE operations are strictly forbidden.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_audit_log_update_delete ON authorization_audit_events;
CREATE TRIGGER trg_prevent_audit_log_update_delete
BEFORE UPDATE OR DELETE ON authorization_audit_events
FOR EACH ROW
EXECUTE FUNCTION prevent_audit_log_modification();

DROP TRIGGER IF EXISTS trg_prevent_checkpoint_update_delete ON audit_integrity_checkpoints;
CREATE TRIGGER trg_prevent_checkpoint_update_delete
BEFORE UPDATE OR DELETE ON audit_integrity_checkpoints
FOR EACH ROW
EXECUTE FUNCTION prevent_audit_log_modification();
