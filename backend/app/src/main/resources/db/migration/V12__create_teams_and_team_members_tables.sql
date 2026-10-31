-- ──────────────────────────────────────────────────────────────────────────────
-- DevFlow — Teams & Team Members Schema Migration
--
-- Migration: V12__create_teams_and_team_members_tables.sql
-- Author:    DevFlow Backend Engineering
-- Date:      2026-10-31
--
-- Establishes the `teams` and `team_members` tables to model team units
-- within Workspaces and Organizations.
--
-- Reference: docs/database/DATABASE_DESIGN.md
-- ──────────────────────────────────────────────────────────────────────────────

-- 1. Ensure composite unique constraint on workspaces for composite foreign key integrity
ALTER TABLE workspaces ADD CONSTRAINT uk_workspaces_id_organization_id UNIQUE (id, organization_id);

-- 2. Create teams table
CREATE TABLE teams (
    id              VARCHAR(36)              NOT NULL,
    workspace_id    VARCHAR(36)              NOT NULL,
    organization_id VARCHAR(36)              NOT NULL,
    name            VARCHAR(100)             NOT NULL,
    description     VARCHAR(500),
    status          VARCHAR(30)              NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_teams                 PRIMARY KEY (id),
    CONSTRAINT fk_teams_workspace       FOREIGN KEY (workspace_id, organization_id)
                                            REFERENCES workspaces (id, organization_id)
                                            ON DELETE RESTRICT,
    CONSTRAINT fk_teams_organization    FOREIGN KEY (organization_id)
                                            REFERENCES organizations (id)
                                            ON DELETE RESTRICT,
    CONSTRAINT chk_teams_status         CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

-- Case-insensitive team name uniqueness per workspace
CREATE UNIQUE INDEX uk_teams_workspace_name_ci ON teams (workspace_id, LOWER(name));

-- Tenant index for organization lookups
CREATE INDEX idx_teams_organization_id ON teams (organization_id);

-- 3. Create team_members table
CREATE TABLE team_members (
    id              VARCHAR(36)              NOT NULL,
    team_id         VARCHAR(36)              NOT NULL,
    user_id         VARCHAR(36)              NOT NULL,
    role            VARCHAR(30)              NOT NULL DEFAULT 'MEMBER',
    joined_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_team_members           PRIMARY KEY (id),
    CONSTRAINT uk_team_members_team_user UNIQUE (team_id, user_id),
    CONSTRAINT fk_team_members_team      FOREIGN KEY (team_id)
                                             REFERENCES teams (id)
                                             ON DELETE CASCADE,
    CONSTRAINT fk_team_members_user      FOREIGN KEY (user_id)
                                             REFERENCES users (id)
                                             ON DELETE RESTRICT,
    CONSTRAINT chk_team_members_role     CHECK (role IN ('LEAD', 'MEMBER'))
);

-- Index user_id for member lookups (team_id is already covered as the leading column in uk_team_members_team_user)
CREATE INDEX idx_team_members_user_id ON team_members (user_id);

-- Partial unique index ensuring at most one active LEAD per team
CREATE UNIQUE INDEX uk_team_members_team_lead ON team_members (team_id) WHERE role = 'LEAD';

