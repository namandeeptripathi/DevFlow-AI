# DevFlow — Database Architecture Specification

> **Version:** 1.0.0  
> **Status:** Approved / Architecture Review Board (ARB) Signed Off  
> **Author:** Principal Database Architect  
> **Date:** 2026-07-28  
> **Classification:** Internal — Engineering

---

## 1. Purpose

This document specifies the **Database Architecture** for DevFlow, an AI-First Engineering Intelligence & Delivery Platform. In a Modular Monolith architecture, database-level organization must mirror module boundaries to prevent logical separation from breaking down into a coupled database layer.

The goals of this database architecture are:
- **Scalability:** Design schema configurations that scale reads and writes independently, supporting read-replicas, table partitioning, and connection pooling.
- **Maintainability:** Isolate table ownership within modular boundaries, ensuring that schema alterations in one module do not break operations in others.
- **Module Isolation:** Enforce a strict "no cross-module database join" rule. Access to external data occurs strictly via public API interfaces or asynchronous domain events.
- **Consistency:** Maintain transaction boundaries within single aggregate roots, relying on event-driven eventual consistency to coordinate state updates across different module contexts.
- **Future Evolution:** Structure database schemas so they can be migrated from a single shared instance into isolated, service-specific databases (microservices) with zero code refactoring.

---

## 2. Database Technology

DevFlow utilizes **PostgreSQL 16** as its primary transactional database.

### 2.1 Core Technology Selections

| Technology | Purpose | Architectural Justification |
| :--- | :--- | :--- |
| **PostgreSQL 16** | Primary Datastore | Offers advanced relational structures, logical schema partitioning, row-level security (RLS), ACID transactions, and JSONB capabilities. |
| **pgvector Extension** | Vector Search | Integrates semantic search directly inside the primary relational datastore. Stores and queries high-dimensional document embeddings (e.g., 1536-dimensional vectors) in a single ACID-compliant database instance, reducing initial infrastructure complexity. |
| **JSONB Fields** | Semi-Structured Data | Used for highly dynamic payloads such as prompt metadata, automation rule conditions/actions, and user notifications. `JSONB` is selected over raw `TEXT` because it parses binary representations, allows document validation, and supports indexing. |
| **UUID Keys** | Unique Identifiers | UUIDs are used as primary keys across all tables. This supports distributed key generation, offline key generation, secure random identifier exposure, and prevents auto-increment primary key leaks. |
| **HikariCP** | Connection Pooling | The default connection pooling engine, configured with strict timeouts and sizing metrics matching the thread pools of the Java virtual thread environment. |

### 2.2 Time Zone Policy
- **UTC Enforcement:** All timestamps must be stored using the `TIMESTAMP WITH TIME ZONE` (TIMESTAMPTZ) type.
- **Local Conversion:** The database stores all timestamp values in Universal Time Coordinated (UTC). Timezone conversion and localized formatting are delegated entirely to the presentation layer (frontend clients).

---

## 3. Schema Organization

To maintain modular boundaries, the database is partitioned into nine logical **PostgreSQL Schemas**. Each schema aligns with a Bounded Context defined in the Domain Model and is owned exclusively by its respective module.

