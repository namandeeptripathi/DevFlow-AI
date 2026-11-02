-- ──────────────────────────────────────────────────────────────────────────────
-- DevFlow — Centralized Authorization Persistence & Scoped Role Assignments
--
-- Migration: V13__create_authorization_tables.sql
-- Author:    DevFlow Core Security & Platform Engineering
-- Date:      2026-11-02
--
-- Establishes the database-backed canonical authorization catalog and scoped
-- role assignment persistence model (Stage 4).
--
-- Reference:
--   - docs/security/AUTHORIZATION_MODEL.md §5, §6
--   - docs/security/ADR-001-AUTHORIZATION-POLICY-RESOLUTION.md
--   - docs/database/DATABASE_DESIGN.md
-- ──────────────────────────────────────────────────────────────────────────────

-- ── 1. Canonical Roles Catalog Table ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS roles (
    id          VARCHAR(36)              NOT NULL,
    name        VARCHAR(50)              NOT NULL,
    scope       VARCHAR(30)              NOT NULL,
    description VARCHAR(500),
    is_system   BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version     BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_roles          PRIMARY KEY (id),
    CONSTRAINT uk_roles_name     UNIQUE (name),
    CONSTRAINT uk_roles_id_scope UNIQUE (id, scope),
    CONSTRAINT chk_roles_scope   CHECK (scope IN ('PLATFORM', 'ORGANIZATION', 'PROJECT'))
);

CREATE INDEX IF NOT EXISTS idx_roles_scope ON roles (scope);

-- ── 2. Canonical Permissions Catalog Table ────────────────────────────────────
CREATE TABLE IF NOT EXISTS permissions (
    id          VARCHAR(36)              NOT NULL,
    name        VARCHAR(100)             NOT NULL,
    resource    VARCHAR(50)              NOT NULL,
    action      VARCHAR(50)              NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version     BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_permissions                  PRIMARY KEY (id),
    CONSTRAINT uk_permissions_name             UNIQUE (name),
    CONSTRAINT uk_permissions_resource_action  UNIQUE (resource, action)
);

CREATE INDEX IF NOT EXISTS idx_permissions_resource ON permissions (resource);

