-- ──────────────────────────────────────────────────────────────────────────────
-- DevFlow — Organization Invitations Table Migration
--
-- Migration: V10__create_organization_invitations_table.sql
-- Author:    DevFlow Backend Engineering
-- Date:      2026-08-09
--
-- Establishes the `organization_invitations` table to model secure, tokenized
-- organization onboarding workflows and lifecycle state transitions.
--
-- Relationship: Many-to-One with `organizations` (organization_id FK).
--               Many-to-One with `users` (invited_by_user_id FK).
--               Unique token constraint.
--               Partial unique constraint for pending invitations per (org, email).
--
-- Reference: docs/database/DATABASE_DESIGN.md
-- ──────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS organization_invitations (
    id                  VARCHAR(36)              NOT NULL,
    organization_id     VARCHAR(36)              NOT NULL,
    email               VARCHAR(255)             NOT NULL,
    role                VARCHAR(30)              NOT NULL,
    token               VARCHAR(64)              NOT NULL,
    invited_by_user_id VARCHAR(36)              NOT NULL,
    status              VARCHAR(30)              NOT NULL DEFAULT 'PENDING',
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_at         TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_organization_invitations                 PRIMARY KEY (id),
    CONSTRAINT uk_organization_invitations_token           UNIQUE (token),
    CONSTRAINT fk_organization_invitations_organization    FOREIGN KEY (organization_id)
                                                               REFERENCES organizations (id)
                                                               ON DELETE RESTRICT,
    CONSTRAINT fk_organization_invitations_invited_by      FOREIGN KEY (invited_by_user_id)
                                                               REFERENCES users (id)
                                                               ON DELETE RESTRICT,
    CONSTRAINT chk_organization_invitations_role           CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER')),
    CONSTRAINT chk_organization_invitations_status         CHECK (status IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED'))
);

CREATE INDEX IF NOT EXISTS idx_organization_invitations_org_id ON organization_invitations (organization_id);
CREATE INDEX IF NOT EXISTS idx_organization_invitations_email  ON organization_invitations (email);
CREATE INDEX IF NOT EXISTS idx_organization_invitations_token  ON organization_invitations (token);
CREATE INDEX IF NOT EXISTS idx_organization_invitations_status ON organization_invitations (status);

CREATE UNIQUE INDEX IF NOT EXISTS uk_org_invitations_pending
    ON organization_invitations (organization_id, email)
    WHERE status = 'PENDING';