```
┌────────────────────────────────────────────────────────────────────────┐
│                          Single PostgreSQL Database                    │
│                                                                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌────────────┐  │
│  │ Schema: auth │  │ Schema: pm   │  │ Schema: repo │  │ Schema: ai │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  └────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌────────────┐  │
│  │ Schema: kb   │  │ Schema: anlyt│  │ Schema: auto │  │ Schema: col│  │
│  └──────────────┘  └──────────────┘  └──────────────┘  └────────────┘  │
│                    ┌──────────────┐                                    │
│                    │ Schema: notif│                                    │
│                    └──────────────┘                                    │
└────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Schema Directory

1. **`auth` Schema**
   - **Owner:** `devflow-auth` module.
   - **Purpose:** Persists users, organization details, workspace memberships, and third-party integrations OAuth credentials.
   - **Isolation Rules:** Holds the core tenant and identity definitions. External modules query this schema via compile-time API interfaces (`AuthApi`) to validate access rights.
2. **`pm` Schema**
   - **Owner:** `devflow-project-management` module.
   - **Purpose:** Persists boards, columns, backlogs, tasks, sprints/cycles, and epics.
   - **Isolation Rules:** No foreign keys link this schema to `auth` or `repo`. Referencing users (assignees) or git resources (commits) occurs via simple UUID columns.
3. **`repo` Schema**
   - **Owner:** `devflow-repository-intelligence` module.
   - **Purpose:** Persists registered code repositories, Git commit histories, branch lists, pull requests, and code reviews.
   - **Isolation Rules:** Read-only structures optimized for Git synchronization runs.
4. **`ai_engine` Schema**
   - **Owner:** `devflow-ai-engine` module.
   - **Purpose:** Persists chat sessions, messages, and prompt templates.
   - **Isolation Rules:** Isolated from the core project schemas. Inter-module data context is ingested as text fields to build LLM context payloads.
5. **`kb` Schema**
   - **Owner:** `devflow-knowledge-base` module.
   - **Purpose:** Persists wiki documents, page histories, folders, and pgvector embeddings.
   - **Isolation Rules:** Embeddings are isolated inside this schema to ensure vector matching is scoped to search constraints.
6. **`developer_analytics` Schema**
   - **Owner:** `devflow-developer-analytics` module.
   - **Purpose:** Stores read-only metric snapshots, historical velocity metrics, and DORA scores.
   - **Isolation Rules:** Write operations are strictly event-driven. Reads are highly optimized for dashboard visualizations.
7. **`workflow_automation` Schema**
   - **Owner:** `devflow-workflow-automation` module.
   - **Purpose:** Persists automation rules, triggers, actions, and execution logs.
   - **Isolation Rules:** Isolated execution engine that reads rule configurations and outputs execution logs.
8. **`collaboration` Schema**
   - **Owner:** `devflow-collaboration` module.
   - **Purpose:** Persists comment threads, comments, and emoji reactions.
   - **Isolation Rules:** Decoupled commenting structure. Comments are mapped to external targets using target types and entity ID attributes rather than FK constraints.
9. **`notifications` Schema**
   - **Owner:** `devflow-notifications` module.
   - **Purpose:** Persists notification alert templates, user preferences, and delivery logs.
   - **Isolation Rules:** Consumer schema driven entirely by domain event subscribers.

---

## 4. Entity Persistence Strategy

Every Aggregate Root defined in the Domain Model maps to a primary table inside its owning module's database schema. This section outlines persistence boundaries, lifecycle ownership, and cascade profiles.

| Bounded Context | Aggregate Root | Owning Schema | Persistence Responsibility | Lifecycle Boundary / Cascades |
| :--- | :--- | :--- | :--- | :--- |
| **IAM** | `Organization` | `auth` | `devflow-auth` | Core workspace boundary. Deletion cascade is blocked if active Projects or Repositories reference its ID. |
| **IAM** | `User` | `auth` | `devflow-auth` | Deleting a User disables login credentials, with soft-deletion handling user profile details. |
| **PM** | `Project` | `pm` | `devflow-project-management` | Project container. Deleting a Project cascades to delete boards, columns, cycles, epics, and tasks. |
| **PM** | `Task` | `pm` | `devflow-project-management` | Standalone Aggregate. Deletion cascades to its own description edits history, but does not affect projects or boards. |
| **RI** | `Repository` | `repo` | `devflow-repository-intelligence` | Ingest container. Deleting a Repository cascades to purge its commits, pull requests, and code reviews. |
| **AI Engine** | `ChatSession` | `ai_engine` | `devflow-ai-engine` | Conversation boundary. Deleting a ChatSession cascades to delete its chat messages. |
| **KB** | `Document` | `kb` | `devflow-knowledge-base` | Article container. Deleting a Document cascades to purge page histories and vector embeddings. |
| **Analytics** | `MetricSnapshot` | `developer_analytics` | `devflow-developer-analytics` | Read-only statistical snapshots. Periodically pruned according to data retention policies. |
| **Automation** | `AutomationRule` | `workflow_automation` | `devflow-workflow-automation` | Rule container. Deleting a rule cascades to delete execution runs logs. |
| **Collaboration** | `CommentThread` | `collaboration` | `devflow-collaboration` | Comment container. Thread deletions cascade to purge child comments and reactions. |
| **Notifications** | `Notification` | `notifications` | `devflow-notifications` | Alert instance. Cleaned up after expiration or read status timeout. |
| **Notifications** | `NotificationPreference`| `notifications` | `devflow-notifications` | User configuration map. Deleted if the corresponding user profile is purged. |

---

## 5. Relationships

Database relationships are strictly governed by module boundaries.

### 5.1 Intra-Module Relationships
- **Allowed:** Standard relational foreign key constraints (FKs) are permitted and encouraged within a single schema.
- **Cascades:** Cascading deletions are allowed inside schema boundaries to ensure relational integrity (e.g., deleting a Project column cascades to delete the tasks within that column).

### 5.2 Inter-Module Relationships (Cross-Module)
- **Forbidden:** Foreign keys (FKs) across different schemas are strictly prohibited.
- **Reference-by-ID Pattern:** Tables reference entities in other modules by storing their immutable identifier (UUID) as a raw data attribute (e.g., a `Task` table in `pm` stores `assignee_id` and `repository_id` as UUID columns, but no database-level FK constraint links them to `auth.user` or `repo.repository`).
- **Architectural Rationale:** Eliminating cross-module FKs ensures that:
  1. Schemas are decoupled and can be moved to separate database instances without breaking integrity checks.
  2. Database migrations inside a module can run independently without causing locks in other modules.
  3. Circular dependencies cannot be created at the database tier.
- **Eventual Consistency:** When an entity is deleted or updated in an upstream schema, downstream schemas synchronize their states asynchronously via domain event listeners, rather than relying on database-level cascades.

---

## 6. Multi-Tenancy Strategy

DevFlow implements a **logical multi-tenancy model using tenant isolation columns** within a shared PostgreSQL database instance.

- **Logical Organization Boundaries:** All tenant-scoped tables must include an `organization_id` (or `tenant_id`) column of type UUID.
- **Tenant Context Resolution:** The backend application resolves the tenant identifier from the JWT on every API request and binds it to a thread-local context.
- **Enforcement Mechanism:** 
  1. Database queries are dynamically filtered by appending a tenant clause (`WHERE organization_id = ?`).
  2. To prevent developer error or missed clauses, the persistence layer utilizes JPA filters (e.g., Hibernate `@FilterDef` and `@Filter` annotations) to automatically inject the tenant clause into all queries.
  3. Row-level security (RLS) policies are configured in PostgreSQL as an additional safety boundary, preventing any database session from executing reads or writes outside its active tenant scope.

---

## 7. Primary Key Strategy

DevFlow uses UUIDs as the primary key type for all tables in the system.

- **UUID Version Standard:** **UUIDv7** is the designated standard for transactional tables.
- **Architectural Rationale for UUIDv7:**
  - UUIDv7 encodes a Unix timestamp in its first 48 bits, producing **time-ordered (sequential)** values.
  - Unlike random UUIDv4, sequential UUIDv7 values cluster sequentially inside PostgreSQL B-Tree indexes, preventing random index page splits and ensuring high insert speeds.
  - They retain all benefits of UUIDs: decentralized generation, offline creation, and protection against sequential-ID enumeration security risks.
- **UUIDv4 Usage:** Permitted for highly transient entities where creation sequence is irrelevant (e.g., OAuth state tokens, websocket session logs).

---

## 8. Audit Strategy

A standardized audit trail is enforced for every database table.

Every table must include the following structural auditing columns:
- **`created_at`** (TIMESTAMPTZ): The creation date and time in UTC. Set once at row creation.
- **`updated_at`** (TIMESTAMPTZ): The last mutation date and time in UTC. Updated on every write.
- **`created_by`** (UUID): The identifier of the user who created the record.
- **`updated_by`** (UUID): The identifier of the user who last mutated the record.
- **`version`** (INT8): An incrementing integer used for **optimistic locking** concurrency control.

---

## 9. Soft Delete Strategy

To preserve historical context and analytics integrity, DevFlow employs a soft delete strategy for core user-facing entities.

### 9.1 Deletion Profiles

| Strategy | Applicable Entities | Description |
| :--- | :--- | :--- |
| **Soft Delete** | `Project`, `Task`, `Document`, `CommentThread` | Rows are flagged as deleted by updating a `deleted_at` timestamp. They are excluded from transactional queries but remain available for historical reporting. |
| **Hard Delete** | `OAuthConnection`, `AutomationExecution`, `Notification`, `ChatMessage` | Rows are physically purged using SQL `DELETE` commands. Relational dependencies are cleaned up immediately. |

### 9.2 Restoration Rules
- Restoring a soft-deleted entity is executed by resetting `deleted_at` to `NULL`.
- Restoration logic must recursively validate that parent boundaries are active (e.g., a Task cannot be restored if its parent Project is soft-deleted).

### 9.3 Indexing Implications
- To prevent soft-deleted records from blocking unique constraints, unique indexes must be partial indexes (e.g., `CREATE UNIQUE INDEX ... WHERE deleted_at IS NULL`).

---

## 10. Indexing Strategy

Indexing is critical to maintaining low API latencies. The following rules govern index creation:

1. **Foreign Key Indexes:** Every cross-module ID reference column (e.g., `assignee_id` inside the `pm` schema) must have a single-column B-Tree index to optimize query lookups.
2. **Tenant Indexes:** Composite B-Tree indexes must be utilized for common query filters. These indexes should list the `organization_id` (or `tenant_id`) first (e.g., index on `(organization_id, status)`).
3. **JSONB Indexing:** High-flexibility JSONB structures (such as automation triggers and notification payloads) must use **GIN (Generalized Inverted Index)** indexes using the `jsonb_path_ops` operator class to speed up key-value search evaluations.
4. **Vector Embeddings Indexing:** The `pgvector` embedding table inside the `kb` schema must use an **HNSW (Hierarchical Navigable Small World)** index utilizing cosine operator metrics (`vector_cosine_ops`). HNSW indexes are preferred over IVFFlat because they offer higher query recall rates and faster search times, though they require more memory.
5. **Partial Indexes:** Indexes on soft-deleted entities must use partial clauses (`WHERE deleted_at IS NULL`) to reduce index sizes.

---

## 11. Transaction Strategy

Database transactions are bounded strictly within modules to prevent locking bottlenecks and distributed rollbacks.

- **Single Module Boundary:** A database transaction (Spring `@Transactional`) must never span operations in multiple schemas.
- **Aggregate Root Consistency:** Transactions are scoped to update a single aggregate root. Modifications to other aggregates—even within the same module—should occur in separate transactions.
- **Transactional Outbox / Eventual Consistency:** Cross-module updates are coordinated using domain events. To prevent partial failure (e.g., database updates committing but event dispatches failing), modules publish events within the same transaction to an internal outbox table. A background worker reads from the outbox and publishes the events, ensuring **at-least-once delivery** and eventual consistency.

---

## 12. Concurrency Strategy

To handle high write volumes from concurrent users, DevFlow implements optimistic concurrency controls.

- **Optimistic Locking:** Every mutable table contains a `version` column. Hibernate verifies this version column on updates, throwing an `OptimisticLockingFailureException` if a conflict occurs.
- **Retry Philosophy:** Read-write conflicts or locking failures in background jobs (e.g., repo sync runs) are managed using Spring Retry or Resilience4j. Transactions are retried up to 3 times with a brief backoff period before throwing an exception.
- **Pessimistic Locking:** Prohibited for user-facing API paths. Permitted only in background worker queues where duplicate operations would cause invalid state transitions (e.g., locking a Git sync worker queue).

---

## 13. Search Strategy

DevFlow segregates data lookup workloads between PostgreSQL, Elasticsearch, and Vector indexes to optimize performance.

```
                  ┌──────────────────────┐
                  │   Data Query Input   │
                  └──────────┬───────────┘
                             │
            ┌────────────────┼────────────────┐
            ▼                ▼                ▼
   [ Relational Queries ] [ Semantic Search ] [ Text Search ]
            │                │                │
            ▼                ▼                ▼
     PostgreSQL DB       pgvector Index     Elasticsearch
     (ACID, Tenant,      (Cosine Embed,     (Aggregations,
      Primary keys)       RAG Context)       Wiki, Boards)
