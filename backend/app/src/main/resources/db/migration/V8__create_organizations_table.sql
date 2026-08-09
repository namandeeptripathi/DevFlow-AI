-- ──────────────────────────────────────────────────────────────────────────────
-- DevFlow — Organization Domain Foundation Migration
--
-- Migration: V8__create_organizations_table.sql
-- Author:    DevFlow Backend Engineering
-- Date:      2026-08-09
--
-- Establishes the `organizations` table — the primary tenant boundary
-- within the DevFlow platform. Each organization is owned by a single user
-- and serves as the top-level aggregate root for multi-tenant isolation.
--
-- Relationship: Many-to-One with `users` (owner_id FK).
-- Slug provides a URL-safe, human-readable, globally unique identifier
-- for API and UI routing.
--
-- Reference: docs/database/DATABASE_DESIGN.md
-- ──────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS organizations (
    id          VARCHAR(36)              NOT NULL,
    name        VARCHAR(100)             NOT NULL,
    slug        VARCHAR(120)             NOT NULL,
    description VARCHAR(500),
    owner_id    VARCHAR(36)              NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_organizations            PRIMARY KEY (id),
    CONSTRAINT uk_organizations_slug       UNIQUE (slug),
    CONSTRAINT fk_organizations_owner      FOREIGN KEY (owner_id)
                                               REFERENCES users (id)
                                               ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_organizations_slug     ON organizations (slug);
CREATE INDEX IF NOT EXISTS idx_organizations_owner_id ON organizations (owner_id);
