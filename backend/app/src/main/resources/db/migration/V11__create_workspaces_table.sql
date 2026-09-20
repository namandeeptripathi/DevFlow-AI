-- ──────────────────────────────────────────────────────────────────────────────
-- DevFlow — Workspace Domain Foundation Migration
--
-- Migration: V11__create_workspaces_table.sql
-- Author:    DevFlow Backend Engineering
-- Date:      2026-09-20
--
-- Establishes the `workspaces` table to model operational workspaces (e.g.,
-- Engineering, Design, Product, HR) within an Organization.
--
-- Relationship: Many-to-One with `organizations` (organization_id FK).
--               Unique constraint on (organization_id, name).
--
-- Reference: docs/database/DATABASE_DESIGN.md
-- ──────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS workspaces (
    id              VARCHAR(36)              NOT NULL,
    name            VARCHAR(100)             NOT NULL,
    organization_id VARCHAR(36)              NOT NULL,
    visibility      VARCHAR(30)              NOT NULL DEFAULT 'PUBLIC',
    description     VARCHAR(500),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_workspaces                 PRIMARY KEY (id),
    CONSTRAINT uk_workspaces_org_name        UNIQUE (organization_id, name),
    CONSTRAINT fk_workspaces_organization    FOREIGN KEY (organization_id)
                                                 REFERENCES organizations (id)
                                                 ON DELETE RESTRICT,
    CONSTRAINT chk_workspaces_visibility     CHECK (visibility IN ('PUBLIC', 'PRIVATE'))
);

CREATE INDEX IF NOT EXISTS idx_workspaces_organization_id ON workspaces (organization_id);
CREATE INDEX IF NOT EXISTS idx_workspaces_org_name        ON workspaces (organization_id, name);