```

### 13.1 Search Technology Roles

| Datastore | Primary Workloads | Search Index Type |
| :--- | :--- | :--- |
| **PostgreSQL** | Transactional queries, relation lookups, tenant validation filters, and simple key lookups. | B-Tree / GIN |
| **pgvector** | Semantic searches, similar document lookups, and AI RAG prompt injections. | HNSW |
| **Elasticsearch** | Global search, documentation indexing, fuzzy task matching, and analytics aggregations. | Inverted Index / Block-based |

- **Synchronization Rule:** PostgreSQL remains the system of record. Changes to entities are published as events, and search indexers update Elasticsearch asynchronously, ensuring transactional writes are never blocked by search engine availability.

---

## 14. Backup & Recovery Principles

To ensure disaster recovery readiness, database deployments must follow these principles:

- **Continuous WAL Archiving:** Write-Ahead Logs (WAL) are streamed continuously to S3-compatible cloud storage (e.g., Cloudflare R2, AWS S3).
- **Point-in-Time Recovery (PITR):** WAL logs are retained for 30 days, enabling recovery to any specific second in the past.
- **Daily Physical Backups:** Full database snapshots are taken daily during low-traffic windows.
- **Automated Restores Verification:** Automated scripts periodically restore daily backups to a isolated staging environment to verify backup validity.

---

## 15. Migration Strategy

Database migrations are managed using **Flyway** or **Liquibase**.

- **Module-Specific Migration Scrips:** Each module maintains its own database migration directory (e.g., `db/migration/pm` inside the project-management Maven module).
- **Naming Conventions:** Scripts use strict naming schemes containing timestamps and description tags (e.g., `V20260728_1200__create_tasks_table.sql`).
- **Zero-Downtime Constraints:** Migrations in production must be backward-compatible (expand-and-contract phase pattern):
  1. Adding a new column must define it as nullable or provide a default value.
  2. Renaming a column requires adding a new column, copying the data, and dropping the old column in a subsequent release.
- **Rollback Strategy:** Automatic rollbacks are dangerous. If a migration fails, the deployment is aborted, and a compensating migration script is written to correct the state.

---

## 16. Database Architectural Rules

Every developer must follow these mandatory database design rules:

1. **No Shared Tables:** A table belongs to exactly one schema and is owned by exactly one module.
2. **No Direct Schema Access:** Modules must never execute queries targeting tables in other schemas (e.g., a service in `pm` must never query `auth.users` directly).
3. **No Cross-Schema Foreign Keys:** Foreign key constraints are forbidden across logical schemas.
4. **Reference-by-ID only:** Cross-module references must be stored as raw UUID values.
5. **No Cross-Schema Joins:** Database joins (`JOIN`) across schemas are strictly prohibited.
6. **Required Audit Fields:** Every table must include `created_at`, `updated_at`, `created_by`, `updated_by`, and `version` columns.
7. **Required Tenant ID:** Every tenant-scoped table must include `organization_id` as a UUID column.
8. **Asynchronous Events for Downstream Synchronization:** Downstream table syncs must occur via domain events.

---

## 17. Future Evolution

The modular database architecture of DevFlow simplifies scaling as the application grows.

- **Database Splitting (Microservices Extraction):**
  - Because schemas are isolated and have no cross-schema FKs, any schema (e.g., `repo`) can be moved to a dedicated PostgreSQL database instance with no impact on other schemas.
- **Read Replicas:**
  - Read-heavy modules (e.g., `developer_analytics`) can route their queries to PostgreSQL read-replicas, keeping read operations isolated from the primary database writes.
- **Table Partitioning:**
  - High-volume logging and audit tables (e.g., `Commit`, `MetricSnapshot`) are designed to be partitioned by time ranges (e.g., monthly partitions) to keep index sizes small and queries fast.
