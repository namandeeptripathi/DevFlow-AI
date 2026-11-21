-- ──────────────────────────────────────────────────────────────────────────────
-- DevFlow — Permissions Stage 2: Durable Project Ownership Registry
--
-- Migration: V17__create_project_registrations_table.sql
--
-- Establishes the `project_registrations` table as the authoritative, durable
-- relational registry anchoring project tenant boundaries in PostgreSQL.
-- Prevents multi-tenant project collisions and enforces global uniqueness across
-- organizations prior to the full Project Management module.
-- ──────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS project_registrations (
    project_id      VARCHAR(36)              NOT NULL,
    organization_id VARCHAR(36)              NOT NULL,
    registered_by   VARCHAR(36),
    registered_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_project_registrations          PRIMARY KEY (project_id),
    CONSTRAINT fk_project_registrations_org      FOREIGN KEY (organization_id)
                                                     REFERENCES organizations (id)
                                                     ON DELETE CASCADE,
    CONSTRAINT fk_project_registrations_user     FOREIGN KEY (registered_by)
                                                     REFERENCES users (id)
                                                     ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_project_registrations_org_id ON project_registrations (organization_id);
