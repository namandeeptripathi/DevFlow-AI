-- ──────────────────────────────────────────────────────────────────────────────
-- DevFlow — Organization Members Table Migration
--
-- Migration: V9__create_organization_members_table.sql
-- Author:    DevFlow Backend Engineering
-- Date:      2026-08-09
--
-- Establishes the `organization_members` table to model user membership within
-- organizations, including RBAC roles and membership lifecycle status.
--
-- Relationship: Many-to-One with `organizations` (organization_id FK).
--               Many-to-One with `users` (user_id FK).
--               Unique constraint on (organization_id, user_id).
--
-- Reference: docs/database/DATABASE_DESIGN.md
-- ──────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS organization_members (
    id              VARCHAR(36)              NOT NULL,
    organization_id VARCHAR(36)              NOT NULL,
    user_id         VARCHAR(36)              NOT NULL,
    role            VARCHAR(30)              NOT NULL,
    status          VARCHAR(30)              NOT NULL DEFAULT 'ACTIVE',
    joined_at       TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_organization_members                 PRIMARY KEY (id),
    CONSTRAINT uk_organization_members_org_user        UNIQUE (organization_id, user_id),
    CONSTRAINT fk_organization_members_organization    FOREIGN KEY (organization_id)
                                                           REFERENCES organizations (id)
                                                           ON DELETE RESTRICT,
    CONSTRAINT fk_organization_members_user            FOREIGN KEY (user_id)
                                                           REFERENCES users (id)
                                                           ON DELETE RESTRICT,
    CONSTRAINT chk_organization_members_role           CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER')),
    CONSTRAINT chk_organization_members_status         CHECK (status IN ('ACTIVE', 'INVITED', 'SUSPENDED'))
);

CREATE INDEX IF NOT EXISTS idx_organization_members_org_id   ON organization_members (organization_id);
CREATE INDEX IF NOT EXISTS idx_organization_members_user_id  ON organization_members (user_id);
CREATE INDEX IF NOT EXISTS idx_organization_members_org_user ON organization_members (organization_id, user_id);
