# DevFlow — Repository Structure Specification

> **Version:** 1.0.0
> **Status:** Approved / Architecture Review Board (ARB) Signed Off
> **Author:** Principal Software Architect
> **Date:** 2026-07-30
> **Classification:** Internal — Engineering

---

## Table of Contents

1. [Purpose](#1-purpose)
2. [Repository Philosophy](#2-repository-philosophy)
3. [Repository Organization Principles](#3-repository-organization-principles)
4. [Repository Layout](#4-repository-layout)
5. [Backend Structure](#5-backend-structure)
6. [Module Organization](#6-module-organization)
7. [Documentation Organization](#7-documentation-organization)
8. [Infrastructure Organization](#8-infrastructure-organization)
9. [Testing Organization](#9-testing-organization)
10. [Build Organization](#10-build-organization)
11. [Shared Libraries](#11-shared-libraries)
12. [Naming Conventions](#12-naming-conventions)
13. [Dependency Rules](#13-dependency-rules)
14. [Repository Governance](#14-repository-governance)
15. [Future Evolution](#15-future-evolution)
16. [Architectural Principles & Key Design Decisions](#16-architectural-principles--key-design-decisions)

---

## 1. Purpose

This document defines the **official repository architecture** for DevFlow — an AI-First Engineering Intelligence and Delivery Platform. It establishes how the entire codebase is physically organized on disk, why each structural decision was made, and how the repository is expected to evolve as the platform matures.

Repository architecture is a first-order architectural concern. When the physical layout of a codebase is inconsistent with the conceptual architecture, two systems exist simultaneously: the one engineers intend to build and the one that actually grows from accumulated tactical decisions. This divergence compounds over time into an unnavigable codebase where modules overlap, ownership is ambiguous, and the cost of every new feature includes hours of locating and understanding context.

DevFlow's repository structure is engineered to eliminate that divergence from the outset. Every directory, every package, and every file placement is a deliberate architectural statement that reflects the platform's Modular Monolith design, its Domain-Driven Design principles, its Clean Architecture layering, and its multi-tenant operational model.

This specification serves the following audiences and purposes:

| Audience | Purpose |
| :--- | :--- |
| **Principal Engineers** | Enforce structural compliance during code review and architectural assessments. |
| **Software Architects** | Validate that structural decisions remain consistent with conceptual architecture decisions. |
| **Platform Engineers** | Understand the boundary between application code and infrastructure code, and locate CI/CD, Docker, and Terraform resources predictably. |
| **Senior Backend Engineers** | Navigate the codebase without tribal knowledge. Understand where new domain logic belongs before writing a single line. |
| **Architecture Review Board** | Audit repository evolution against approved architectural decisions and enforce governance policies. |

This document is explicitly not a contribution guide, not a Git branching strategy, not a coding standards document, and not a deployment runbook. Each of those concerns is addressed by dedicated specifications cross-referenced at the end of this document.

---

## 2. Repository Philosophy

### 2.1 Why Repository Architecture Matters

A repository is not a storage location for code. It is a communication medium. Every engineer who opens the repository reads its structure as a statement of intent: what this system does, how it is divided, what is shared and what is isolated, and where future changes belong. When that statement is coherent and consistent, new engineers become productive faster, cross-team collaboration is frictionless, and every architectural decision made in design sessions is faithfully reflected in the code that ships.

Conversely, a repository without intentional structure accumulates entropy. Modules that were meant to be isolated grow dependencies on each other. Shared utilities expand to include domain logic that belongs elsewhere. Infrastructure code becomes entangled with application code. Documentation lives wherever it was most convenient at the time. Within two years, a codebase that began with clear intentions requires significant archaeological effort to understand, and every change requires a senior engineer's tribal knowledge to execute safely.

DevFlow operates in a domain — engineering intelligence and AI-augmented developer tooling — where the codebase itself is a product asset. The code that developers write to build DevFlow reflects the same engineering discipline that DevFlow is designed to help other teams achieve. Repository integrity is therefore both a technical and organizational commitment.

### 2.2 Scalability

Repository scalability means that adding a new domain module, a new frontend application, a new infrastructure environment, or a new documentation category does not require restructuring existing directories or creating ambiguity about placement. The repository layout must absorb growth without degrading coherence.

DevFlow achieves this through strict single-responsibility boundaries at every level of the directory hierarchy. When a new bounded context is introduced — such as a Billing domain or a Marketplace domain — it maps to a new Maven module under `backend/`, a new schema under the corresponding database migrations directory, a new documentation subdirectory under `docs/`, and potentially new Terraform workspaces under `infrastructure/`. The container directories for each of these concerns already exist and their purpose is clearly defined. No new top-level directory is required for a new domain, and existing directories are not repurposed.

### 2.3 Maintainability

Repository maintainability is the property that allows engineers to locate any piece of the system without asking for directions. If an engineer needs to understand how JWT tokens are validated, the answer should be derivable from the module structure alone: the `devflow-auth` module, within the `infrastructure.security` package. If an engineer needs to modify the Docker Compose configuration for local development, the answer should be equally obvious: `infrastructure/docker/`. If a new Architecture Decision Record is required, the location is `docs/adr/`.

Predictable structure reduces cognitive load. Engineers who do not regularly work in a specific module can contribute to it with confidence when placement rules are consistent and documented. Code review becomes faster when reviewers can immediately identify whether a proposed change is in the correct location.

### 2.4 Modular Development

DevFlow's Modular Monolith architecture requires that each bounded context owns its code completely. The repository structure enforces this by mapping each domain module to a physically isolated Maven submodule. This isolation ensures that module boundaries are not merely conceptual: they are enforced by the build system. A class defined as package-private in `devflow-project-management` cannot be referenced by `devflow-developer-analytics` regardless of how the code is written, because the Maven module boundary prevents compilation.

This physical isolation mirrors the logical isolation defined in the Module Boundaries Specification and the Domain Model Specification. Repository structure and domain architecture are in direct correspondence.

### 2.5 Long-Term Evolution

Repository structure must support the platform's evolution from a Modular Monolith toward independently deployable services, standalone SDKs, a command-line interface, and a plugin ecosystem — without requiring a repository restructure that invalidates existing paths, breaks CI pipeline configurations, or forces mass refactoring across dependent tooling.

This is achieved by establishing top-level directories that are semantically stable even as their contents evolve. The `backend/` directory will always refer to the JVM application regardless of whether it contains one module or fifteen. The `infrastructure/` directory will always house deployment and provisioning code regardless of whether the platform deploys to a single Docker host or a multi-region Kubernetes cluster. These stable container directories allow tooling, documentation, and developer muscle memory to remain valid across years of platform growth.

---

## 3. Repository Organization Principles

The following five principles govern every structural decision in this repository. They are not preferences — they are constraints enforced through code review, governance processes, and automated architecture tests.

### 3.1 Single Responsibility

Every directory in this repository has exactly one purpose. A directory that serves multiple purposes is a symptom of structural debt. The `docs/` directory holds documentation only. The `backend/` directory holds JVM application source code only. The `infrastructure/` directory holds provisioning and deployment definitions only. When a concern does not fit cleanly into an existing directory's defined purpose, the correct response is to create a new, purpose-specific directory with a clearly defined scope — not to expand an existing directory's purpose to accommodate the new concern.

### 3.2 High Cohesion

Files that change together for the same reasons belong in the same directory. The source code for the `devflow-ai-engine` module — its domain entities, application services, infrastructure adapters, REST controllers, and database migrations — belongs together in the `devflow-ai-engine` Maven module. It does not belong partially in a shared `entities/` directory and partially in a shared `controllers/` directory. When a module evolves, all of its constituent files evolve together. This cohesion makes module boundaries visually obvious and ensures that the cost of a change is localized.

### 3.3 Low Coupling

Directories and modules that serve different purposes must not have implicit dependencies on each other's internal structure. The `backend/` directory knows nothing about the layout of `infrastructure/`. The `frontend/` directory knows nothing about the package structure inside `backend/`. Each top-level directory is a self-contained concern. Cross-cutting relationships are expressed through documented contracts — API specifications, event schemas, Docker image names — not through shared directory structures or file path dependencies.

### 3.4 Clear Ownership

Every directory in this repository must have a clear, identifiable owner. Ownership is documented in the `CODEOWNERS` file and reflected in the module ownership table in this specification. Directories without owners are vectors for unreviewed changes and accumulating technical debt. Ownership does not mean exclusion — all engineers may read and propose changes to any part of the repository — but it establishes who is accountable for reviewing changes and maintaining the structural integrity of each area.

### 3.5 Predictable Structure

Engineers working in this repository should never need to guess where something belongs. The structure must be derivable from the principles and conventions documented here. If an engineer cannot predict the correct placement for a new piece of code, documentation, or configuration from first principles, this specification must be updated to eliminate the ambiguity. Predictability is not achieved through exhaustive enumeration of every possible file — it is achieved through consistent application of clear rules.

---

## 4. Repository Layout

### 4.1 Top-Level Directory Tree

The following tree represents the complete top-level repository structure for DevFlow. Every directory shown here is intentional and its purpose is defined in the sections that follow.

```
devflow/                             # Repository root
|
+-- backend/                         # JVM application: all business domain code
|   +-- devflow-shared-kernel/       # Cross-cutting base abstractions, value objects, domain events
|   +-- devflow-auth/                # Identity, authentication, authorization, RBAC
|   +-- devflow-project-management/  # Tasks, boards, cycles, epics, project delivery
|   +-- devflow-repository-intelligence/ # Git sync, commit parsing, pull request tracking
|   +-- devflow-ai-engine/           # LLM orchestration, RAG, vector embeddings, chat
|   +-- devflow-knowledge-base/      # Wiki documentation, revisions, folder hierarchy
|   +-- devflow-developer-analytics/ # DORA metrics, velocity analysis, contributor activity
|   +-- devflow-workflow-automation/ # Event-triggered automation rules and executions
|   +-- devflow-collaboration/       # Comment threads, reactions, mentions
|   +-- devflow-notifications/       # Multi-channel notification routing and delivery
|   \-- pom.xml                      # Root Maven POM: dependency management, build plugins
|
+-- frontend/                        # Web client applications
|   +-- app/                         # Primary user-facing React/Next.js application
|   \-- storybook/                   # Component library and design system catalog
|
+-- infrastructure/                  # All deployment and provisioning code
|   +-- docker/                      # Dockerfiles, Docker Compose definitions
|   +-- terraform/                   # Cloud resource provisioning (IaC)
|   +-- kubernetes/                  # Kubernetes manifests and Helm charts
|   +-- monitoring/                  # Observability stack: Prometheus, Grafana, Loki, Tempo
|   +-- ci/                          # CI/CD pipeline definitions
|   +-- secrets/                     # Secret template files (never actual secret values)
|   \-- environments/                # Per-environment variable template files
|
+-- docs/                            # All architecture and operational documentation
|   +-- architecture/                # System design, domain model, module boundaries
|   +-- api/                         # REST API standards, versioning, error conventions
|   +-- database/                    # Schema design, migration strategy, data modeling
|   +-- security/                    # Authentication, authorization, threat model
|   +-- observability/               # Logging, metrics, tracing strategies
|   +-- configuration/               # Configuration strategy and environment management
|   +-- developer-guide/             # Local setup, contribution workflow, tooling
|   +-- decisions/                   # High-level design decision records
|   +-- adr/                         # Architecture Decision Records (ADRs)
|   +-- deployment/                  # Deployment procedures and rollback guides
|   \-- operations/                  # On-call runbooks, incident procedures
|
+-- scripts/                         # Utility and operational scripts
|   +-- local/                       # Developer environment setup scripts
|   +-- db/                          # Database seeding, migration validation scripts
|   +-- release/                     # Release tagging and artifact promotion scripts
|   \-- ci/                          # Scripts invoked exclusively from CI pipelines
|
+-- tools/                           # Internal developer tooling
|   +-- code-generator/              # Scaffolding templates for new modules
|   +-- lint/                        # Custom static analysis and architecture linting rules
|   \-- migration/                   # Data migration and schema evolution tooling
|
+-- examples/                        # Reference implementations and integration samples
|   +-- api-clients/                 # Sample clients for DevFlow REST APIs
|   +-- webhooks/                    # Sample webhook receiver implementations
|   \-- automation-rules/            # Example workflow automation rule definitions
|
+-- assets/                          # Static repository-level assets
|   +-- diagrams/                    # Source files for architecture diagrams
|   +-- branding/                    # Logo, color palette, typography definitions
|   \-- screenshots/                 # Product screenshots for documentation embedding
|
+-- .github/                         # GitHub platform configuration
|   +-- workflows/                   # GitHub Actions CI/CD workflow definitions
|   +-- CODEOWNERS                   # Directory ownership assignments
|   +-- PULL_REQUEST_TEMPLATE.md     # Standard pull request description template
|   \-- ISSUE_TEMPLATE/              # Bug report and feature request templates
|
+-- .editorconfig                    # IDE-agnostic code formatting baseline
+-- .gitignore                       # Tracked file exclusions
+-- .gitattributes                   # Line ending and binary file handling rules
+-- CHANGELOG.md                     # Versioned release change log
+-- LICENSE                          # Software license
\-- README.md                        # Repository entry point and orientation
```

### 4.2 Top-Level Directory Responsibilities

| Directory | Responsibility | Owner |
| :--- | :--- | :--- |
| `backend/` | All JVM application source code organized as Maven submodules. Contains domain logic, application services, infrastructure adapters, and REST controllers for all bounded contexts. | Backend Platform Team |
| `frontend/` | Web client applications. Isolated from backend source code. Communicates with backend exclusively through published REST APIs. | Frontend Platform Team |
| `infrastructure/` | All provisioning and deployment code: Docker, Terraform, Kubernetes, CI/CD pipelines, monitoring configurations. No business logic resides here. | Platform Engineering Team |
| `docs/` | All architecture, API, database, security, observability, and operational documentation. Version-controlled alongside code. | Architecture Review Board |
| `scripts/` | Utility scripts invoked by developers or CI pipelines for environment setup, database operations, and release management. Must be idempotent. | Platform Engineering Team |
| `tools/` | Internal developer tooling that improves productivity. Distinct from `scripts/` in that tools produce artifacts or enforce rules rather than performing operations. | Developer Experience Team |
| `examples/` | Reference implementations demonstrating how to integrate with DevFlow's APIs, webhooks, and automation engine. Intended for external developers. | Developer Relations Team |
| `assets/` | Static files that support documentation and branding. Source files for diagrams, logos, and screenshots. | Architecture Review Board |
| `.github/` | GitHub platform configuration. Workflow definitions, ownership rules, and template files that govern the collaborative development process. | Platform Engineering Team |

### 4.3 Structural Constraints

The following constraints apply unconditionally to the top-level structure:

1. No business logic, database connections, or API endpoints may reside in `infrastructure/`, `scripts/`, `tools/`, or `examples/`.
2. No provisioning or deployment code may reside in `backend/` or `frontend/`.
3. No actual secret values (passwords, API keys, tokens) may reside anywhere in the repository. Secret templates are permitted in `infrastructure/secrets/`.
4. No build artifacts, compiled binaries, or generated files may be committed to the repository. All generated output is excluded via `.gitignore`.
5. The `docs/` directory must remain the single authoritative location for all documentation. Documentation embedded within source code comments is supplementary, not authoritative.

---

## 5. Backend Structure

### 5.1 Multi-Module Maven Organization

The `backend/` directory is organized as a Maven Multi-Module project. The root `pom.xml` declares all submodules, manages dependency versions through a centralized Bill of Materials (BOM), and defines shared build plugin configurations that apply across all modules. Individual module `pom.xml` files declare only their specific dependencies and do not override shared build configuration without architectural justification.

Each submodule under `backend/` corresponds to exactly one Bounded Context from the Domain Model Specification. This one-to-one correspondence is not coincidental — it is the primary mechanism by which domain boundaries are enforced at the build system level.

### 5.2 Internal Package Structure

Every Maven module follows the same internal package hierarchy rooted at `src/main/java/com/devflow/modules/<module-name>/`. This consistency eliminates navigation ambiguity: any engineer who knows the package structure of one module can navigate any other module without consulting documentation.

```
devflow-<module>/
\-- src/
    +-- main/
    |   +-- java/
    |   |   \-- com/devflow/modules/<module>/
    |   |       +-- api/              # Public module API (interfaces, DTOs, events)
    |   |       +-- domain/           # Domain entities, aggregates, value objects, rules
    |   |       +-- application/      # Application services, use case coordinators
    |   |       +-- infrastructure/   # Persistence, messaging, external integrations
    |   |       +-- interfaces/       # REST controllers, WebSocket handlers, CLI adapters
    |   |       \-- config/           # Spring @Configuration classes for this module
    |   \-- resources/
    |       +-- db/migration/         # Flyway migration scripts for this module's schema
    |       \-- application-<module>.yml  # Module-specific Spring configuration fragment
    \-- test/
        +-- java/
        |   \-- com/devflow/modules/<module>/
        |       +-- unit/             # Unit tests for domain and application logic
        |       +-- integration/      # Integration tests requiring real infrastructure
        |       \-- architecture/     # ArchUnit and Spring Modulith boundary tests
        \-- resources/
            \-- fixtures/             # Test data and configuration fixtures
```

### 5.3 Package Layer Responsibilities

#### 5.3.1 `api/` — Public Module Contract

The `api/` package is the only package within a module whose classes carry `public` visibility. It contains the Java interfaces through which other modules may invoke this module's capabilities, the Data Transfer Objects (DTOs) that cross module boundaries as method arguments and return types, and the domain event classes published on the internal event bus.

This package constitutes the module's published contract. Any change to a class in the `api/` package is a breaking change with the same implications as a REST API version change. Such changes require explicit review and coordination with consuming modules.

#### 5.3.2 `domain/` — Business Logic Core

The `domain/` package contains the heart of each bounded context: Aggregate Roots, Entities, Value Objects, Domain Services, and business invariant enforcement logic. This layer is completely isolated from Spring, JPA, Flyway, HTTP, and every other infrastructure concern. It depends on nothing outside the `devflow-shared-kernel` module and the Java standard library.

Domain objects are the source of truth for business rules. A `Task` entity knows the rules governing its state transitions. An `Organization` aggregate enforces membership invariants. This logic is expressed in pure Java, not in database constraints or request validation logic, because it must be testable in isolation without any infrastructure.

#### 5.3.3 `application/` — Use Case Coordination

The `application/` package contains Application Services that coordinate domain operations. An Application Service receives a command or query from the interfaces layer, validates security preconditions using module APIs from the `devflow-auth` module, loads aggregate roots from repositories (through interfaces defined in the domain layer), invokes domain methods, and persists the results. Application Services are transaction boundaries: they are annotated with `@Transactional` and are responsible for ensuring that all domain state changes within a use case either succeed atomically or fail without partial commitment.

Application Services do not contain business logic. They orchestrate. Business rules belong in the domain layer. Infrastructure concerns belong in the infrastructure layer. This strict separation ensures that the application layer remains thin, readable, and trivially testable.

#### 5.3.4 `infrastructure/` — Technical Implementations

The `infrastructure/` package contains all technology-specific implementations: JPA entity mappings, Spring Data repositories, external HTTP clients, message producers, cache adapters, file storage clients, and search engine clients. This layer implements the interfaces defined in the domain layer, providing technical realizations of domain abstractions without the domain layer having any knowledge of the specific technology used.

The infrastructure layer is where Spring annotations (`@Repository`, `@Service`, `@Component`), JPA annotations (`@Entity`, `@Table`, `@Column`), and external SDK configurations appear. This concentration ensures that migrating from one technology to another — replacing an HTTP client, switching an object store, upgrading a database driver — requires changes only in this layer.

#### 5.3.5 `interfaces/` — External Boundary Adapters

The `interfaces/` package contains all entry points through which external systems communicate with this module: REST controllers, WebSocket message handlers, GraphQL resolvers, and gRPC service implementations. These classes translate external protocols into application-layer commands and queries, and translate application-layer responses into protocol-appropriate outputs.

Controllers are deliberately thin. They perform HTTP-level input parsing, delegate to Application Services for all business logic, and serialize the result for HTTP output. Exception handling is centralized in a global exception advisor, not distributed across controllers.

#### 5.3.6 `config/` — Module Spring Configuration

The `config/` package contains Spring `@Configuration` classes that define beans specific to this module. Bean definitions for infrastructure clients, security configurations scoped to this module, async executor configurations, and module-specific properties binding classes reside here. The `config/` package deliberately excludes global application configuration, which is managed by a separate application bootstrap module.

---

## 6. Module Organization

### 6.1 Module Inventory

The following table documents every module in the DevFlow backend, its primary responsibility, its owned Aggregate Roots, and its position in the dependency hierarchy.

| Module | Primary Responsibility | Owned Aggregates | Dependency Tier |
| :--- | :--- | :--- | :--- |
| `devflow-shared-kernel` | Universal base abstractions, value objects, domain events, cross-cutting exceptions | None (structural only) | Foundation |
| `devflow-auth` | User identity, organization tenancy, JWT issuance, RBAC enforcement | `User`, `Organization` | Core |
| `devflow-project-management` | Task lifecycle, board management, cycle tracking, epic planning | `Project`, `Task` | Core |
| `devflow-repository-intelligence` | Git repository synchronization, commit indexing, pull request tracking | `Repository` | Core |
| `devflow-knowledge-base` | Documentation wiki, revision history, folder hierarchy, document search | `Document` | Business |
| `devflow-ai-engine` | LLM orchestration, RAG pipeline, vector indexing, contextual chat | `ChatSession` | Business |
| `devflow-developer-analytics` | DORA metrics, velocity analysis, contributor activity aggregation | `MetricSnapshot` | Analytics |
| `devflow-workflow-automation` | Event-triggered automation rules, condition evaluation, automated mutations | `AutomationRule` | Automation |
| `devflow-collaboration` | Comment threads, reactions, mention parsing across all domain entities | `CommentThread` | Cross-cutting |
| `devflow-notifications` | Multi-channel notification routing, template rendering, preference enforcement | `Notification`, `NotificationPreference` | Cross-cutting |

### 6.2 Module Tier Diagram

```
  +--------------------------------------------------------------------+
  |                    FOUNDATION LAYER                                |
  |              devflow-shared-kernel                                 |
  |   (BaseEntity, AggregateRoot, DomainEvent, TenantId, UserId)      |
  +--------------------------------------------------------------------+
                              |
                              v
  +--------------------------------------------------------------------+
  |                      CORE SECURITY LAYER                          |
  |                       devflow-auth                                |
  |      (User, Organization, JWT, RBAC, OAuth, TenantResolution)     |
  +--------------------------------------------------------------------+
                              |
          +-------------------+-------------------+
          |                                       |
          v                                       v
  +--------------------+               +---------------------+
  |  devflow-project-  |               | devflow-repository- |
  |  management        |               | intelligence        |
  | (Project, Task,    |               | (Repository,        |
  |  Board, Cycle)     |               |  Commit, PR)        |
  +--------------------+               +---------------------+
          |                                       |
          +-------------------+-------------------+
                              |
              +---------------+---------------+
              |               |               |
              v               v               v
  +-----------+----+ +--------+------+ +------+---------+
  |devflow-knowledge| |devflow-ai-   | |devflow-developer|
  |base             | |engine        | |analytics        |
  |(Document,Folder)| |(ChatSession) | |(MetricSnapshot) |
  +-----------------+ +--------------+ +-----------------+
                              |
          +-------------------+-------------------+
          |                                       |
          v                                       v
  +--------------------+               +---------------------+
  | devflow-workflow-  |               | devflow-            |
  | automation         |               | collaboration       |
  | (AutomationRule)   |               | (CommentThread)     |
  +--------------------+               +---------------------+
                              |
                              v
  +--------------------------------------------------------------------+
  |                    CROSS-CUTTING LAYER                             |
  |                  devflow-notifications                             |
  |        (Notification, NotificationPreference)                     |
  +--------------------------------------------------------------------+
```

### 6.3 Module Detailed Responsibilities

#### 6.3.1 `devflow-shared-kernel`

The Shared Kernel is the lowest layer of the dependency hierarchy. Every other module depends on it. Precisely because of this universal dependency, it must remain minimal, stable, and free of any technology-specific code. It contains base entity abstractions (`BaseEntity`, `AggregateRoot`), universal value objects (`TenantId`, `UserId`, `EmailAddress`, `DateRange`), the `DomainEvent` marker interface, and common exception types (`BusinessException`, `EntityNotFoundException`, `UnauthorizedException`). Nothing more. Additions to this module require Architecture Review Board approval because every module is affected by every change.

#### 6.3.2 `devflow-auth`

The Auth module is the security foundation of the platform. It owns the `User` and `Organization` aggregates, which together define the identity and tenant boundaries within which all other domain data operates. It issues and validates JWT access tokens, resolves tenant context from incoming requests, enforces RBAC permission checks, and manages OAuth provider connections for third-party Git integrations. All other modules that need to verify user identity or permissions invoke this module's `AuthApi` interface — they do not implement their own security logic.

#### 6.3.3 `devflow-project-management`

The Project Management module owns the core delivery planning domain. Its `Project` aggregate manages boards, columns, cycles, and epics. Its `Task` aggregate is intentionally separated as an independent Aggregate Root to prevent write contention on large project boards. The module exposes `ProjectManagementApi` and `TaskQueryApi` for read operations by downstream modules, and publishes domain events (`TaskCreatedEvent`, `TaskStatusChangedEvent`, `CycleClosedEvent`) consumed by Analytics, Automation, and Notifications.

#### 6.3.4 `devflow-repository-intelligence`

The Repository Intelligence module manages the integration with external Git hosting providers. It handles OAuth installation binding, webhook signature verification, repository cloning, commit parsing, pull request synchronization, and code diff extraction. It exposes `RepositoryIntelligenceApi` and publishes `CommitParsedEvent` and `PullRequestMergedEvent` for downstream consumption. Its internal Git operations use JGit and are entirely isolated within the infrastructure layer.

#### 6.3.5 `devflow-ai-engine`

The AI Engine module is the platform's intelligence layer. It orchestrates communications with external LLM providers (OpenAI, Anthropic, Google Gemini, Ollama), constructs context-aware prompts using project and repository data retrieved through the APIs of upstream modules, manages vector embedding storage and retrieval, and maintains conversational chat sessions with developers. It depends on the APIs of Project Management, Repository Intelligence, and Knowledge Base to assemble rich context payloads. It exposes `AiOrchestrationApi` for invoking AI capabilities from other modules.

#### 6.3.6 `devflow-knowledge-base`

The Knowledge Base module manages the platform's documentation and internal wiki capabilities. It maintains hierarchical folder structures, rich document content with full revision history, and semantic document indexing for AI retrieval augmentation. Documents are associated with projects and tasks by reference ID only, preserving module independence. It exposes `KnowledgeBaseApi` for document queries and publishes `DocumentUpdatedEvent` for downstream indexing.

#### 6.3.7 `devflow-developer-analytics`

The Developer Analytics module is a pure downstream consumer that constructs historical performance views from events published by upstream modules. It subscribes to task lifecycle events, commit events, and pull request events to calculate DORA metrics, sprint velocity, cycle time, and contributor activity. Its `MetricSnapshot` aggregate is a read-optimized projection with no write contention with upstream aggregates. It exposes `DeveloperAnalyticsApi` for dashboard and reporting queries.

#### 6.3.8 `devflow-workflow-automation`

The Workflow Automation module allows organizations to define conditional automation rules that react to system events. It subscribes to events from Project Management, Repository Intelligence, and Notifications, evaluates rule conditions using a Boolean expression engine, and executes configured actions (column transitions, user assignments, external webhook dispatches). It owns the `AutomationRule` aggregate and exposes `WorkflowAutomationApi` for rule management.

#### 6.3.9 `devflow-collaboration`

The Collaboration module provides a generic discussion substrate across all platform entities. Comment threads are associated with target entities by a `TargetType` and `TargetId` reference, meaning the module has no compile-time dependency on Project Management, Repository Intelligence, or Knowledge Base. This design prevents bidirectional coupling. The module exposes `CollaborationApi` for retrieving comment counts and thread summaries for display in upstream module views.

#### 6.3.10 `devflow-notifications`

The Notifications module handles the routing and delivery of alerts to users across multiple channels: In-App, Email, Slack, and Discord. It subscribes to events from all other modules, resolves recipient addresses and channel preferences through `AuthApi`, compiles channel-appropriate message templates, and dispatches notifications to external delivery providers. It owns no knowledge of business entities beyond event payloads and user preferences.

---

## 7. Documentation Organization

### 7.1 Documentation as Architecture

Documentation in DevFlow is not supplementary material — it is a first-class component of the architecture. The Configuration Strategy, the Logging Strategy, the Domain Model, and the Module Boundaries Specification are version-controlled alongside source code, reviewed through the same pull request process, and treated with the same level of rigor as production code.

This approach is justified by a fundamental observation: architecture decisions that exist only in an engineer's memory or in a shared document store are invisible to future team members, are not auditable, and are routinely violated by engineers who were not present when the decision was made. By committing documentation to the repository, every architectural decision is traceable through version history, every change is reviewed by the appropriate owners, and the documentation evolves in lockstep with the code it describes.

### 7.2 Documentation Directory Structure

```
docs/
|
+-- architecture/                    # System-level architecture specifications
|   +-- HIGH_LEVEL_ARCHITECTURE.md   # Overall system design, component diagram, technology decisions
|   +-- DOMAIN_MODEL.md              # DDD bounded contexts, aggregates, entities, value objects
|   +-- MODULE_BOUNDARIES.md         # Inter-module dependency rules, communication patterns
|   \-- REPOSITORY_STRUCTURE.md      # This document. Physical repository organization.
|
+-- api/                             # REST API design standards
|   +-- API_STANDARDS.md             # Global API design conventions and constraints
|   +-- REST_CONVENTIONS.md          # HTTP method usage, URL structure, response formats
|   +-- VERSIONING.md                # API versioning strategy and compatibility guarantees
|   +-- ERROR_CODES.md               # Standardized error code registry
|   \-- ERROR_RESPONSE_FORMAT.md     # Machine-readable error response schema
|
+-- database/                        # Data architecture documentation
|   +-- SCHEMA_DESIGN.md             # Database schema strategy, multi-tenant partitioning
|   +-- MIGRATION_STRATEGY.md        # Flyway migration conventions, rollback procedures
|   \-- QUERY_PATTERNS.md            # Approved query patterns, N+1 prevention, pagination
|
+-- security/                        # Security architecture documentation
|   +-- AUTHENTICATION.md            # JWT lifecycle, token validation, session management
|   +-- AUTHORIZATION.md             # RBAC model, permission definitions, enforcement points
|   +-- MULTI_TENANCY.md             # Tenant isolation model, data boundary enforcement
|   \-- THREAT_MODEL.md              # Identified threats, risk assessments, mitigations
|
+-- observability/                   # Observability architecture
|   +-- LOGGING_STRATEGY.md          # Structured logging, log levels, correlation identifiers
|   +-- METRICS_STRATEGY.md          # Micrometer metrics, dashboards, alerting thresholds
|   \-- TRACING_STRATEGY.md          # Distributed tracing, OpenTelemetry, trace propagation
|
+-- configuration/                   # Configuration management documentation
|   \-- CONFIGURATION_STRATEGY.md    # Externalized configuration, secret management, profiles
|
+-- developer-guide/                 # Engineering onboarding and workflow guides
|   +-- LOCAL_SETUP.md               # Local development environment setup procedures
|   +-- CONTRIBUTING.md              # Contribution workflow, branching, review standards
|   \-- TOOLING.md                   # Approved development tools and configurations
|
+-- decisions/                       # High-level technology and design decisions
|   \-- TECHNOLOGY_CHOICES.md        # Rationale for major technology selections
|
+-- adr/                             # Architecture Decision Records
|   +-- ADR-0001-modular-monolith.md # Decision to adopt Modular Monolith architecture
|   +-- ADR-0002-postgresql.md       # Decision to adopt PostgreSQL as primary database
|   +-- ADR-0003-maven-multimodule.md # Decision to use Maven Multi-Module structure
|   \-- ADR-NNNN-<title>.md          # Future ADRs, sequentially numbered
|
+-- deployment/                      # Deployment and release documentation
|   +-- DEPLOYMENT_PROCEDURE.md      # Production deployment steps and validation
|   +-- ROLLBACK_PROCEDURE.md        # Rollback strategy for failed deployments
|   \-- ENVIRONMENT_GUIDE.md         # Per-environment configuration and access procedures
|
\-- operations/                      # Operational runbooks and incident procedures
    +-- INCIDENT_RESPONSE.md         # Incident classification, escalation, communication
    +-- ON_CALL_GUIDE.md             # On-call responsibilities, alert response procedures
    \-- RUNBOOKS/                    # Specific operational procedures for known failure modes
```

### 7.3 Documentation Category Responsibilities

| Category | Audience | Update Triggers |
| :--- | :--- | :--- |
| `architecture/` | Architects, Principal Engineers | Structural changes, new domain decisions |
| `api/` | Backend Engineers, API Consumers | API changes, new conventions, versioning decisions |
| `database/` | Backend Engineers, DBAs | Schema changes, new query patterns |
| `security/` | Security Team, Architects | Authentication changes, new threat vectors |
| `observability/` | Platform Engineers, SRE | New metrics, logging changes, tracing updates |
| `configuration/` | All Engineers, Operations | New configuration keys, environment changes |
| `developer-guide/` | All Engineers | Tooling changes, workflow updates |
| `decisions/` | Architects, Engineering Leadership | Major technology adoptions or replacements |
| `adr/` | All Engineers | Any architectural decision requiring rationale capture |
| `deployment/` | Platform Engineers, Operations | Release process changes, infrastructure updates |
| `operations/` | SRE, On-Call Engineers | New failure modes, escalation path changes |

### 7.4 Why Documentation Is Separated from Source Code

Documentation subdirectories under `docs/` are separated from module source code for three reasons. First, documentation has different ownership and review requirements than source code. The Security team must review changes to `docs/security/`. The Architecture Review Board must approve changes to `docs/architecture/`. These ownership boundaries are enforced through `CODEOWNERS` rules applied to the `docs/` directory tree. Second, documentation changes that span multiple domains — such as updating the Domain Model to add a new bounded context — should not require changes across all affected module directories. Third, documentation aggregated in a single directory tree is more navigable for readers who are not yet familiar with the module structure.

### 7.5 Architecture Decision Record Format

Every ADR in `docs/adr/` must conform to the following structure to ensure consistency and readability across all architectural decisions:

| Section | Content |
| :--- | :--- |
| **Status** | `Proposed`, `Approved`, `Deprecated`, or `Superseded` |
| **Context** | The problem, technical constraints, and forces at play |
| **Options Considered** | All meaningful alternatives evaluated, with trade-offs |
| **Decision** | The chosen option, stated concisely |
| **Rationale** | Why this option was selected over the alternatives |
| **Consequences** | Expected outcomes, trade-offs accepted, and follow-up work |
| **References** | Links to related ADRs, specifications, or external resources |

---

## 8. Infrastructure Organization

### 8.1 Separation of Infrastructure from Application

Infrastructure code and application code serve fundamentally different purposes, change at different rates, require different expertise to review, and carry different risk profiles. A change to a Kubernetes Deployment manifest is a deployment-topology decision. A change to a Terraform resource is an infrastructure-provisioning decision. Neither of these is a business logic decision. Mixing them with application source code produces a codebase where the risk profile of every change is ambiguous.

DevFlow places all infrastructure code under `infrastructure/` and enforces its complete separation from `backend/`. Infrastructure engineers may modify `infrastructure/` without requiring review from backend domain engineers. Backend engineers may modify `backend/` without requiring review from infrastructure engineers. CODEOWNERS enforces this separation automatically.

### 8.2 Infrastructure Directory Structure

```
infrastructure/
|
+-- docker/
|   +-- Dockerfile                   # Production multi-stage application image definition
|   +-- Dockerfile.dev               # Development image with hot-reload and debugging
|   +-- docker-compose.yml           # Local development stack: app, DB, Redis, Loki, Grafana
|   +-- docker-compose.test.yml      # Integration test stack: isolated test databases
|   \-- .env.example                 # Environment variable template for local Docker stack
|
+-- terraform/
|   +-- modules/                     # Reusable Terraform modules
|   |   +-- networking/              # VPC, subnets, security groups
|   |   +-- compute/                 # EC2, ECS, or GKE node pool definitions
|   |   +-- database/                # RDS PostgreSQL, Redis ElastiCache provisioning
|   |   \-- storage/                 # S3 buckets, object storage policies
|   +-- environments/
|   |   +-- staging/                 # Staging environment Terraform workspace
|   |   \-- production/              # Production environment Terraform workspace
|   \-- backend.tf                   # Remote state backend configuration
|
+-- kubernetes/
|   +-- base/                        # Base Kustomize resources applied to all environments
|   |   +-- deployment.yaml          # Application Deployment specification
|   |   +-- service.yaml             # Kubernetes Service definitions
|   |   +-- configmap.yaml           # Non-secret configuration
|   |   \-- hpa.yaml                 # Horizontal Pod Autoscaler specification
|   +-- overlays/
|   |   +-- staging/                 # Staging-specific Kustomize patches
|   |   \-- production/              # Production-specific Kustomize patches
|   \-- helm/
|       \-- devflow/                 # DevFlow Helm chart for parameterized deployments
|
+-- monitoring/
|   +-- prometheus/
|   |   +-- prometheus.yml           # Prometheus scrape configuration
|   |   \-- rules/                   # Alerting rules for DevFlow-specific metrics
|   +-- grafana/
|   |   +-- dashboards/              # Pre-built Grafana dashboard JSON definitions
|   |   \-- datasources/             # Grafana datasource provisioning configurations
|   +-- loki/
|   |   \-- loki-config.yml          # Grafana Loki log aggregation configuration
|   \-- tempo/
|       \-- tempo-config.yml         # Grafana Tempo distributed tracing configuration
|
+-- ci/
|   +-- build.yml                    # Application build pipeline definition
|   +-- test.yml                     # Test execution pipeline definition
|   +-- security-scan.yml            # SAST, dependency scanning, container scanning
|   \-- deploy.yml                   # Deployment pipeline with environment promotion gates
|
+-- secrets/
|   +-- secret-template.env          # Template listing all required secret keys (no values)
|   \-- vault-policies/              # HashiCorp Vault access policy definitions
|
\-- environments/
    +-- local.env.template            # Local development environment variable template
    +-- staging.env.template          # Staging environment variable template
    \-- production.env.template       # Production environment variable template
```

### 8.3 Infrastructure Component Responsibilities

| Directory | Technology | Responsibility |
| :--- | :--- | :--- |
| `docker/` | Docker, Docker Compose | Container image definitions and local development orchestration. All local development uses Docker Compose. |
| `terraform/` | HashiCorp Terraform | Cloud resource provisioning. All cloud infrastructure is defined as code. No manual resource creation. |
| `kubernetes/` | Kubernetes, Kustomize, Helm | Container orchestration manifests for staging and production. Uses Kustomize for environment differentiation. |
| `monitoring/` | Prometheus, Grafana, Loki, Tempo | Observability stack configuration. Pre-built dashboards and alerting rules for DevFlow-specific metrics. |
| `ci/` | GitHub Actions | Pipeline definitions for build, test, security scanning, and deployment. Pipelines are code, not UI configurations. |
| `secrets/` | HashiCorp Vault | Secret policy definitions and key templates. Never contains actual secret values. |
| `environments/` | Shell, dotenv | Variable templates for each deployment environment. Engineers copy templates and supply values locally. |

### 8.4 Environment Promotion Model

```
  Local Development         Staging                    Production
  (docker-compose.yml)      (kubernetes/overlays/      (kubernetes/overlays/
                             staging/)                  production/)
         |                        |                          |
         v                        v                          v
  +-------------+         +-------------+            +-------------+
  | Developer   |  --PR-> | CI Pipeline | -Approval> | Production  |
  | Machine     |         | Build+Test  |            | Cluster     |
  +-------------+         +-------------+            +-------------+
         |                        |                          |
  Docker Compose          Terraform (staging)        Terraform (prod)
  environment vars        env template               env template
  from local.env          from staging.env           from secrets vault
```

No environment may be configured manually. Every environment configuration change must flow through the versioned template files in `infrastructure/environments/` and be applied via the CI pipeline.

---

## 9. Testing Organization

### 9.1 Tests Mirror Production Structure

The testing strategy in DevFlow is governed by a single organizing principle: tests mirror production package structure. A test class for `TaskApplicationService` in the `devflow-project-management` module resides at:

```
devflow-project-management/
\-- src/test/java/com/devflow/modules/pm/
    \-- unit/
        \-- application/
            \-- TaskApplicationServiceTest.java
```

This is not bureaucratic rigidity — it is a fundamental property of a maintainable test suite. When tests are placed adjacent to the production code they verify, engineers can locate tests immediately when debugging failures. When refactoring production code, engineers know exactly which test files must be updated. When reviewing a pull request, reviewers can verify that new production logic is accompanied by corresponding test cases in the expected location.

### 9.2 Test Category Organization

Each module's test directory contains the following categories, each isolated into its own sub-package to prevent category confusion and enable targeted test execution.

```
src/test/java/com/devflow/modules/<module>/
|
+-- unit/                            # Pure unit tests: no Spring context, no infrastructure
|   +-- domain/                      # Tests for aggregate roots, entities, value objects
|   \-- application/                 # Tests for application services (mocked dependencies)
|
+-- integration/                     # Integration tests: real infrastructure, isolated test DB
|   +-- persistence/                 # Repository and JPA mapping tests against test PostgreSQL
|   +-- api/                         # Controller tests using MockMvc or WebTestClient
|   \-- external/                    # Tests against WireMock stubs of external providers
|
+-- architecture/                    # Static analysis and boundary enforcement tests
|   +-- ModularityTest.java          # Spring Modulith boundary verification
|   \-- LayeringTest.java            # ArchUnit layering rule enforcement
|
\-- contract/                        # Consumer-driven contract tests (module API contracts)
    \-- <consumer-name>/             # One directory per consuming module
```

### 9.3 Test Resource Organization

```
src/test/resources/
+-- fixtures/
|   +-- entities/                    # JSON fixture files representing test domain entities
|   \-- events/                      # JSON payloads for domain event deserialization tests
+-- sql/                             # SQL scripts for pre-populating test database state
\-- application-test.yml             # Spring test profile configuration overrides
```

### 9.4 Testing Layer Responsibilities

| Test Category | Infrastructure Required | Execution Speed | Scope |
| :--- | :--- | :--- | :--- |
| **Unit** | None (all dependencies mocked) | Milliseconds per test | Single class, isolated logic |
| **Integration** | PostgreSQL, Redis (Testcontainers) | Seconds per test | Module-internal data flow |
| **API** | Spring MockMvc, WireMock | Seconds per test | HTTP contract verification |
| **Architecture** | Classpath only | Seconds (full scan) | Structural rule enforcement |
| **Contract** | None (generated stubs) | Milliseconds per test | Cross-module API compatibility |
| **Performance** | Dedicated load environment | Minutes to hours | Throughput, latency baselines |
| **Security** | Full application stack | Minutes | Authentication, authorization, injection |

### 9.5 Architecture Tests

Architecture tests are mandatory for every module and represent a unique category: they do not test business behavior. Instead, they verify that the code structure complies with the architectural rules defined in this specification and the Module Boundaries Specification.

Every module must include:

- A **Spring Modulith boundary test** that verifies no module references another module's non-public classes.
- An **ArchUnit layering test** that verifies the `domain/` package does not import from `infrastructure/`, that the `infrastructure/` package does not import from `interfaces/`, and that the `api/` package contains only public types.

These tests fail the build on violation and serve as the automated enforcement mechanism for structural rules.

### 9.6 Full Testing Topology

```
  +------------------------------------------------------------------+
  |                    MODULE TEST STRUCTURE                         |
  |                                                                  |
  |  unit/                 integration/          architecture/       |
  |  +-- domain/           +-- persistence/      +-- ModularityTest  |
  |  |   (no Spring)       |   (Testcontainers)  \-- LayeringTest    |
  |  \-- application/      +-- api/                                  |
  |      (mocked)          |   (MockMvc)         contract/           |
  |                        \-- external/         \-- <consumer>/     |
  |                            (WireMock)                            |
  +------------------------------------------------------------------+
                              |
  +------------------------------------------------------------------+
  |               EXECUTION ENVIRONMENT MATRIX                      |
  |                                                                  |
  |  Local Dev       CI Pipeline             Performance Env         |
  |  unit + arch     unit + arch + api +     performance/           |
  |                  integration + contract  security/               |
  +------------------------------------------------------------------+
```

---

## 10. Build Organization

### 10.1 Maven Multi-Module Build Structure

The DevFlow build is organized as a Maven Multi-Module project rooted at `backend/pom.xml`. This root POM performs three functions: it declares all submodules, it manages all dependency versions through a centralized `<dependencyManagement>` section, and it configures all shared build plugins. Individual module POMs inherit from the root POM and declare only module-specific dependencies without version specifications.

### 10.2 Build Artifact Organization

```
backend/
+-- pom.xml                          # Root POM: module declarations, dependency management
|
+-- devflow-shared-kernel/
|   +-- pom.xml                      # Minimal POM: inherits root, declares no extra deps
|   \-- target/
|       \-- devflow-shared-kernel-<version>.jar
|
+-- devflow-auth/
|   +-- pom.xml                      # Declares: shared-kernel, spring-security, jjwt
|   \-- target/
|       \-- devflow-auth-<version>.jar
|
+-- devflow-project-management/
|   +-- pom.xml
|   \-- target/
|       \-- devflow-project-management-<version>.jar
|
... (all domain modules follow the same pattern)
|
\-- devflow-application/             # Bootstrap module: Spring Boot entry point only
    +-- pom.xml                      # Declares all domain modules as runtime dependencies
    \-- target/
        \-- devflow-application-<version>.jar   # Executable fat JAR (single deployable)
```

### 10.3 Dependency Management

All dependency versions are declared once in the root `pom.xml` under `<dependencyManagement>`. Individual modules declare dependencies without version attributes, inheriting versions from the root. This ensures:

1. The entire build uses a consistent set of library versions with no version drift between modules.
2. Dependency upgrades are executed in one location and apply uniformly across the codebase.
3. Dependency conflicts are detected at the root level before they cause module-level compilation failures.

### 10.4 Build Lifecycle Conventions

| Phase | Action | Convention |
| :--- | :--- | :--- |
| `validate` | POM structure validation | Root POM defines all modules |
| `compile` | Source code compilation | Java 21 compiler target, `-parameters` flag required |
| `test` | Unit and architecture tests | All tests in `unit/` and `architecture/` packages execute |
| `package` | JAR assembly | Module JARs produced; no generated sources committed |
| `verify` | Integration tests | Testcontainers-backed tests execute in `integration/` packages |
| `install` | Local repository installation | Required for local multi-module dependency resolution |

### 10.5 Build Flow Diagram

```
  Root pom.xml
       |
       v
  +----------------------------------------------------+
  |  Phase 1: validate                                 |
  |  POM structure and module declarations verified    |
  +----------------------------------------------------+
       |
       v
  +----------------------------------------------------+
  |  Phase 2: compile                                  |
  |  Order: shared-kernel -> auth -> pm, repo          |
  |         -> kb, ai, analytics                       |
  |         -> automation, collab, notifications       |
  |         -> application (bootstrap)                 |
  +----------------------------------------------------+
       |
       v
  +----------------------------------------------------+
  |  Phase 3: test (unit + architecture)               |
  |  Each module runs independently in parallel        |
  +----------------------------------------------------+
       |
       v
  +----------------------------------------------------+
  |  Phase 4: package                                  |
  |  Module JARs -> application bootstrap fat JAR      |
  +----------------------------------------------------+
       |
       v
  +----------------------------------------------------+
  |  Phase 5: verify (integration tests)               |
  |  Testcontainers: PostgreSQL + Redis                |
  +----------------------------------------------------+
       |
       v
  +----------------------------------------------------+
  |  Artifact: devflow-application-<version>.jar       |
  |  Single executable JAR, immutably versioned        |
  +----------------------------------------------------+
```

### 10.6 Generated Sources

Database migration scripts (Flyway SQL), OpenAPI-generated client stubs, and any other generated sources must not be committed to version control. Generated sources are produced during the build lifecycle in `target/generated-sources/` and are excluded from Git via `.gitignore`. If generated stubs are required at compile time, they are generated by a dedicated Maven plugin phase before compilation and are never manually edited.

### 10.7 Build Determinism

Every build of DevFlow must be reproducible: given the same source tree and the same build tool versions, the build output must be byte-for-byte identical. This requires:

1. All dependency versions are pinned to exact versions in `<dependencyManagement>`, not version ranges.
2. The Maven Wrapper (`mvnw`) is committed to the repository to ensure build tool version consistency.
3. No build steps write environment-specific values into artifact manifests.
4. Timestamps in artifact metadata are normalized to the last commit time.

---

## 11. Shared Libraries

### 11.1 Purpose and Constraints

The `devflow-shared-kernel` module is the only shared library in the DevFlow backend. It serves as the common foundation upon which all domain modules are built. Its scope is intentionally narrow, and that narrowness is an architectural constraint enforced by the Architecture Review Board — not a stylistic preference.

The fundamental risk of a shared library in a Modular Monolith is gravitational collapse: the tendency of developers to place logic in the shared module because "it is used in multiple places." This reasoning, applied consistently, converts a cleanly bounded codebase into a monolithic shared library with thin domain modules that contain no real logic. The `devflow-shared-kernel` is protected against this failure mode by explicit inclusion and exclusion rules.

### 11.2 Approved Shared Kernel Contents

| Category | Examples | Rationale |
| :--- | :--- | :--- |
| **Base domain abstractions** | `BaseEntity`, `AggregateRoot`, `DomainEvent` | Structural contracts that every module must implement uniformly |
| **Universal value objects** | `TenantId`, `UserId`, `EmailAddress`, `DateRange` | Domain-agnostic types referenced by multiple bounded contexts |
| **Cross-cutting exceptions** | `BusinessException`, `EntityNotFoundException`, `UnauthorizedException` | Exception types that cross module API boundaries in method signatures |
| **Core interfaces** | `DomainEventPublisher`, `TenantContextHolder` | Contracts for cross-cutting behaviors with module-specific implementations |

### 11.3 Prohibited Shared Kernel Contents

The following categories must never be placed in `devflow-shared-kernel`, regardless of how many modules appear to need them:

| Prohibited Category | Correct Alternative |
| :--- | :--- |
| Domain entities (`Task`, `User`, `Repository`) | Defined in their owning modules; referenced by ID across modules |
| Business logic services (`TaskService`, `CommitParser`) | Encapsulated within owning modules |
| JPA entities or Spring Data repositories | Defined in the `infrastructure/` layer of the owning module |
| REST controllers or request/response DTOs | Defined in the `interfaces/` and `api/` layers of the owning module |
| Third-party SDK integrations | Encapsulated in the owning module's `infrastructure/` layer |
| Spring framework configuration | Each module provides its own `config/` package |
| Database migration scripts | Each module owns its own Flyway migration directory |

### 11.4 The Shared Utilities Anti-Pattern

A common degradation pattern is the creation of a `common/` or `utils/` package as a dumping ground for code that does not obviously belong anywhere else. DevFlow explicitly prohibits this pattern. If a utility function is needed by multiple modules, the question to answer is not "where does this go?" but "why does this need to be shared?" The answer usually reveals one of three correct solutions:

1. The utility belongs in a specific module's `api/` package because it is a natural extension of that module's contract.
2. The utility reveals a missing abstraction that should be formalized in the domain model.
3. The utility is genuinely universal (e.g., a string normalization function) and belongs in `devflow-shared-kernel` as a pure utility with no domain semantics.

### 11.5 Shared Kernel Governance

| Gate | Requirement |
| :--- | :--- |
| Adding a new class to `devflow-shared-kernel` | ARB review and approval |
| Modifying an existing class in `devflow-shared-kernel` | ARB review and approval; all consuming modules must be tested |
| Removing a class from `devflow-shared-kernel` | ARB review, deprecation notice, and migration guide to all modules |
| Adding a new Maven dependency to `devflow-shared-kernel` | ARB review; dependency is introduced into every module's classpath |

---

## 12. Naming Conventions

### 12.1 Package Naming

All packages follow the base namespace `com.devflow.modules.<module-name>` where `<module-name>` uses lowercase with hyphens replaced by dots.

| Module | Base Package |
| :--- | :--- |
| `devflow-shared-kernel` | `com.devflow.kernel` |
| `devflow-auth` | `com.devflow.modules.auth` |
| `devflow-project-management` | `com.devflow.modules.pm` |
| `devflow-repository-intelligence` | `com.devflow.modules.repo` |
| `devflow-ai-engine` | `com.devflow.modules.ai` |
| `devflow-knowledge-base` | `com.devflow.modules.kb` |
| `devflow-developer-analytics` | `com.devflow.modules.analytics` |
| `devflow-workflow-automation` | `com.devflow.modules.automation` |
| `devflow-collaboration` | `com.devflow.modules.collaboration` |
| `devflow-notifications` | `com.devflow.modules.notifications` |

### 12.2 Class Naming

| Type | Convention | Example |
| :--- | :--- | :--- |
| Aggregate Roots and Entities | `PascalCase`, noun | `Task`, `Organization`, `ChatSession` |
| Value Objects | `PascalCase`, noun | `TaskKey`, `EmailAddress`, `DiffStats` |
| Application Services | `PascalCase` + `ApplicationService` | `TaskApplicationService` |
| Domain Services | `PascalCase` + `DomainService` | `TaskAssignmentDomainService` |
| REST Controllers | `PascalCase` + `Controller` | `TaskController` |
| JPA Repositories | `PascalCase` + `JpaRepository` | `TaskJpaRepository` |
| Domain Events | `PascalCase` + `Event` | `TaskCreatedEvent`, `PullRequestMergedEvent` |
| Module API Interfaces | `PascalCase` + `Api` | `AuthApi`, `TaskQueryApi` |
| Request DTOs | `PascalCase` + `Request` | `CreateTaskRequest` |
| Response DTOs | `PascalCase` + `Response` | `TaskResponse`, `ProjectSummaryResponse` |
| Spring `@Configuration` classes | `PascalCase` + `Configuration` | `SecurityConfiguration`, `AsyncConfiguration` |
| Test classes | Production class name + `Test` | `TaskApplicationServiceTest` |

### 12.3 File and Directory Naming

| Context | Convention | Example |
| :--- | :--- | :--- |
| Maven module directories | `devflow-<domain-name>` lowercase with hyphens | `devflow-ai-engine` |
| Java source files | Match class name exactly | `TaskApplicationService.java` |
| Flyway migration scripts | `V<version>__<description>.sql` | `V001__create_tasks_table.sql` |
| Docker Compose files | `docker-compose.<purpose>.yml` | `docker-compose.test.yml` |
| Terraform module directories | lowercase with underscores | `database/`, `networking/` |
| Documentation files | `SCREAMING_SNAKE_CASE.md` | `LOGGING_STRATEGY.md` |
| ADR files | `ADR-<NNNN>-<short-title>.md` | `ADR-0001-modular-monolith.md` |
| Environment templates | `<environment>.env.template` | `staging.env.template` |
| GitHub Actions workflow files | `<purpose>.yml` | `build.yml`, `deploy.yml` |

### 12.4 Resource Naming

| Resource | Convention | Example |
| :--- | :--- | :--- |
| Spring configuration files | `application-<module>.yml` | `application-auth.yml` |
| Test configuration files | `application-test.yml` | — |
| Fixture JSON files | `<entity>-<scenario>.json` | `task-completed.json` |
| SQL fixture scripts | `<entity>_seed.sql` | `organization_seed.sql` |

### 12.5 Naming Anti-Patterns

The following naming patterns are prohibited because they encode technical roles rather than domain intent, invite misplacement, or reduce predictability:

| Prohibited Pattern | Prohibition Rationale |
| :--- | :--- |
| `common/`, `utils/`, `helpers/` directories | Dumping ground anti-pattern. Lacks defined scope. |
| `*Manager`, `*Handler`, `*Processor` class suffixes | Vague technical role names. Use domain-specific names. |
| Numbered file variants (`TaskService2.java`) | Indicates unresolved refactoring. The old version must be removed. |
| `temp/`, `draft/`, `wip/` directories | No work-in-progress code may be committed to the repository. |
| Inconsistent abbreviation in package names | All abbreviations must be defined in this specification and used consistently. |

---

## 13. Dependency Rules

### 13.1 Layer Dependency Rules

Within each module, dependencies between architectural layers are strictly directional. Violations of these rules are detected by ArchUnit tests and fail the build.

```
  Allowed dependency directions (arrows indicate permitted imports):

  interfaces/ ──────────────────────────────────► application/
       |                                                |
       |                                                v
       |                                           domain/
       |                                           (pure Java,
       |                                           no framework)
       |                                                ^
       |                                                |
       \──────────────────────────────────► infrastructure/
                                           (implements domain
                                            interfaces)

  api/  ──► kernel only (no inward dependencies from other layers)
  config/ ──► any layer within the same module
```

**Allowed layer dependencies:**

| Source Layer | May Depend On |
| :--- | :--- |
| `interfaces/` | `application/`, `api/`, `config/` |
| `application/` | `domain/`, `api/` (current module), other modules' `api/` packages |
| `domain/` | `devflow-shared-kernel` only |
| `infrastructure/` | `domain/`, `devflow-shared-kernel`, external libraries |
| `api/` | `devflow-shared-kernel` value objects and exception types only |
| `config/` | `application/`, `infrastructure/`, external library configuration types |

**Forbidden layer dependencies:**

| Source Layer | Must NEVER Depend On |
| :--- | :--- |
| `domain/` | `infrastructure/`, `interfaces/`, `application/`, Spring Framework, JPA |
| `api/` | Internal module packages (anything outside `api/` and `kernel`) |
| `infrastructure/` | `interfaces/` |
| Any layer | Another module's non-`api/` packages |

### 13.2 Module Dependency Diagram

```
  devflow-shared-kernel
         ^
         |  (all modules depend on kernel)
         |
  devflow-auth
         ^
         |  (all feature modules depend on auth for security)
         |
  +------+----------------------------------+
  |      |                                  |
  |      v                                  v
  devflow-pm              devflow-repo-intelligence
         ^                       ^
         |                       |
         +--------+--------------+
                  |
         devflow-knowledge-base
                  ^
                  |
         devflow-ai-engine
                  ^
                  |
  +---------------+-------------------+
  |                                   |
  devflow-analytics          devflow-automation
                                      ^
                                      |
                             devflow-notifications (also consumed by automation)

  devflow-collaboration (depends only on auth + kernel)
```

### 13.3 Module Dependency Matrix

The following matrix defines compile-time dependency permissions between all modules. "Yes" indicates an allowed dependency; "No" indicates a strictly prohibited dependency. Rows represent the source module; columns represent the dependency.

| Source Module | `kernel` | `auth` | `pm` | `repo` | `kb` | `ai` | `analytics` | `automation` | `collab` | `notif` |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`shared-kernel`** | — | No | No | No | No | No | No | No | No | No |
| **`auth`** | Yes | — | No | No | No | No | No | No | No | No |
| **`pm`** | Yes | Yes | — | No | No | No | No | No | No | No |
| **`repo`** | Yes | Yes | No | — | No | No | No | No | No | No |
| **`kb`** | Yes | Yes | Yes | No | — | No | No | No | No | No |
| **`ai`** | Yes | Yes | Yes | Yes | Yes | — | No | No | No | No |
| **`analytics`** | Yes | Yes | Yes | Yes | No | No | — | No | No | No |
| **`automation`** | Yes | Yes | Yes | Yes | No | Yes | No | — | No | Yes |
| **`collab`** | Yes | Yes | No | No | No | No | No | No | — | No |
| **`notif`** | Yes | Yes | No | No | No | No | No | No | No | — |

### 13.4 Forbidden Cross-Module Patterns

| Forbidden Pattern | Prohibition Rationale |
| :--- | :--- |
| Direct access to another module's JPA repository | Violates ownership boundaries. Cross-module data access must use the owning module's `api/` interfaces. |
| Circular module dependencies | Blocked by Maven at compile time. Circular dependency reveals a missing bounded context or a misplaced concern. |
| Core modules depending on downstream modules | `auth`, `pm`, and `repo` must not depend on `analytics`, `automation`, `collaboration`, or `notifications`. Side effects flow via domain events. |
| Sharing database schemas across module boundaries | Each module owns its schema. Cross-schema SQL queries are prohibited; data must be retrieved through in-process API calls. |
| Placing business logic in `devflow-shared-kernel` | The kernel is structural only. Business logic belongs in its owning bounded context. |
| Direct instantiation of another module's domain entities | Entities are package-private. External access is through the `api/` contract. |
| Referencing another module's `infrastructure/` layer | External modules may not know how a module persists its data or integrates with external systems. |

---

## 14. Repository Governance

### 14.1 Ownership Model

Repository ownership is enforced through the `.github/CODEOWNERS` file. Every directory in the repository has at least one designated owner. Ownership grants are cumulative: a team that owns a parent directory also owns all subdirectories unless a more specific ownership rule applies.

| Directory | Owning Team | Review Requirement |
| :--- | :--- | :--- |
| `backend/devflow-shared-kernel/` | Architecture Review Board | ARB approval required for all changes |
| `backend/devflow-auth/` | Security Team + Backend Platform | Security review mandatory |
| `backend/devflow-project-management/` | Product Domain Team | Standard peer review |
| `backend/devflow-repository-intelligence/` | Integration Domain Team | Standard peer review |
| `backend/devflow-ai-engine/` | AI Platform Team | AI Platform review required |
| `backend/devflow-knowledge-base/` | Product Domain Team | Standard peer review |
| `backend/devflow-developer-analytics/` | Analytics Domain Team | Standard peer review |
| `backend/devflow-workflow-automation/` | Platform Automation Team | Standard peer review |
| `backend/devflow-collaboration/` | Product Domain Team | Standard peer review |
| `backend/devflow-notifications/` | Platform Automation Team | Standard peer review |
| `docs/architecture/` | Architecture Review Board | ARB approval required for all changes |
| `docs/security/` | Security Team + Architecture Review Board | Security review mandatory |
| `docs/adr/` | Architecture Review Board | ARB approval required for all ADRs |
| `infrastructure/` | Platform Engineering Team | Platform Engineering review required |
| `.github/` | Platform Engineering Team | Platform Engineering review required |

### 14.2 Architecture Review Board

The Architecture Review Board (ARB) is responsible for maintaining the integrity of DevFlow's architectural decisions. The ARB must review and approve:

1. Any change to `docs/architecture/` documents.
2. Any addition to or removal from `devflow-shared-kernel`.
3. Any introduction of a new Maven module.
4. Any change to the dependency matrix defined in Section 13.
5. Any new Architecture Decision Record (ADR).
6. Any proposal to introduce a new top-level directory in the repository.
7. Any change to the naming conventions defined in Section 12.

ARB review is a deliberate gate — not a bureaucratic bottleneck. Its purpose is to prevent the accumulation of structural decisions that individually seem reasonable but collectively erode architectural coherence over time.

### 14.3 Architecture Decision Records

Every significant architectural decision must be captured as an Architecture Decision Record (ADR) in `docs/adr/`. An ADR captures the context of a decision, the options considered, the chosen option, and the rationale. ADRs are never deleted — they are superseded by new ADRs when a decision changes, preserving the historical reasoning for the original choice.

ADR lifecycle states:

| State | Meaning |
| :--- | :--- |
| `Proposed` | Decision is under discussion; not yet approved |
| `Approved` | Decision is approved and in effect |
| `Deprecated` | Decision is still in effect but expected to be superseded |
| `Superseded` | Decision has been replaced by a newer ADR; references the superseding ADR |

### 14.4 Deprecation and Migration Policy

When a directory, module, or structural pattern is deprecated:

1. A `DEPRECATED.md` notice is placed in the deprecated directory explaining what replaced it and the expected removal timeline.
2. A migration ADR is created documenting the reason for the change and the migration path.
3. The deprecated directory is removed from the repository on or after the documented removal date — it is not left indefinitely as abandoned scaffolding.
4. CODEOWNERS is updated to reflect the new ownership structure before the deprecated directory is removed.

No abandoned directories, empty modules, or placeholder files may persist in the repository beyond their documented deprecation window. The repository must at all times reflect the current state of the system, not its historical accidents.

### 14.5 Review Process for Structural Changes

| Change Type | Required Reviewers | Minimum Review Time |
| :--- | :--- | :--- |
| New domain module | ARB + Platform Engineering | 5 business days |
| Changes to `devflow-shared-kernel` | ARB | 3 business days |
| New top-level directory | ARB | 5 business days |
| Changes to CI/CD pipelines | Platform Engineering | 2 business days |
| Changes to Terraform (production) | Platform Engineering + Engineering Lead | 3 business days |
| New ADR | ARB | 3 business days |
| Changes to documentation (non-architecture) | Directory owner | 1 business day |

### 14.6 Governance Roles and Responsibilities

| Role | Repository Responsibilities |
| :--- | :--- |
| **Architecture Review Board** | Approves structural changes, ADRs, new modules, and changes to shared kernel. Maintains this specification. |
| **Principal Engineer** | Enforces architectural rules during code review. Escalates structural violations to ARB. |
| **Platform Engineering Lead** | Approves infrastructure changes, CI/CD modifications, and cloud resource changes. |
| **Security Lead** | Approves changes to `devflow-auth`, `docs/security/`, and any code handling authentication or authorization. |
| **Module Owners** | Review and approve changes within their assigned directories. Maintain module quality and boundary integrity. |
| **All Engineers** | Follow naming conventions, placement rules, and dependency rules. Raise concerns via ADR proposals rather than unilateral changes. |

---

## 15. Future Evolution

### 15.1 Evolution Philosophy

The DevFlow repository is designed for progressive architectural evolution. The directory structure, module naming, and ownership model established in this specification are intentionally forward-compatible: they accommodate the platform's growth from a single-team Modular Monolith to a multi-team platform with independently deployable services, standalone tooling, and an extensible plugin ecosystem — without requiring a structural reorganization that would invalidate existing paths, break CI configurations, or require mass refactoring.

The key insight is that architectural evolution is always easier when the current structure already reflects clean boundaries. A module that is truly isolated — with a well-defined public API, an independent database schema, and no direct dependencies from other modules into its internals — can be extracted into an independent service with minimal friction. A module that has accumulated cross-cutting dependencies requires extensive refactoring before it can be safely extracted.

Every structural decision in this specification is made with extractability as a design criterion.

### 15.2 Evolution Toward Microservices

When module extraction begins, the new `services/` directory is added alongside `backend/`. The extracted module's Maven code moves from `backend/<module>` to `services/<module>-svc` and is repackaged as a standalone Spring Boot application. The `backend/` directory continues to host modules not yet extracted. This transitional coexistence is intentional and eliminates the need for a "big bang" restructuring.

```
  Phase 1: Modular Monolith          Phase 2: Selective Extraction
  ─────────────────────────          ─────────────────────────────
  backend/                           backend/        services/
  +-- devflow-shared-kernel/         +-- shared-     +-- devflow-ai-svc/
  +-- devflow-auth/                  |   kernel/     \-- devflow-repo-svc/
  +-- devflow-pm/                    +-- devflow-
  +-- devflow-repo/           ──►    |   auth/
  +-- devflow-ai/                    +-- devflow-pm/
  +-- devflow-kb/                    +-- devflow-kb/
  +-- devflow-analytics/             +-- devflow-
  +-- devflow-automation/            |   analytics/
  +-- devflow-collaboration/         \-- devflow-
  \-- devflow-notifications/             automation/


  Phase 3: Full Platform
  ─────────────────────────────────────────────────────────────
  services/                     sdks/                    cli/
  +-- devflow-auth-svc/         +-- devflow-java-sdk/    \-- devflow-cli/
  +-- devflow-pm-svc/           +-- devflow-js-sdk/
  +-- devflow-repo-svc/         \-- devflow-python-sdk/  agents/
  +-- devflow-ai-svc/                                    +-- code-review/
  +-- devflow-kb-svc/           frontend/                +-- task-planning/
  +-- devflow-analytics-svc/    +-- app/                 \-- incident/
  +-- devflow-automation-svc/   +-- mobile/
  +-- devflow-collab-svc/       \-- vscode/              plugins/
  \-- devflow-notif-svc/                                 \-- plugin-sdk/
```

### 15.3 Evolution Toward Independent SDKs

As DevFlow's public API surface matures, client SDKs in multiple languages will be introduced under a top-level `sdks/` directory. Each SDK is a completely independent project with its own build system, test suite, and release pipeline. SDK clients consume DevFlow's public API exclusively — they never depend on internal Java packages or shared Maven modules. The introduction of the `sdks/` directory requires no structural changes to `backend/`, `frontend/`, or `infrastructure/`.

### 15.4 Evolution Toward a Command-Line Interface

The DevFlow CLI will be introduced under a top-level `cli/` directory when the feature set justifies a dedicated developer tooling experience. The CLI communicates with DevFlow through the published REST API. It has no knowledge of internal module structure, database schemas, or event bus topology. Its introduction requires no structural changes to any existing top-level directory.

### 15.5 Evolution Toward Multiple Frontends

As DevFlow expands to mobile, desktop, and embedded developer tool contexts, additional frontend applications will be added under the existing `frontend/` directory. All frontend applications communicate with the backend through the published REST API. The addition of new frontend applications requires no structural changes to `backend/` or `infrastructure/`.

```
  frontend/
  +-- app/          # Primary web application (current)
  +-- mobile/       # React Native mobile application (future)
  +-- vscode/       # VS Code extension (future)
  +-- jetbrains/    # JetBrains IDE plugin (future)
  \-- storybook/    # Shared component library (current)
```

### 15.6 Evolution Toward an AI Agent Ecosystem

As DevFlow's AI capabilities mature, autonomous agents that perform complex multi-step engineering tasks will be introduced. These agents will be organized under a top-level `agents/` directory, following the same organizational principles as `backend/`: one subdirectory per agent type, each with its own dependency manifest and test suite.

```
  agents/
  +-- code-review-agent/   # Autonomous PR review and feedback agent
  +-- task-planning-agent/ # Sprint planning and task decomposition agent
  \-- incident-agent/      # On-call alert triage and root cause analysis agent
```

Each agent communicates with the DevFlow platform through the published REST API and WebSocket channels. Agents are isolated from each other and from the core application. Their introduction does not require modification to any existing module.

### 15.7 Repository Evolution Summary

| Milestone | New Top-Level Directory | Required Changes to Existing Directories |
| :--- | :--- | :--- |
| Current Modular Monolith | None required | — |
| First microservice extraction | `services/` | Move one module from `backend/` to `services/` |
| Public SDK release | `sdks/` | None |
| CLI tooling | `cli/` | None |
| Mobile frontend | None (under `frontend/`) | None |
| AI agents | `agents/` | None |
| Plugin ecosystem | `plugins/` | None |

---

## 16. Architectural Principles & Key Design Decisions

The following principles govern every repository-level decision in DevFlow. They are not aspirational guidelines — they are binding constraints enforced through code review, automated tests, and governance processes. Each principle is stated with its rationale, because a principle without rationale is indistinguishable from an arbitrary rule.

---

**Principle 1: Repository Structure Reflects Architecture**

The physical layout of the repository must correspond directly to the conceptual architecture of the system. When the two diverge — when the code lives somewhere other than where the architecture says it should — the architecture document becomes fiction and engineers navigate by the code's actual structure rather than its intended structure. DevFlow maintains this correspondence by mapping every bounded context to a Maven module, every Maven module to a predictable internal package hierarchy, and every architectural concern to a top-level directory. If a proposed code change does not fit into the existing structure without compromise, the correct response is to examine whether the code itself reflects a sound architectural decision — not to accommodate the code by relaxing structural rules.

---

**Principle 2: Modules Own Their Boundaries Completely**

A module owns everything it needs to fulfill its responsibilities: its domain entities, its application services, its infrastructure adapters, its REST controllers, its database migration scripts, and its test suite. No module depends on the internal implementation of another module. This complete ownership is the only defense against the distributed monolith failure mode — a codebase organized into separate modules that nonetheless cannot be understood, modified, or deployed independently because they share database tables, directly reference each other's package-private classes, or depend on shared implementation state. Complete ownership is expressed through Maven module boundaries enforced at compile time.

---

**Principle 3: Shared Code Is Intentionally Small**

The `devflow-shared-kernel` module contains the minimum set of abstractions necessary to prevent structural code duplication across modules. Every addition to the shared kernel must justify its inclusion against a high threshold: it must be genuinely cross-cutting, technology-agnostic, and free of domain-specific semantics. The default answer to "should this go in the shared kernel?" is no. If something is needed in multiple modules, the first question is whether it reveals a missing bounded context or a missing module API contract — not whether it should be added to the shared library. A shared kernel that grows without governance becomes the new monolith.

---

**Principle 4: Documentation Is a First-Class Repository Citizen**

Architecture documentation, API specifications, security threat models, and operational runbooks are version-controlled in the repository alongside source code and reviewed through the same pull request process. When documentation lives in the repository, it is visible to all engineers, it evolves with the code it describes, and its history is queryable through the same tools used to understand code history. Documentation that lives outside the repository is invisible to engineers who join after it was written, is not auditable, and is routinely violated because there is no mechanism to surface it during code review.

---

**Principle 5: Infrastructure Is Isolated from Business Logic**

Provisioning code, container definitions, CI/CD pipeline configurations, and monitoring stack configurations reside exclusively in `infrastructure/`. Business logic, domain entities, and application services reside exclusively in `backend/`. These two concerns have different change frequencies, different review requirements, different risk profiles, and require different expertise to evaluate. Their isolation is enforced by CODEOWNERS assignments. A change to a Kubernetes manifest should not require the same review chain as a change to the task lifecycle state machine.

---

**Principle 6: Tests Mirror Production Structure**

Every test class in the DevFlow codebase is located at a path that mirrors its corresponding production class. This is enforced by convention and code review. A test for `TaskApplicationService` belongs in the `unit/application/` test package — not in a flat `tests/` directory, not alongside other tests that happen to be in the same file, and not in a module other than `devflow-project-management`. The mirroring principle makes test navigation deterministic and ensures that orphaned tests — tests for code that no longer exists — are immediately visible during refactoring.

---

**Principle 7: Every Directory Has Exactly One Responsibility**

A directory that serves multiple purposes is a structural liability. When the boundary of a directory's responsibility is ambiguous, engineers place new files using their own judgment, and the judgment of different engineers is inconsistent. Over time, a multi-purpose directory accumulates an incoherent mix of concerns that makes it progressively harder to navigate. DevFlow enforces single-responsibility at every level of the directory hierarchy. If a new concern cannot be cleanly assigned to an existing directory without expanding its stated purpose, a new directory with a precisely defined scope is created and its scope is documented in this specification.

---

**Principle 8: Build Is Deterministic and Reproducible**

The DevFlow build produces identical output for identical inputs, regardless of when or where the build is executed. This requires pinned dependency versions, a committed Maven Wrapper, no reliance on mutable external state during the build, and the exclusion of all build artifacts from version control. A build that produces different outputs in different environments is a security and reliability risk: it means the artifact tested in staging may differ from the artifact deployed to production. Determinism eliminates this class of failure entirely and ensures that artifact provenance is unambiguous.

---

**Principle 9: No Abandoned Code, No Placeholder Directories**

Every directory, module, and file in the repository serves a current, active purpose. Deprecated directories are documented, given a removal timeline, and removed on schedule. Empty placeholder modules are not created speculatively. Commented-out code is not committed. A clean repository reflects a disciplined engineering organization. An accumulation of abandoned artifacts reflects a team that has lost control of its codebase. The governance policy defined in Section 14.4 enforces active cleanup as a mandatory practice, not an aspirational one.

---

**Principle 10: Architecture Drives Organization, Not Convention**

Repository structure in DevFlow is derived from architectural decisions — Domain-Driven Design, Clean Architecture, Modular Monolith — not from general programming conventions like "separate controllers from services" or "put tests in a separate folder." The specific choices made here are justified by the specific architecture of DevFlow. Engineers joining the project should be able to infer the repository structure from the architecture documents alone. If the structure requires explanation that cannot be derived from the architecture, the structure requires revisiting.

---

**Principle 11: Dependency Direction Is Enforced at Compile Time**

Architectural layer and module dependency rules are not guidelines enforced through code review alone. They are enforced by the Maven module system (cross-module dependencies) and by ArchUnit tests (within-module layer dependencies). A dependency that violates the rules established in Section 13 fails the build. This automated enforcement is essential because architectural discipline expressed only as a shared understanding erodes under deadline pressure. Making violations structurally impossible is more reliable than relying on every engineer to correctly recall and apply complex dependency rules under time pressure.

---

**Principle 12: Repository Evolves Without Restructuring**

The directory structure established in this specification is designed to accommodate the full evolutionary trajectory of the platform — from Modular Monolith to selectively extracted microservices, from a single web frontend to multiple client applications, from no public SDK to a multi-language SDK ecosystem — without requiring a repository reorganization. New capabilities are added to existing top-level directories or introduced as new top-level directories with precisely defined scopes. The existing structure is extended, not replaced. This forward compatibility is not achieved by accident: it requires deliberate design choices about which concerns belong at the top level and which belong nested within a stable container.

---

**Principle 13: Secrets Are Never in the Repository**

No actual secret value — database password, API key, OAuth client secret, JWT signing key, encryption key, or service account credential — may reside in any file committed to this repository. This prohibition is absolute and applies to all branches, all commits, and all file types including `.env` files, YAML configuration fragments, Docker Compose files, and test fixtures. Secret templates listing required keys without values are permitted in `infrastructure/secrets/` and `infrastructure/environments/`. Secret values are supplied through a dedicated secret management system at runtime, as documented in the Configuration Strategy.

---

**Principle 14: Module Naming Encodes Domain Language**

Module names in DevFlow are derived from the domain language of the bounded context they represent, not from technical roles. `devflow-project-management` and `devflow-repository-intelligence` communicate domain intent. `devflow-service-layer` and `devflow-data-access` would communicate technical roles — and technical-role-based naming is explicitly prohibited because it encourages layer-first organization. Layer-first organization produces codebases where all code for a given business capability is fragmented across multiple directories, forcing every engineer to understand the complete codebase to navigate to any specific piece of business logic. Domain-first naming ensures that all code for a given business concern is co-located.

---

**Principle 15: Governance Scales with Architectural Risk**

Not all repository changes carry equal architectural risk. A spelling correction in a developer guide requires one reviewer and one business day. A change to the `devflow-shared-kernel` module requires ARB approval and three business days. A new Maven module introduction requires ARB approval and five business days. The governance model defined in Section 14 is calibrated to architectural risk — not to bureaucratic uniformity. High-risk structural changes receive proportionally more scrutiny. Routine changes move at the speed of normal engineering. This calibration ensures that governance protects architectural integrity without impeding development velocity.

---

## Cross-Reference Index

The following documents are referenced in this specification and represent the complete, approved architectural context for DevFlow:

| Document | Location | Relationship |
| :--- | :--- | :--- |
| High-Level Architecture Specification | `docs/architecture/HIGH_LEVEL_ARCHITECTURE.md` | Defines the overall system design, architecture style justification, and technology decisions that this repository structure serves. |
| Domain Model Specification | `docs/architecture/DOMAIN_MODEL.md` | Defines the bounded contexts and aggregate roots that map to Maven modules in this specification. |
| Module Boundaries Specification | `docs/architecture/MODULE_BOUNDARIES.md` | Defines the inter-module dependency rules, communication patterns, and public API contracts referenced in Sections 6 and 13. |
| Configuration Strategy | `docs/configuration/CONFIGURATION_STRATEGY.md` | Defines the externalized configuration model that governs `infrastructure/environments/` and the `application-<module>.yml` pattern. |
| Logging Strategy | `docs/observability/LOGGING_STRATEGY.md` | Defines the structured logging conventions that apply to all modules and are configured in the `infrastructure/monitoring/loki/` directory. |
| Docker Setup | `docs/infrastructure/DOCKER_SETUP.md` | Defines the Docker architecture that is physically organized in `infrastructure/docker/` as described in Section 8. |

---

*This document is maintained by the Architecture Review Board. All proposed changes must be submitted for ARB review. Changes approved by the ARB are reflected in a corresponding Architecture Decision Record under `docs/adr/`.*
