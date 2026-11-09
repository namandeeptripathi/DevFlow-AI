-- ──────────────────────────────────────────────────────────────────────────────
-- DevFlow — Custom Roles & Permission Governance (Stage 7)
--
-- Migration: V14__create_custom_roles_tables.sql
-- Author:    DevFlow Core Security & Platform Engineering
-- Date:      2026-11-09
--
-- Establishes organization-scoped custom roles, permission associations,
-- and custom role assignment persistence models.
--
-- Reference:
--   - docs/security/AUTHORIZATION_MODEL.md §5.7
--   - docs/security/STAGE-7-CUSTOM-ROLES-GOVERNANCE.md
-- ──────────────────────────────────────────────────────────────────────────────

-- ── 1. Custom Roles Table ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS custom_roles (
    id              VARCHAR(36)              NOT NULL,
    organization_id VARCHAR(36)              NOT NULL,
    name            VARCHAR(50)              NOT NULL,
    display_name    VARCHAR(100)             NOT NULL,
    description     VARCHAR(500),
    is_active       BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_by      VARCHAR(36)              NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version         BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_custom_roles            PRIMARY KEY (id),
    CONSTRAINT fk_custom_roles_org        FOREIGN KEY (organization_id)
                                              REFERENCES organizations (id)
                                              ON DELETE RESTRICT,
    CONSTRAINT fk_custom_roles_created_by FOREIGN KEY (created_by)
                                              REFERENCES users (id)
                                              ON DELETE RESTRICT,
    CONSTRAINT uk_custom_roles_org_name   UNIQUE (organization_id, name)
);

CREATE INDEX IF NOT EXISTS idx_custom_roles_org_id ON custom_roles (organization_id);
CREATE INDEX IF NOT EXISTS idx_custom_roles_active ON custom_roles (organization_id, is_active);

-- ── 2. Custom Role to Canonical Permission Mapping Table ───────────────────────
CREATE TABLE IF NOT EXISTS custom_role_permissions (
    id             VARCHAR(36)              NOT NULL,
    custom_role_id VARCHAR(36)              NOT NULL,
    permission_id  VARCHAR(36)              NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_custom_role_permissions          PRIMARY KEY (id),
    CONSTRAINT fk_custom_role_permissions_role     FOREIGN KEY (custom_role_id)
                                                       REFERENCES custom_roles (id)
                                                       ON DELETE CASCADE,
    CONSTRAINT fk_custom_role_permissions_perm     FOREIGN KEY (permission_id)
                                                       REFERENCES permissions (id)
                                                       ON DELETE RESTRICT,
    CONSTRAINT uk_custom_role_permissions_role_perm UNIQUE (custom_role_id, permission_id)
);

CREATE INDEX IF NOT EXISTS idx_custom_role_perms_role_id ON custom_role_permissions (custom_role_id);
CREATE INDEX IF NOT EXISTS idx_custom_role_perms_perm_id ON custom_role_permissions (permission_id);

-- ── 3. Custom Role Assignments Table ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS custom_role_assignments (
    id                VARCHAR(36)              NOT NULL,
    organization_id   VARCHAR(36)              NOT NULL,
    user_id           VARCHAR(36)              NOT NULL,
    custom_role_id    VARCHAR(36)              NOT NULL,
    assigned_by       VARCHAR(36)              NOT NULL,
    assigned_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at        TIMESTAMP WITH TIME ZONE,
    revoked_by        VARCHAR(36),
    revoked_at        TIMESTAMP WITH TIME ZONE,
    revocation_reason VARCHAR(255),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version           BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_custom_role_assignments             PRIMARY KEY (id),
    CONSTRAINT fk_custom_role_assignments_org         FOREIGN KEY (organization_id)
                                                          REFERENCES organizations (id)
                                                          ON DELETE RESTRICT,
    CONSTRAINT fk_custom_role_assignments_user        FOREIGN KEY (user_id)
                                                          REFERENCES users (id)
                                                          ON DELETE RESTRICT,
    CONSTRAINT fk_custom_role_assignments_role        FOREIGN KEY (custom_role_id)
                                                          REFERENCES custom_roles (id)
                                                          ON DELETE RESTRICT,
    CONSTRAINT fk_custom_role_assignments_assigned_by FOREIGN KEY (assigned_by)
                                                          REFERENCES users (id)
                                                          ON DELETE RESTRICT,
    CONSTRAINT fk_custom_role_assignments_revoked_by  FOREIGN KEY (revoked_by)
                                                          REFERENCES users (id)
                                                          ON DELETE RESTRICT,
    CONSTRAINT fk_custom_role_assignments_org_user    FOREIGN KEY (organization_id, user_id)
                                                          REFERENCES organization_members (organization_id, user_id)
                                                          ON DELETE CASCADE
);

-- Active assignment uniqueness: prevents duplicate active assignments for same (org, user, custom_role)
CREATE UNIQUE INDEX IF NOT EXISTS uk_custom_role_assignments_active
    ON custom_role_assignments (organization_id, user_id, custom_role_id)
    WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_custom_role_assignments_org_id   ON custom_role_assignments (organization_id);
CREATE INDEX IF NOT EXISTS idx_custom_role_assignments_user_id  ON custom_role_assignments (user_id);
CREATE INDEX IF NOT EXISTS idx_custom_role_assignments_role_id  ON custom_role_assignments (custom_role_id);
CREATE INDEX IF NOT EXISTS idx_custom_role_assignments_org_user ON custom_role_assignments (organization_id, user_id);