-- ── 3. Role-to-Permission Mapping Table ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS role_permissions (
    id            VARCHAR(36)              NOT NULL,
    role_id       VARCHAR(36)              NOT NULL,
    permission_id VARCHAR(36)              NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_role_permissions          PRIMARY KEY (id),
    CONSTRAINT uk_role_permissions_role_perm UNIQUE (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role     FOREIGN KEY (role_id)
                                                REFERENCES roles (id)
                                                ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_perm     FOREIGN KEY (permission_id)
                                                REFERENCES permissions (id)
                                                ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_role_permissions_role_id ON role_permissions (role_id);
CREATE INDEX IF NOT EXISTS idx_role_permissions_perm_id ON role_permissions (permission_id);

-- ── 4. Organization Scoped Role Assignments Table ─────────────────────────────
CREATE TABLE IF NOT EXISTS organization_role_assignments (
    id                VARCHAR(36)              NOT NULL,
    organization_id   VARCHAR(36)              NOT NULL,
    user_id           VARCHAR(36)              NOT NULL,
    role_id           VARCHAR(36)              NOT NULL,
    role_scope        VARCHAR(30)              NOT NULL DEFAULT 'ORGANIZATION',
    assigned_by       VARCHAR(36)              NOT NULL,
    assigned_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at        TIMESTAMP WITH TIME ZONE,
    revoked_by        VARCHAR(36),
    revoked_at        TIMESTAMP WITH TIME ZONE,
    revocation_reason VARCHAR(255),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_org_role_assignments              PRIMARY KEY (id),
    CONSTRAINT fk_org_role_assignments_org          FOREIGN KEY (organization_id)
                                                        REFERENCES organizations (id)
                                                        ON DELETE RESTRICT,
    CONSTRAINT fk_org_role_assignments_user         FOREIGN KEY (user_id)
                                                        REFERENCES users (id)
                                                        ON DELETE RESTRICT,
    CONSTRAINT fk_org_role_assignments_org_user     FOREIGN KEY (organization_id, user_id)
                                                        REFERENCES organization_members (organization_id, user_id)
                                                        ON DELETE CASCADE,
    CONSTRAINT chk_org_role_assignments_scope       CHECK (role_scope = 'ORGANIZATION'),
    CONSTRAINT fk_org_role_assignments_role_scope   FOREIGN KEY (role_id, role_scope)
                                                        REFERENCES roles (id, scope)
                                                        ON DELETE RESTRICT,
    CONSTRAINT fk_org_role_assignments_assigned_by  FOREIGN KEY (assigned_by)
                                                        REFERENCES users (id)
                                                        ON DELETE RESTRICT,
    CONSTRAINT fk_org_role_assignments_revoked_by   FOREIGN KEY (revoked_by)
                                                        REFERENCES users (id)
                                                        ON DELETE RESTRICT
);

-- Active assignment uniqueness: prevents duplicate active roles for same (org, user, role)
CREATE UNIQUE INDEX IF NOT EXISTS uk_org_role_assignments_active
    ON organization_role_assignments (organization_id, user_id, role_id)
    WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_org_role_assignments_org_id   ON organization_role_assignments (organization_id);
CREATE INDEX IF NOT EXISTS idx_org_role_assignments_user_id  ON organization_role_assignments (user_id);
CREATE INDEX IF NOT EXISTS idx_org_role_assignments_org_user ON organization_role_assignments (organization_id, user_id);
CREATE INDEX IF NOT EXISTS idx_org_role_assignments_role_id  ON organization_role_assignments (role_id);

-- ── 5. Project Scoped Role Assignments Table ──────────────────────────────────
CREATE TABLE IF NOT EXISTS project_role_assignments (
    id                VARCHAR(36)              NOT NULL,
    project_id        VARCHAR(36)              NOT NULL,
    organization_id   VARCHAR(36)              NOT NULL,
    user_id           VARCHAR(36)              NOT NULL,
    role_id           VARCHAR(36)              NOT NULL,
    role_scope        VARCHAR(30)              NOT NULL DEFAULT 'PROJECT',
    assigned_by       VARCHAR(36)              NOT NULL,
    assigned_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at        TIMESTAMP WITH TIME ZONE,
    revoked_by        VARCHAR(36),
    revoked_at        TIMESTAMP WITH TIME ZONE,
    revocation_reason VARCHAR(255),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_project_role_assignments             PRIMARY KEY (id),
    CONSTRAINT fk_project_role_assignments_org         FOREIGN KEY (organization_id)
                                                           REFERENCES organizations (id)
                                                           ON DELETE RESTRICT,
    CONSTRAINT fk_project_role_assignments_user        FOREIGN KEY (user_id)
                                                           REFERENCES users (id)
                                                           ON DELETE RESTRICT,
    CONSTRAINT fk_project_role_assignments_org_user    FOREIGN KEY (organization_id, user_id)
                                                           REFERENCES organization_members (organization_id, user_id)
                                                           ON DELETE CASCADE,
    CONSTRAINT chk_project_role_assignments_scope      CHECK (role_scope = 'PROJECT'),
    CONSTRAINT fk_project_role_assignments_role_scope  FOREIGN KEY (role_id, role_scope)
                                                           REFERENCES roles (id, scope)
                                                           ON DELETE RESTRICT,
    CONSTRAINT fk_project_role_assignments_assigned_by FOREIGN KEY (assigned_by)
                                                           REFERENCES users (id)
                                                           ON DELETE RESTRICT,
    CONSTRAINT fk_project_role_assignments_revoked_by  FOREIGN KEY (revoked_by)
                                                           REFERENCES users (id)
                                                           ON DELETE RESTRICT
);

-- Active assignment uniqueness: prevents duplicate active roles for same (project, user, role)
CREATE UNIQUE INDEX IF NOT EXISTS uk_project_role_assignments_active
    ON project_role_assignments (project_id, user_id, role_id)
    WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_project_role_assignments_project_id ON project_role_assignments (project_id);
CREATE INDEX IF NOT EXISTS idx_project_role_assignments_org_id     ON project_role_assignments (organization_id);
CREATE INDEX IF NOT EXISTS idx_project_role_assignments_user_id    ON project_role_assignments (user_id);
CREATE INDEX IF NOT EXISTS idx_project_role_assignments_proj_user  ON project_role_assignments (project_id, user_id);
CREATE INDEX IF NOT EXISTS idx_project_role_assignments_org_user   ON project_role_assignments (organization_id, user_id);
CREATE INDEX IF NOT EXISTS idx_project_role_assignments_role_id    ON project_role_assignments (role_id);

-- ── 6. Seed Canonical Roles Reference Data ───────────────────────────────────
INSERT INTO roles (id, name, scope, description, is_system, created_at, updated_at, version) VALUES
('184e8dbb-66fe-55c3-bd25-c3c47d49d520', 'PLATFORM_ADMIN', 'PLATFORM', 'Full deployment platform administrator with tenant provisioning and emergency access.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('04131dca-7f4c-5131-b77d-fc5f5034436d', 'PLATFORM_SUPPORT', 'PLATFORM', 'Read-only cross-tenant customer support and incident investigation.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('30fbdce8-5d3e-5025-9fba-3af23689668b', 'ORGANIZATION_OWNER', 'ORGANIZATION', 'Founding owner with complete organization governance, billing, and settings.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('8c830b56-e86c-5072-8471-2bd34e4e2522', 'ORGANIZATION_ADMIN', 'ORGANIZATION', 'Administrative access to manage organization members, roles, integrations, and projects.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('18a58295-a572-51fe-a0a7-b9882954faec', 'ORGANIZATION_MEMBER', 'ORGANIZATION', 'Standard member access for tasks, repositories, AI, and knowledge base.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('272e64a4-aac5-57cb-bd88-3309d7ce7909', 'ORGANIZATION_VIEWER', 'ORGANIZATION', 'Read-only access to organizational boards, dashboards, and non-sensitive data.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('50d48c19-396c-56b5-b175-d480bac033fa', 'ORGANIZATION_BILLING', 'ORGANIZATION', 'Access restricted exclusively to subscription, invoice history, and billing.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('baf57136-10e9-5505-9d80-bc335c0556b2', 'PROJECT_LEAD', 'PROJECT', 'Project management authority: settings, cycles, assignments, and boards.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('9386ad97-19bd-53c0-82fc-a172c746e5d7', 'PROJECT_MEMBER', 'PROJECT', 'Standard project contributor: tasks, repos, epics, and comments.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('2fd979fb-a361-5ff8-bb25-cd95d09dcd88', 'PROJECT_VIEWER', 'PROJECT', 'Read-only observer for project boards, tasks, and epics.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (name) DO NOTHING;

-- ── 7. Seed Canonical Permissions Reference Data ─────────────────────────────
INSERT INTO permissions (id, name, resource, action, description, created_at, updated_at, version) VALUES
('5ec268de-c8da-5fcd-bc1c-17559b103172', 'org.manage', 'org', 'manage', 'Manage organization settings, integrations, and domains.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('20860b43-466d-5aff-a0a4-3ac643b3bdd1', 'org.delete', 'org', 'delete', 'Delete the organization permanently.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('1afdfef8-58c3-5629-9ca2-b81727740b2c', 'member.invite', 'member', 'invite', 'Invite new members to the organization.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('5811c08e-6dbb-5abc-a65a-703fc6a7b621', 'member.remove', 'member', 'remove', 'Remove existing members from the organization.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('80f5deb6-d4e0-5d1c-a06e-d7b841086db6', 'project.read', 'project', 'read', 'View project details, boards, and roadmaps.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('bc07923e-f0f7-5f7d-bb4a-240afa9dd318', 'project.write', 'project', 'write', 'Modify project settings, cycles, and boards.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('a974c950-bbea-51aa-b0a3-540a10691e73', 'project.delete', 'project', 'delete', 'Delete or archive a project.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('11a204b3-9692-58af-af4d-19de36d76df6', 'task.read', 'task', 'read', 'View tasks, subtasks, and descriptions.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('20742d3c-fc9c-5dce-8168-d7cca87b72eb', 'task.create', 'task', 'create', 'Create new tasks and issue items.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('8dc75e02-5a19-5ad9-8e0c-de1bb3a3f2ef', 'task.write', 'task', 'write', 'Update task attributes, states, and descriptions.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('c4bbb67f-bde1-5e20-87da-d31d0be3c91c', 'task.assign', 'task', 'assign', 'Assign tasks to organization or project members.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('008dc2dd-3312-5160-b810-d3ac16923471', 'task.delete', 'task', 'delete', 'Delete tasks permanently or move to trash.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('1c18b3b4-470f-53b0-bd2a-9f43dc262458', 'cycle.manage', 'cycle', 'manage', 'Create, update, and close project sprint cycles.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('34c43532-d838-5a8e-badd-fc6a1fc4eae2', 'repo.read', 'repo', 'read', 'Read repository code, branches, and commit histories.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('2f9f1f40-9a10-5a09-832c-331685a8b3b2', 'repo.connect', 'repo', 'connect', 'Connect remote source control repositories.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('a4a13180-4f5a-5849-bb88-756d0bb259cb', 'repo.disconnect', 'repo', 'disconnect', 'Disconnect source control repositories.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('d296a80f-e2fb-5aaf-95c2-36cad4a5a666', 'repo.scan', 'repo', 'scan', 'Trigger security and vulnerability scans on repositories.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('a2cbfe42-022a-5a27-94c2-792a5b3c5b72', 'repo.configure', 'repo', 'configure', 'Configure repository intelligence settings and branches.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('8d7ca9aa-5435-59cd-9fb5-02b64beee308', 'ai.chat', 'ai', 'chat', 'Interact with DevFlow AI context and assistant.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('855f4154-cf11-58ca-a78e-d9acce316247', 'ai.review', 'ai', 'review', 'Request automated AI code review on commits or pull requests.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('de9d1dbf-9505-527b-b341-f7e6b3146ca2', 'ai.review.approve', 'ai', 'review.approve', 'Approve and publish AI code review summaries.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('5ee3fdc6-feb9-50a6-a83f-94deba41332f', 'ai.configure', 'ai', 'configure', 'Configure AI model routing, tokens, and prompt rules.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('56278e12-31e1-506d-a879-47ddcd737fd9', 'kb.read', 'kb', 'read', 'Read documentation and internal wiki articles.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('d56a02a7-832f-5373-83fd-ace9b375efca', 'kb.write', 'kb', 'write', 'Create and update documentation pages and notes.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('c21bf30b-6ee3-58cc-a1a6-6872e45d050e', 'kb.manage', 'kb', 'manage', 'Organize folder hierarchies and publishing states.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('2560e030-b3c5-5528-88ee-d991b87b668f', 'kb.delete', 'kb', 'delete', 'Delete documentation pages and wiki revisions.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('ea17c2ca-b481-50fd-8988-5e691524d9c4', 'analytics.view', 'analytics', 'view', 'View developer velocity and operational analytics.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('9ced8052-8349-5702-84dd-484c01719771', 'analytics.export', 'analytics', 'export', 'Export telemetry reports and velocity data.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('49fc46be-c716-5baf-9b00-215dc1721e5c', 'analytics.configure', 'analytics', 'configure', 'Configure metrics dashboards and KPI tracking.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('2c05baa1-c6d5-52ff-b16d-248b198c734b', 'automation.read', 'automation', 'read', 'View workflow automation rules and trigger logs.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('02f9c830-d5b5-5a5a-83d1-53a6350fff5a', 'automation.configure', 'automation', 'configure', 'Create and modify automation rules.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('baa8b8f5-bbda-57ba-92a5-fd17d536ed27', 'automation.delete', 'automation', 'delete', 'Delete workflow automation rules.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('bea94c40-84b7-5a3e-a60e-1021e508e0fa', 'comment.create', 'comment', 'create', 'Post comments on tasks and documents.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('58a25a1e-1fdb-56e7-933a-c21f55afe8f9', 'comment.edit', 'comment', 'edit', 'Edit own posted comments.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('858576d3-5a69-5288-83fc-416a27ee1801', 'comment.delete', 'comment', 'delete', 'Delete own posted comments.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('5402a0e7-1903-5616-a393-d364423ad2e4', 'comment.moderate', 'comment', 'moderate', 'Moderate, edit, or delete any comment thread.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('25ae3a6f-fb02-502a-8e20-b7d1b432a661', 'notification.configure', 'notification', 'configure', 'Configure notification routing and integrations.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('b1285d8b-6e79-55cc-b8a1-2c80583a89e8', 'notification.broadcast', 'notification', 'broadcast', 'Send broadcast announcements to organization members.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('32e98954-3a18-57e4-b880-c661b956f697', 'billing.view', 'billing', 'view', 'View subscription tier, usage, and invoices.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('d73d7a91-1071-579b-a3a7-0fbf18e8848b', 'billing.manage', 'billing', 'manage', 'Manage payment methods, upgrade tier, and cancel subscription.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('ff06229c-f4bb-535a-926b-582788c6de97', 'platform.tenant.provision', 'platform', 'tenant.provision', 'Provision new tenant organizations across deployment.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('646e79df-9153-5b1e-9250-1a0fed6a2fd9', 'platform.tenant.deprovision', 'platform', 'tenant.deprovision', 'Deprovision tenant organizations and purge data.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('65ec8b0a-f8db-53f6-9db7-6434a707b14a', 'platform.featureflag.manage', 'platform', 'featureflag.manage', 'Manage platform-wide feature flags and toggles.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('0ef817dc-b563-5fdd-bdc4-c82d4f87d4f7', 'platform.observability.read', 'platform', 'observability.read', 'Access platform metrics, traces, and system health logs.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('103f920e-ab82-51e9-b4a5-d86afb3a37f3', 'platform.audit.read', 'platform', 'audit.read', 'Inspect deployment-wide security audit trails.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('eae4c3bc-0bc0-5963-a008-bbe8e42e6f95', 'platform.support.case.read', 'platform', 'support.case.read', 'Read support case details and diagnostics.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('7470ff08-7581-5332-98f5-3039b53dd958', 'platform.support.tenant.read', 'platform', 'support.tenant.read', 'Read-only tenant inspection for customer support.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('01c93cee-2c2d-54a1-97bf-e9a39b02e5c3', 'platform.emergency_access.execute', 'platform', 'emergency_access.execute', 'Execute break-glass operational procedures.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (name) DO NOTHING;

-- ── 8. Seed Role-to-Permission Mappings ──────────────────────────────────────
INSERT INTO role_permissions (id, role_id, permission_id, created_at) VALUES
('8e050918-f351-5485-b235-f3f3725a7d90', '184e8dbb-66fe-55c3-bd25-c3c47d49d520', '103f920e-ab82-51e9-b4a5-d86afb3a37f3', CURRENT_TIMESTAMP),
('e74e2290-c5cd-5279-87c4-415c4340fc77', '184e8dbb-66fe-55c3-bd25-c3c47d49d520', '01c93cee-2c2d-54a1-97bf-e9a39b02e5c3', CURRENT_TIMESTAMP),
('a17e5890-9579-56e7-92c3-690c501adab7', '184e8dbb-66fe-55c3-bd25-c3c47d49d520', '65ec8b0a-f8db-53f6-9db7-6434a707b14a', CURRENT_TIMESTAMP),
('0b1d7641-82df-50c4-ad0b-98c2838f6096', '184e8dbb-66fe-55c3-bd25-c3c47d49d520', '0ef817dc-b563-5fdd-bdc4-c82d4f87d4f7', CURRENT_TIMESTAMP),
('27812557-d1a6-5682-b1f1-86147c509280', '184e8dbb-66fe-55c3-bd25-c3c47d49d520', 'eae4c3bc-0bc0-5963-a008-bbe8e42e6f95', CURRENT_TIMESTAMP),
('956c823e-f979-525d-8406-73016b9f4328', '184e8dbb-66fe-55c3-bd25-c3c47d49d520', '7470ff08-7581-5332-98f5-3039b53dd958', CURRENT_TIMESTAMP),
('34e888f7-4fd9-5a27-9c57-ea7b56046155', '184e8dbb-66fe-55c3-bd25-c3c47d49d520', '646e79df-9153-5b1e-9250-1a0fed6a2fd9', CURRENT_TIMESTAMP),
('c3d7fc7d-d01d-5c8f-b999-b1787bcf2cce', '184e8dbb-66fe-55c3-bd25-c3c47d49d520', 'ff06229c-f4bb-535a-926b-582788c6de97', CURRENT_TIMESTAMP),
('bbaefcee-3a00-559e-ac45-9480269da480', '04131dca-7f4c-5131-b77d-fc5f5034436d', 'eae4c3bc-0bc0-5963-a008-bbe8e42e6f95', CURRENT_TIMESTAMP),
('0ab98d37-0b26-5172-998e-d2d8a6b3ed8c', '04131dca-7f4c-5131-b77d-fc5f5034436d', '7470ff08-7581-5332-98f5-3039b53dd958', CURRENT_TIMESTAMP),
('b548cb60-cf72-5e4b-b4e2-98a6e94e4e2a', '30fbdce8-5d3e-5025-9fba-3af23689668b', '8d7ca9aa-5435-59cd-9fb5-02b64beee308', CURRENT_TIMESTAMP),
('24026be3-fd0e-58e1-800a-56534ed3cb17', '30fbdce8-5d3e-5025-9fba-3af23689668b', '5ee3fdc6-feb9-50a6-a83f-94deba41332f', CURRENT_TIMESTAMP),
('ae5b1ab9-5377-5645-ab48-5f27eabf5ae2', '30fbdce8-5d3e-5025-9fba-3af23689668b', '855f4154-cf11-58ca-a78e-d9acce316247', CURRENT_TIMESTAMP),
('309f9d53-1792-509b-91c2-15a305d50a24', '30fbdce8-5d3e-5025-9fba-3af23689668b', 'de9d1dbf-9505-527b-b341-f7e6b3146ca2', CURRENT_TIMESTAMP),
('d562b2c3-4970-5599-b477-5b0cb22dc6b4', '30fbdce8-5d3e-5025-9fba-3af23689668b', '49fc46be-c716-5baf-9b00-215dc1721e5c', CURRENT_TIMESTAMP),
('16752c01-3239-5608-9b43-67ae2196bb37', '30fbdce8-5d3e-5025-9fba-3af23689668b', '9ced8052-8349-5702-84dd-484c01719771', CURRENT_TIMESTAMP),
('60c9bd66-337a-5024-b8f7-ff982387906c', '30fbdce8-5d3e-5025-9fba-3af23689668b', 'ea17c2ca-b481-50fd-8988-5e691524d9c4', CURRENT_TIMESTAMP),
('82ad75a7-bce0-50fe-872b-011075e8b0bf', '30fbdce8-5d3e-5025-9fba-3af23689668b', '02f9c830-d5b5-5a5a-83d1-53a6350fff5a', CURRENT_TIMESTAMP),
('a9368b7f-2cd3-5cf7-94fe-c2fab3cc8a73', '30fbdce8-5d3e-5025-9fba-3af23689668b', 'baa8b8f5-bbda-57ba-92a5-fd17d536ed27', CURRENT_TIMESTAMP),
('0d55e4fe-13c2-50d0-8eb1-11086e85a7bf', '30fbdce8-5d3e-5025-9fba-3af23689668b', '2c05baa1-c6d5-52ff-b16d-248b198c734b', CURRENT_TIMESTAMP),
('9eb9e53c-cfa8-5f57-9689-a3006e2415fe', '30fbdce8-5d3e-5025-9fba-3af23689668b', 'd73d7a91-1071-579b-a3a7-0fbf18e8848b', CURRENT_TIMESTAMP),
('4e735a52-7027-5346-9194-dcb937676b37', '30fbdce8-5d3e-5025-9fba-3af23689668b', '32e98954-3a18-57e4-b880-c661b956f697', CURRENT_TIMESTAMP),
('f4673b59-dca9-5fb7-9cc6-94eaa356aa8f', '30fbdce8-5d3e-5025-9fba-3af23689668b', 'bea94c40-84b7-5a3e-a60e-1021e508e0fa', CURRENT_TIMESTAMP),
('02b3e106-71fb-51f3-813b-c4263cbd8a9d', '30fbdce8-5d3e-5025-9fba-3af23689668b', '5402a0e7-1903-5616-a393-d364423ad2e4', CURRENT_TIMESTAMP),
('e2c6a260-0eb7-5d2c-b3ee-d8711b0a7e93', '30fbdce8-5d3e-5025-9fba-3af23689668b', '1c18b3b4-470f-53b0-bd2a-9f43dc262458', CURRENT_TIMESTAMP),
('cb6191d5-8265-5556-89a0-1e01e5f90203', '30fbdce8-5d3e-5025-9fba-3af23689668b', '2560e030-b3c5-5528-88ee-d991b87b668f', CURRENT_TIMESTAMP),
('cfb6717c-14ec-52a2-b988-2a3bdb13ceca', '30fbdce8-5d3e-5025-9fba-3af23689668b', 'c21bf30b-6ee3-58cc-a1a6-6872e45d050e', CURRENT_TIMESTAMP),
('e118e565-364d-5018-9c05-7481bc7cc294', '30fbdce8-5d3e-5025-9fba-3af23689668b', '56278e12-31e1-506d-a879-47ddcd737fd9', CURRENT_TIMESTAMP),
('8e36df01-ab44-500b-890c-d7ab4b5d6c28', '30fbdce8-5d3e-5025-9fba-3af23689668b', 'd56a02a7-832f-5373-83fd-ace9b375efca', CURRENT_TIMESTAMP),
('e8bd64b9-5e78-555a-b61b-e3fcb68c6bfd', '30fbdce8-5d3e-5025-9fba-3af23689668b', '1afdfef8-58c3-5629-9ca2-b81727740b2c', CURRENT_TIMESTAMP),
('bffb32db-f8e3-52b5-9406-f8153f62e1ca', '30fbdce8-5d3e-5025-9fba-3af23689668b', '5811c08e-6dbb-5abc-a65a-703fc6a7b621', CURRENT_TIMESTAMP),
('db2fcb22-c8f1-5952-9710-4a2194db6cfa', '30fbdce8-5d3e-5025-9fba-3af23689668b', 'b1285d8b-6e79-55cc-b8a1-2c80583a89e8', CURRENT_TIMESTAMP),
('8f92abd3-e619-581d-aaa3-e6721986d72f', '30fbdce8-5d3e-5025-9fba-3af23689668b', '25ae3a6f-fb02-502a-8e20-b7d1b432a661', CURRENT_TIMESTAMP),
('df2c7e72-0dc6-5164-9932-4b2cd1dd3a7a', '30fbdce8-5d3e-5025-9fba-3af23689668b', '20860b43-466d-5aff-a0a4-3ac643b3bdd1', CURRENT_TIMESTAMP),
('0355c482-eeca-5f97-a3a6-5d125bc2980a', '30fbdce8-5d3e-5025-9fba-3af23689668b', '5ec268de-c8da-5fcd-bc1c-17559b103172', CURRENT_TIMESTAMP),
('d8252c4a-624d-56c3-a2c1-101145406915', '30fbdce8-5d3e-5025-9fba-3af23689668b', 'a974c950-bbea-51aa-b0a3-540a10691e73', CURRENT_TIMESTAMP),
('7e83d77d-5eb9-599b-a8b6-4bf141df2978', '30fbdce8-5d3e-5025-9fba-3af23689668b', '80f5deb6-d4e0-5d1c-a06e-d7b841086db6', CURRENT_TIMESTAMP),
('2f1a0e9c-7439-54d3-b078-16ffba4945b2', '30fbdce8-5d3e-5025-9fba-3af23689668b', 'bc07923e-f0f7-5f7d-bb4a-240afa9dd318', CURRENT_TIMESTAMP),
('3a2d7a50-1e1c-5202-a921-f4551dc8610c', '30fbdce8-5d3e-5025-9fba-3af23689668b', 'a2cbfe42-022a-5a27-94c2-792a5b3c5b72', CURRENT_TIMESTAMP),
('7a48e1f1-f4d2-512e-ab5a-0f2bdc573cf1', '30fbdce8-5d3e-5025-9fba-3af23689668b', '2f9f1f40-9a10-5a09-832c-331685a8b3b2', CURRENT_TIMESTAMP),
('480136bc-70cc-5ee0-9c87-2825fb380fd7', '30fbdce8-5d3e-5025-9fba-3af23689668b', 'a4a13180-4f5a-5849-bb88-756d0bb259cb', CURRENT_TIMESTAMP),
('18edaf6b-06e5-51a6-83a0-d599e541be38', '30fbdce8-5d3e-5025-9fba-3af23689668b', '34c43532-d838-5a8e-badd-fc6a1fc4eae2', CURRENT_TIMESTAMP),
('b407845d-738b-5bc4-bad3-304e60528e9c', '30fbdce8-5d3e-5025-9fba-3af23689668b', 'd296a80f-e2fb-5aaf-95c2-36cad4a5a666', CURRENT_TIMESTAMP),
('3b5276d4-5bc6-5a3d-a886-862ce88caf66', '30fbdce8-5d3e-5025-9fba-3af23689668b', 'c4bbb67f-bde1-5e20-87da-d31d0be3c91c', CURRENT_TIMESTAMP),
('72189413-82d9-5785-9eab-cfc0ca053049', '30fbdce8-5d3e-5025-9fba-3af23689668b', '20742d3c-fc9c-5dce-8168-d7cca87b72eb', CURRENT_TIMESTAMP),
('12e4f38f-0c6f-51bc-8dac-f0fdf6315272', '30fbdce8-5d3e-5025-9fba-3af23689668b', '008dc2dd-3312-5160-b810-d3ac16923471', CURRENT_TIMESTAMP),
('4157495f-3eed-5897-ad6e-01a9f2dc69b8', '30fbdce8-5d3e-5025-9fba-3af23689668b', '11a204b3-9692-58af-af4d-19de36d76df6', CURRENT_TIMESTAMP),
('95b42cbc-46c1-5ad3-8fd7-a197a459aeaa', '30fbdce8-5d3e-5025-9fba-3af23689668b', '8dc75e02-5a19-5ad9-8e0c-de1bb3a3f2ef', CURRENT_TIMESTAMP),
('1dc57927-7bdf-57c4-98b4-eacc0eb1f849', '8c830b56-e86c-5072-8471-2bd34e4e2522', '8d7ca9aa-5435-59cd-9fb5-02b64beee308', CURRENT_TIMESTAMP),
('6050318d-f39d-5938-9855-6f88e07c5488', '8c830b56-e86c-5072-8471-2bd34e4e2522', '5ee3fdc6-feb9-50a6-a83f-94deba41332f', CURRENT_TIMESTAMP),
('ccd57046-af85-5abe-931d-732fd4bcd5bd', '8c830b56-e86c-5072-8471-2bd34e4e2522', '855f4154-cf11-58ca-a78e-d9acce316247', CURRENT_TIMESTAMP),
('93c3ff22-dfb2-52de-8509-1965292d5825', '8c830b56-e86c-5072-8471-2bd34e4e2522', 'de9d1dbf-9505-527b-b341-f7e6b3146ca2', CURRENT_TIMESTAMP),
('99d951b7-4539-5c6f-b40f-48b68df89df1', '8c830b56-e86c-5072-8471-2bd34e4e2522', '49fc46be-c716-5baf-9b00-215dc1721e5c', CURRENT_TIMESTAMP),
('b489005a-b225-52d4-b1dd-81622b6c4507', '8c830b56-e86c-5072-8471-2bd34e4e2522', '9ced8052-8349-5702-84dd-484c01719771', CURRENT_TIMESTAMP),
('04999617-8f27-5bb9-ad28-123e4081f92b', '8c830b56-e86c-5072-8471-2bd34e4e2522', 'ea17c2ca-b481-50fd-8988-5e691524d9c4', CURRENT_TIMESTAMP),
('2ae1ad4d-9518-59bb-af55-d60e555fa76a', '8c830b56-e86c-5072-8471-2bd34e4e2522', '02f9c830-d5b5-5a5a-83d1-53a6350fff5a', CURRENT_TIMESTAMP),
('783ac5b5-47b3-5d0a-93cc-e98fd333f173', '8c830b56-e86c-5072-8471-2bd34e4e2522', 'baa8b8f5-bbda-57ba-92a5-fd17d536ed27', CURRENT_TIMESTAMP),
('401a2a3d-4c4d-5271-bb6b-47f20892e0c1', '8c830b56-e86c-5072-8471-2bd34e4e2522', '2c05baa1-c6d5-52ff-b16d-248b198c734b', CURRENT_TIMESTAMP),
('8163aa5c-1116-55f0-a05a-f7b46adaa208', '8c830b56-e86c-5072-8471-2bd34e4e2522', 'bea94c40-84b7-5a3e-a60e-1021e508e0fa', CURRENT_TIMESTAMP),
('f9c16b29-2065-52c8-98da-94d17b7135c8', '8c830b56-e86c-5072-8471-2bd34e4e2522', '5402a0e7-1903-5616-a393-d364423ad2e4', CURRENT_TIMESTAMP),
('bc9cd6bd-0dc0-552d-8a2b-18f582aa91d7', '8c830b56-e86c-5072-8471-2bd34e4e2522', '1c18b3b4-470f-53b0-bd2a-9f43dc262458', CURRENT_TIMESTAMP),
('244320f2-af22-5872-af89-29e1976c8eeb', '8c830b56-e86c-5072-8471-2bd34e4e2522', '2560e030-b3c5-5528-88ee-d991b87b668f', CURRENT_TIMESTAMP),
('0d8f5960-1672-5577-8ba9-2eb0d66773d3', '8c830b56-e86c-5072-8471-2bd34e4e2522', 'c21bf30b-6ee3-58cc-a1a6-6872e45d050e', CURRENT_TIMESTAMP),
('67faef5e-f04e-5612-be5b-f73b8030054f', '8c830b56-e86c-5072-8471-2bd34e4e2522', '56278e12-31e1-506d-a879-47ddcd737fd9', CURRENT_TIMESTAMP),
('7f94f0ba-25de-5dfb-ad53-86d36d430908', '8c830b56-e86c-5072-8471-2bd34e4e2522', 'd56a02a7-832f-5373-83fd-ace9b375efca', CURRENT_TIMESTAMP),
('9279c0e4-6995-55ce-8659-9d0160dd27b8', '8c830b56-e86c-5072-8471-2bd34e4e2522', '1afdfef8-58c3-5629-9ca2-b81727740b2c', CURRENT_TIMESTAMP),
('2d17fb2c-d8e4-5e3d-94b8-18fb715053ff', '8c830b56-e86c-5072-8471-2bd34e4e2522', '5811c08e-6dbb-5abc-a65a-703fc6a7b621', CURRENT_TIMESTAMP),
('9ae8425e-93b8-5f36-bcc8-1d36780d92e6', '8c830b56-e86c-5072-8471-2bd34e4e2522', 'b1285d8b-6e79-55cc-b8a1-2c80583a89e8', CURRENT_TIMESTAMP),
('43465f7f-5391-54dc-95a9-23318f122e7d', '8c830b56-e86c-5072-8471-2bd34e4e2522', '25ae3a6f-fb02-502a-8e20-b7d1b432a661', CURRENT_TIMESTAMP),
('fd76e07a-433a-5c68-a625-0790b24a6ef8', '8c830b56-e86c-5072-8471-2bd34e4e2522', 'a974c950-bbea-51aa-b0a3-540a10691e73', CURRENT_TIMESTAMP),
('e872e557-e2ea-5078-9e9e-bec57cc3e0b6', '8c830b56-e86c-5072-8471-2bd34e4e2522', '80f5deb6-d4e0-5d1c-a06e-d7b841086db6', CURRENT_TIMESTAMP),
('3ab3a492-4453-5dc2-a014-9422a30e7631', '8c830b56-e86c-5072-8471-2bd34e4e2522', 'bc07923e-f0f7-5f7d-bb4a-240afa9dd318', CURRENT_TIMESTAMP),
('b0472df6-4da3-5516-ad32-14fa55677ebd', '8c830b56-e86c-5072-8471-2bd34e4e2522', 'a2cbfe42-022a-5a27-94c2-792a5b3c5b72', CURRENT_TIMESTAMP),
('c9e17bee-4ecd-554a-bd7e-9c20d4447575', '8c830b56-e86c-5072-8471-2bd34e4e2522', '2f9f1f40-9a10-5a09-832c-331685a8b3b2', CURRENT_TIMESTAMP),
('e9cf952d-985f-5ac4-a90b-ab349f863afb', '8c830b56-e86c-5072-8471-2bd34e4e2522', 'a4a13180-4f5a-5849-bb88-756d0bb259cb', CURRENT_TIMESTAMP),
('3c3f8f49-84e9-5a11-a988-6747f9086f7b', '8c830b56-e86c-5072-8471-2bd34e4e2522', '34c43532-d838-5a8e-badd-fc6a1fc4eae2', CURRENT_TIMESTAMP),
('340eafb3-0087-5c5c-bb62-74901b1a7037', '8c830b56-e86c-5072-8471-2bd34e4e2522', 'd296a80f-e2fb-5aaf-95c2-36cad4a5a666', CURRENT_TIMESTAMP),
('7a2320c0-c398-5ef8-949f-5f5086b1304c', '8c830b56-e86c-5072-8471-2bd34e4e2522', 'c4bbb67f-bde1-5e20-87da-d31d0be3c91c', CURRENT_TIMESTAMP),
('3dc129ba-4641-5cfe-8ae0-8ca71e9e4298', '8c830b56-e86c-5072-8471-2bd34e4e2522', '20742d3c-fc9c-5dce-8168-d7cca87b72eb', CURRENT_TIMESTAMP),
('5a5c9cc7-669e-599a-82c8-50d91abf4be2', '8c830b56-e86c-5072-8471-2bd34e4e2522', '008dc2dd-3312-5160-b810-d3ac16923471', CURRENT_TIMESTAMP),
('cf281487-6d8c-5327-b948-10ef28232e0e', '8c830b56-e86c-5072-8471-2bd34e4e2522', '11a204b3-9692-58af-af4d-19de36d76df6', CURRENT_TIMESTAMP),
('1051995b-8531-54a2-84e4-9a840b917370', '8c830b56-e86c-5072-8471-2bd34e4e2522', '8dc75e02-5a19-5ad9-8e0c-de1bb3a3f2ef', CURRENT_TIMESTAMP),
('84543e9a-edb4-5b16-abc9-65492f537c69', '18a58295-a572-51fe-a0a7-b9882954faec', '8d7ca9aa-5435-59cd-9fb5-02b64beee308', CURRENT_TIMESTAMP),
('d3738f41-d0f7-5a59-a552-73fd8f833ebd', '18a58295-a572-51fe-a0a7-b9882954faec', '855f4154-cf11-58ca-a78e-d9acce316247', CURRENT_TIMESTAMP),
('94489c1b-a607-5b7b-87cc-33c392aeab0c', '18a58295-a572-51fe-a0a7-b9882954faec', 'ea17c2ca-b481-50fd-8988-5e691524d9c4', CURRENT_TIMESTAMP),
('cca4106c-f8f2-5afc-9751-d241d1e57305', '18a58295-a572-51fe-a0a7-b9882954faec', '2c05baa1-c6d5-52ff-b16d-248b198c734b', CURRENT_TIMESTAMP),
('d14803fc-c5ca-5700-b636-9aa6cfcb386f', '18a58295-a572-51fe-a0a7-b9882954faec', 'bea94c40-84b7-5a3e-a60e-1021e508e0fa', CURRENT_TIMESTAMP),
('a6cd8eb4-fab8-5ae8-8887-44ecd30934a7', '18a58295-a572-51fe-a0a7-b9882954faec', '56278e12-31e1-506d-a879-47ddcd737fd9', CURRENT_TIMESTAMP),
('c103cfda-a422-5485-8497-8a0591c9ec1e', '18a58295-a572-51fe-a0a7-b9882954faec', 'd56a02a7-832f-5373-83fd-ace9b375efca', CURRENT_TIMESTAMP),
('ba2c9eab-6b6b-5aa2-a1ad-7474d0168267', '18a58295-a572-51fe-a0a7-b9882954faec', '80f5deb6-d4e0-5d1c-a06e-d7b841086db6', CURRENT_TIMESTAMP),
('bb53ce76-e90b-5bd4-9894-be72a40ca52c', '18a58295-a572-51fe-a0a7-b9882954faec', 'bc07923e-f0f7-5f7d-bb4a-240afa9dd318', CURRENT_TIMESTAMP),
('3c220bbe-ed2d-5313-93fa-03360ed85e4c', '18a58295-a572-51fe-a0a7-b9882954faec', '34c43532-d838-5a8e-badd-fc6a1fc4eae2', CURRENT_TIMESTAMP),
('9afcb03e-3494-5a9b-abea-39a46d21564a', '18a58295-a572-51fe-a0a7-b9882954faec', 'd296a80f-e2fb-5aaf-95c2-36cad4a5a666', CURRENT_TIMESTAMP),
('9eb9b1f9-f740-5e22-a190-584ff7fc9db6', '18a58295-a572-51fe-a0a7-b9882954faec', 'c4bbb67f-bde1-5e20-87da-d31d0be3c91c', CURRENT_TIMESTAMP),
('027edef8-cd7d-5f2a-a05d-535fb7a962e7', '18a58295-a572-51fe-a0a7-b9882954faec', '20742d3c-fc9c-5dce-8168-d7cca87b72eb', CURRENT_TIMESTAMP),
('5896844d-3b18-5b3e-a021-e43bde0ebbb1', '18a58295-a572-51fe-a0a7-b9882954faec', '11a204b3-9692-58af-af4d-19de36d76df6', CURRENT_TIMESTAMP),
('7fc71bc2-16eb-5c68-bc77-e9b68957ee34', '18a58295-a572-51fe-a0a7-b9882954faec', '8dc75e02-5a19-5ad9-8e0c-de1bb3a3f2ef', CURRENT_TIMESTAMP),
('2747ceed-e15a-50d6-864a-705562820434', '272e64a4-aac5-57cb-bd88-3309d7ce7909', 'ea17c2ca-b481-50fd-8988-5e691524d9c4', CURRENT_TIMESTAMP),
('c131df02-f9ed-5bab-9473-e7dfbf6fa1c3', '272e64a4-aac5-57cb-bd88-3309d7ce7909', '56278e12-31e1-506d-a879-47ddcd737fd9', CURRENT_TIMESTAMP),
('b009f7a4-4169-5134-beba-e58d25300521', '272e64a4-aac5-57cb-bd88-3309d7ce7909', '80f5deb6-d4e0-5d1c-a06e-d7b841086db6', CURRENT_TIMESTAMP),
('5536a92f-f057-5c8e-bf35-bbaebce501ad', '272e64a4-aac5-57cb-bd88-3309d7ce7909', '34c43532-d838-5a8e-badd-fc6a1fc4eae2', CURRENT_TIMESTAMP),
('7533e416-7278-5ce0-b0a1-4f4e4bb8807f', '272e64a4-aac5-57cb-bd88-3309d7ce7909', '11a204b3-9692-58af-af4d-19de36d76df6', CURRENT_TIMESTAMP),
('6ab5e4ef-a9aa-5c5e-99d5-8cae01f30f7c', '50d48c19-396c-56b5-b175-d480bac033fa', 'd73d7a91-1071-579b-a3a7-0fbf18e8848b', CURRENT_TIMESTAMP),
('80b54b9e-71d8-517b-9388-e51a8f1aa741', '50d48c19-396c-56b5-b175-d480bac033fa', '32e98954-3a18-57e4-b880-c661b956f697', CURRENT_TIMESTAMP),
('074aa6f2-e9e9-56f9-bff4-3b72e5c21528', 'baf57136-10e9-5505-9d80-bc335c0556b2', '8d7ca9aa-5435-59cd-9fb5-02b64beee308', CURRENT_TIMESTAMP),
('dedf10cf-6169-5871-b0d8-1420cbea6f85', 'baf57136-10e9-5505-9d80-bc335c0556b2', '855f4154-cf11-58ca-a78e-d9acce316247', CURRENT_TIMESTAMP),
('461ec9f2-ea04-5fb7-809b-dedc61a6caf7', 'baf57136-10e9-5505-9d80-bc335c0556b2', 'de9d1dbf-9505-527b-b341-f7e6b3146ca2', CURRENT_TIMESTAMP),
('1a295fd9-e73e-5c63-b63a-8714312809db', 'baf57136-10e9-5505-9d80-bc335c0556b2', '9ced8052-8349-5702-84dd-484c01719771', CURRENT_TIMESTAMP),
('6b35788f-b1ba-558d-94c3-06448691e3fe', 'baf57136-10e9-5505-9d80-bc335c0556b2', 'ea17c2ca-b481-50fd-8988-5e691524d9c4', CURRENT_TIMESTAMP),
('2669716b-d768-5316-b5ba-de80dffb9a06', 'baf57136-10e9-5505-9d80-bc335c0556b2', '02f9c830-d5b5-5a5a-83d1-53a6350fff5a', CURRENT_TIMESTAMP),
('b786c084-b558-5ed4-a33d-d49f5b6ee832', 'baf57136-10e9-5505-9d80-bc335c0556b2', '2c05baa1-c6d5-52ff-b16d-248b198c734b', CURRENT_TIMESTAMP),
('209d485a-a9c9-50f8-aeae-419926d68e52', 'baf57136-10e9-5505-9d80-bc335c0556b2', 'bea94c40-84b7-5a3e-a60e-1021e508e0fa', CURRENT_TIMESTAMP),
('09381841-6def-58fc-b3e2-22c5c43ee20e', 'baf57136-10e9-5505-9d80-bc335c0556b2', '5402a0e7-1903-5616-a393-d364423ad2e4', CURRENT_TIMESTAMP),
('2c5337dd-69da-57c0-b5a9-0eff22cea13d', 'baf57136-10e9-5505-9d80-bc335c0556b2', '1c18b3b4-470f-53b0-bd2a-9f43dc262458', CURRENT_TIMESTAMP),
('a8cac428-11e6-57ab-867d-d87f21e0026c', 'baf57136-10e9-5505-9d80-bc335c0556b2', '2560e030-b3c5-5528-88ee-d991b87b668f', CURRENT_TIMESTAMP),
('4dab8156-59d1-5c1c-82d1-3ee4d5a86475', 'baf57136-10e9-5505-9d80-bc335c0556b2', 'c21bf30b-6ee3-58cc-a1a6-6872e45d050e', CURRENT_TIMESTAMP),
('b1e174c8-f03b-5545-9d35-b41425e970ad', 'baf57136-10e9-5505-9d80-bc335c0556b2', '56278e12-31e1-506d-a879-47ddcd737fd9', CURRENT_TIMESTAMP),
('e89b4c19-3a9e-5dbc-8194-d51758dbeb56', 'baf57136-10e9-5505-9d80-bc335c0556b2', 'd56a02a7-832f-5373-83fd-ace9b375efca', CURRENT_TIMESTAMP),
('272a490c-29e0-5fd0-aed9-39740ed26da1', 'baf57136-10e9-5505-9d80-bc335c0556b2', 'a974c950-bbea-51aa-b0a3-540a10691e73', CURRENT_TIMESTAMP),
('f779e07b-09a1-5f77-a899-ab67666e645d', 'baf57136-10e9-5505-9d80-bc335c0556b2', '80f5deb6-d4e0-5d1c-a06e-d7b841086db6', CURRENT_TIMESTAMP),
('3cc97277-dfe3-5e57-9ed7-e6e48b09e99b', 'baf57136-10e9-5505-9d80-bc335c0556b2', 'bc07923e-f0f7-5f7d-bb4a-240afa9dd318', CURRENT_TIMESTAMP),
('d39f2408-74bb-531d-a999-7f035be1a6f1', 'baf57136-10e9-5505-9d80-bc335c0556b2', '34c43532-d838-5a8e-badd-fc6a1fc4eae2', CURRENT_TIMESTAMP),
('473529cb-5cf3-5bcb-9059-70ff315e158d', 'baf57136-10e9-5505-9d80-bc335c0556b2', 'd296a80f-e2fb-5aaf-95c2-36cad4a5a666', CURRENT_TIMESTAMP),
('1a7203f8-193a-5b3c-ac8b-8bd6fe870767', 'baf57136-10e9-5505-9d80-bc335c0556b2', 'c4bbb67f-bde1-5e20-87da-d31d0be3c91c', CURRENT_TIMESTAMP),
('8b6adf13-53c2-52f5-af9e-5282c03ccf29', 'baf57136-10e9-5505-9d80-bc335c0556b2', '20742d3c-fc9c-5dce-8168-d7cca87b72eb', CURRENT_TIMESTAMP),
('96b58dd2-de0d-5d8b-906e-69c0bb7262fb', 'baf57136-10e9-5505-9d80-bc335c0556b2', '008dc2dd-3312-5160-b810-d3ac16923471', CURRENT_TIMESTAMP),
('aedeabbd-66b6-5ace-b47f-e494ad800bc6', 'baf57136-10e9-5505-9d80-bc335c0556b2', '11a204b3-9692-58af-af4d-19de36d76df6', CURRENT_TIMESTAMP),
('68cb5884-20fa-5fed-9c7b-24c71ae70ca4', 'baf57136-10e9-5505-9d80-bc335c0556b2', '8dc75e02-5a19-5ad9-8e0c-de1bb3a3f2ef', CURRENT_TIMESTAMP),
('847e7744-865e-5623-9777-c52bfbc19cab', '9386ad97-19bd-53c0-82fc-a172c746e5d7', '8d7ca9aa-5435-59cd-9fb5-02b64beee308', CURRENT_TIMESTAMP),
('6a94d5cf-ff81-50d1-b0d4-f406b2bb78de', '9386ad97-19bd-53c0-82fc-a172c746e5d7', '855f4154-cf11-58ca-a78e-d9acce316247', CURRENT_TIMESTAMP),
('21b5c8dd-dd53-568b-a7e9-4cddaee56c2b', '9386ad97-19bd-53c0-82fc-a172c746e5d7', 'ea17c2ca-b481-50fd-8988-5e691524d9c4', CURRENT_TIMESTAMP),
('6c77b93c-61d4-5529-aeb7-8c92c0efb3e1', '9386ad97-19bd-53c0-82fc-a172c746e5d7', '2c05baa1-c6d5-52ff-b16d-248b198c734b', CURRENT_TIMESTAMP),
('7c0e9ce6-5f0c-5ced-b391-061e41993abe', '9386ad97-19bd-53c0-82fc-a172c746e5d7', 'bea94c40-84b7-5a3e-a60e-1021e508e0fa', CURRENT_TIMESTAMP),
('881ca629-d4a9-5c39-b390-f92e29cda048', '9386ad97-19bd-53c0-82fc-a172c746e5d7', '56278e12-31e1-506d-a879-47ddcd737fd9', CURRENT_TIMESTAMP),
('b9d5a528-975b-56b2-889e-891580b5d4d6', '9386ad97-19bd-53c0-82fc-a172c746e5d7', 'd56a02a7-832f-5373-83fd-ace9b375efca', CURRENT_TIMESTAMP),
('a00336f6-5b1c-58c4-9352-94b3f6b60572', '9386ad97-19bd-53c0-82fc-a172c746e5d7', '80f5deb6-d4e0-5d1c-a06e-d7b841086db6', CURRENT_TIMESTAMP),
('a88c019b-5bf8-5c4e-9966-cc80eb63df00', '9386ad97-19bd-53c0-82fc-a172c746e5d7', '34c43532-d838-5a8e-badd-fc6a1fc4eae2', CURRENT_TIMESTAMP),
('a41ee561-2130-51c3-aaf1-068ab91f6070', '9386ad97-19bd-53c0-82fc-a172c746e5d7', 'd296a80f-e2fb-5aaf-95c2-36cad4a5a666', CURRENT_TIMESTAMP),
('60cee6e0-46d2-5ab6-80db-ae34602a0e73', '9386ad97-19bd-53c0-82fc-a172c746e5d7', '20742d3c-fc9c-5dce-8168-d7cca87b72eb', CURRENT_TIMESTAMP),
('5ae55a03-b010-5f1a-aaf7-d779ca7554fe', '9386ad97-19bd-53c0-82fc-a172c746e5d7', '11a204b3-9692-58af-af4d-19de36d76df6', CURRENT_TIMESTAMP),
('f0d90c8f-7634-5984-91da-da4874d9f0b2', '9386ad97-19bd-53c0-82fc-a172c746e5d7', '8dc75e02-5a19-5ad9-8e0c-de1bb3a3f2ef', CURRENT_TIMESTAMP),
('2cfc2b8b-6bb5-5431-a7bf-7b8a8a5b0c3e', '2fd979fb-a361-5ff8-bb25-cd95d09dcd88', 'ea17c2ca-b481-50fd-8988-5e691524d9c4', CURRENT_TIMESTAMP),
('1a133b40-7ddd-567c-b16f-9b639a1affef', '2fd979fb-a361-5ff8-bb25-cd95d09dcd88', '56278e12-31e1-506d-a879-47ddcd737fd9', CURRENT_TIMESTAMP),
('87c53d55-25ee-5028-800d-8be4c6463a81', '2fd979fb-a361-5ff8-bb25-cd95d09dcd88', '80f5deb6-d4e0-5d1c-a06e-d7b841086db6', CURRENT_TIMESTAMP),
('3c9c0abb-c856-5ffe-a82a-026e90b5b423', '2fd979fb-a361-5ff8-bb25-cd95d09dcd88', '11a204b3-9692-58af-af4d-19de36d76df6', CURRENT_TIMESTAMP)
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ── 9. Deterministic Backfill of Existing Active Organization Memberships ─────
INSERT INTO organization_role_assignments (
    id,
    organization_id,
    user_id,
    role_id,
    role_scope,
    assigned_by,
    assigned_at,
    created_at,
    updated_at,
    version
)
SELECT
    gen_random_uuid()::varchar(36),
    om.organization_id,
    om.user_id,
    CASE om.role
        WHEN 'OWNER' THEN '30fbdce8-5d3e-5025-9fba-3af23689668b'
        WHEN 'ADMIN' THEN '8c830b56-e86c-5072-8471-2bd34e4e2522'
        WHEN 'DEVELOPER' THEN '18a58295-a572-51fe-a0a7-b9882954faec'
        WHEN 'VIEWER' THEN '272e64a4-aac5-57cb-bd88-3309d7ce7909'
    END,
    'ORGANIZATION',
    COALESCE(o.owner_id, om.user_id),
    COALESCE(om.joined_at, om.created_at, CURRENT_TIMESTAMP),
    COALESCE(om.created_at, CURRENT_TIMESTAMP),
    COALESCE(om.updated_at, CURRENT_TIMESTAMP),
    0
FROM organization_members om
JOIN organizations o ON om.organization_id = o.id
WHERE om.status = 'ACTIVE'
ON CONFLICT DO NOTHING;
