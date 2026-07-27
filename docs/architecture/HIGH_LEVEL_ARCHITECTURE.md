# DevFlow — High-Level Architecture Specification

> **Version:** 1.1.0  
> **Status:** Approved / Architecture Review Board (ARB) Signed Off  
> **Author:** Architecture Review Board & Principal Architect  
> **Date:** 2026-07-27  
> **Classification:** Internal — Engineering

---

## Table of Contents

1. [Architecture Style](#1-architecture-style)
2. [Architecture Principles](#2-architecture-principles)
3. [System Overview](#3-system-overview)
4. [Component Diagram](#4-component-diagram)
5. [Data Flow](#5-data-flow)
6. [External Systems](#6-external-systems)
7. [Scalability Strategy](#7-scalability-strategy)
8. [Future Microservice Migration](#8-future-microservice-migration)
9. [Technology Decisions](#9-technology-decisions)
10. [Architecture Decision Summary](#10-architecture-decision-summary)

---

## 1. Architecture Style

### 1.1 Comparison of Architecture Patterns

| Dimension                  | Monolith                     | Modular Monolith (Maven Multi-Module)   | Microservices                          |
|----------------------------|------------------------------|-----------------------------------------|----------------------------------------|
| **Deployment complexity**  | Trivial — single artifact    | Low — single JAR, modular classpaths    | High — dozens of services, orchestration|
| **Team size fit**          | 1–5 engineers                | 3–30 engineers                          | 20+ engineers, multiple teams          |
| **Development velocity**   | Fast initially, degrades     | Fast and sustained                      | Slow initially, scales with teams      |
| **Operational overhead**   | Minimal                      | Minimal                                 | Significant (service mesh, tracing, deployment pipelines per service) |
| **Domain isolation**       | None — spaghetti risk        | Strong — package & API-level boundaries | Strongest — process-level isolation    |
| **Data consistency**       | Simple — single DB, ACID     | Simple — single DB, ACID                | Complex — eventual consistency, sagas  |
| **Refactoring cost**       | Low internally               | Low within modules, medium across       | High — contract changes cascade        |
| **Infrastructure cost**    | Lowest                       | Low                                     | Highest                               |
| **Scaling granularity**    | All or nothing               | All or nothing (initially)              | Per-service                            |
| **Migration path**         | Difficult to break apart     | Clean extraction to microservices       | Already there                          |

### 1.2 Recommendation: Modular Monolith (Multi-Module Maven Project)

**DevFlow will adopt the Modular Monolith architecture, implemented as a Multi-Module Maven project on top of Java 21 and Spring Boot 3.x.**

### 1.3 Justification

1. **Operational Simplicity for High Velocity.** DevFlow is starting with an agile team. Microservices impose a massive operational tax (distributed transactions, service discovery, cross-cutting security, network latency, contract testing) that degrades development velocity before product-market fit. A modular monolith allows the team to focus on business features while ensuring architectural discipline.

2. **Domain Evolution and Boundary Flexibility.** DevFlow spans complex domains: repository intelligence, project management, developer analytics, workflow automation, and AI orchestration. The boundaries between these domains are expected to shift as the product evolves. In a Modular Monolith, adjustments to these boundaries are done via IDE-refactorings and modifying Java package structures rather than altering HTTP/gRPC API contracts, network topologies, and deployment configurations.

3. **Transactional Integrity and ACID Simplicity.** Critical features, such as "Generate a project task from an AI repository review and update velocity metrics," require updates across multiple domain modules. In a microservices architecture, this requires complex distributed transaction patterns (e.g., Saga Pattern, Transactional Outbox, compensating transactions). The modular monolith allows these operations to run inside native Spring `@Transactional` boundaries, ensuring strict ACID consistency without operational overhead.

4. **Engineered for Decomposition.** Each module is isolated within its own Maven module (e.g., `devflow-project-management`, `devflow-ai-engine`). Inter-module communication is restricted to compiled public Java APIs and asynchronous application events. Database tables are partitioned logically via separate PostgreSQL schemas. This clean separation guarantees that any module can be extracted into an independent Spring Boot service with minimal code changes.

5. **Industry Precedents.** Enterprise platforms like Shopify, Linear, and Basecamp successfully scaled to millions of active users before extracting performance-critical hot paths. Java 21's Virtual Threads combined with Spring Boot 3.x and Spring Modulith provide runtime boundary verification and high throughput that rival microservices architectures at a fraction of the cost.

### 1.4 Advantages

- **Unified Deployment Pipeline:** A single executable JAR simplifies CI/CD pipelines, container configurations, and target infrastructure.
- **High-Performance In-Process Execution:** Calls between modules are standard Java method invocations executing in nanoseconds, eliminating network overhead, deserialization latency, and network failure modes.
- **Simplified Data Management:** A single database instance hosting schema-isolated tables allows simple ACID transactions, unified backup/restore strategies, and simplified foreign-key integrity.
- **Local Debugging and Diagnostic Efficiency:** Developers can run the entire platform locally inside a single IDE process with unified stack traces, breakpoints, and log output.
- **Fast Build Times:** Incremental Maven compilation and modular test suites isolate build overhead to modified packages.

### 1.5 Disadvantages and Mitigations

| Disadvantage                              | Mitigation                                                                 |
|-------------------------------------------|----------------------------------------------------------------------------|
| Single deployment = single failure domain | Deploy redundant instances behind active-active Load Balancers with robust health checks and automated blue-green rollback triggers. |
| Cannot scale modules independently        | Offload resource-heavy or bursty processing (e.g., AI RAG pipelines, Git repository cloning/parsing) to asynchronous Spring Integration queues processed by dedicated background thread pools or specialized JVM worker profiles. |
| Risk of module coupling over time         | Enforce boundary verification at compile time and runtime using **Spring Modulith** verification tests, forbidding cross-module package references except through designated API packages. |
| Shared Database Bottlenecks               | Implement database connection pool monitoring (HikariCP), scale PostgreSQL vertically, use read-replicas for read-heavy modules (Developer Analytics), and isolate query loads. |
| Single language stack (Java)              | Java 21's modern features, virtual threads, and rich library ecosystem (Spring AI, JGit, etc.) fully satisfy all platform requirements. Polyglot microservices are deferred. |

### 1.6 Future Migration Strategy

The multi-module Maven structure is engineered to ease future extraction. Each module maintains:
- A designated **Public API Interface** (`com.devflow.modules.X.api`) — internal services are package-private.
- Independent **domain models and entities** — shared concepts are modeled in a decoupled `devflow-shared-kernel`.
- **In-process asynchronous communication** via Spring's `ApplicationEventPublisher`, which acts as a staging ground for a distributed broker.
- **Logically namespaced schemas** (`auth`, `pm`, `repo`, `ai`) inside PostgreSQL, preventing cross-module database joins.

When modular metrics dictate extraction (e.g., the AI Engine requires specialized GPU runtimes or separate scaling factors), the transition is executed as follows:

```
Phase 1 (Current Modular Monolith)      Phase 2 (Selective Extraction)        Phase 3 (Target Microservices)
┌─────────────────────────────────┐      ┌─────────────────────────────┐        ┌─────────────────────────────┐
│  Spring Boot Modular Monolith   │      │   Spring Boot Monolith      │        │       API Gateway           │
│                                 │      │                             │        │       (Spring Cloud Gateway)│
│  ┌────────┐ ┌────────┐ ┌──────┐ │      │  ┌────────┐ ┌────────┐      │        │   ┌────┐ ┌────┐ ┌────┐      │
│  │  Auth  │ │   PM   │ │  AI  │ │      │  │  Auth  │ │   PM   │      │        │   │Auth│ │ PM │ │ AI │      │
│  └────────┘ └────────┘ └──────┘ │      │  └────────┘ └────────┘      │        │   └────┘ └────┘ └────┘      │
│                                 │ ──►  │                      ┌────┐ │  ──►   │   ┌──────────┐              │
│        [ Shared Database ]      │      │                      │ AI │─┼──►Svc  │   │Repo Intel│              │
│       PostgreSQL DB (Schemas)   │      │                      └────┘ │        │   └──────────┘              │
└─────────────────────────────────┘      └─────────────────────────────┘        └─────────────────────────────┘
```

---

## 2. Architecture Principles

### 2.1 Separation of Concerns (SoC)

Every component is designed with a single, clear responsibility. The presentation layer (Spring Web Controllers) acts solely as an interface adaptor. The business logic (Spring Services) implements domain policies. The persistence layer (Spring Data JPA / Hibernate) manages transaction boundaries and entity states. AI routing and prompt construction are encapsulated within the AI module.

**Why it matters for DevFlow:** The platform orchestrates complex processes across project boards, Git commits, vector embeddings, and analytics metrics. Without strict SoC, changes to database schemas would cascade into prompt logic, and changes to AI models would break dashboard analytics.

### 2.2 Clean Architecture & Hexagonal Architecture (Ports and Adapters)

Domain logic is kept independent of framework-specific classes, database drivers, and external API client libraries. Business domains interact with adapters via interfaces (Ports), allowing external infrastructure (e.g., swapping a Git hosting provider or an email client) to be changed with zero modifications to core business rules.

**Why it matters for DevFlow:** The AI landscape is evolving rapidly. Deeply coupling prompt structures or retrieval models to a single provider (e.g., OpenAI) would prevent DevFlow from hot-swapping to local Ollama models or specialized Anthropic models. Ports and Adapters protect the core engine.

### 2.3 SOLID Principles

- **Single Responsibility (SRP):** Classes have one reason to change. E.g., `CommitParser` only extracts metadata from Git history; it does not assign tasks or compute developer metrics.
- **Open/Closed (OCP):** Components are open for extension but closed for modification. Adding a new Git integration (e.g., Azure DevOps) requires writing a new class implementing the `GitProvider` interface, leaving existing code untouched.
- **Liskov Substitution (LSP):** Clients using the `ChatModel` interface can transparently interact with any underlying LLM integration (OpenAI, Anthropic, Gemini, Ollama) without modifying their expectations.
- **Interface Segregation (ISP):** Large interfaces are broken down into small, client-specific interfaces. Domain services only depend on the precise database access methods they need.
- **Dependency Inversion (DIP):** High-level application services depend on abstractions rather than low-level infrastructure modules. We program to interfaces, and Spring injects concrete implementations at runtime.

### 2.4 Domain-Driven Design (Lightweight)

Each module represents a Bounded Context. Within a context, developers use a Ubiquitous Language (e.g., "Sprint", "Commit", "Vector Embedding"). Cross-context boundaries are mapped using Context Maps. Entities, Value Objects, and Domain Events are defined to maintain internal consistency. Aggregate roots are used to define transactional transactional boundaries.

**Why it matters for DevFlow:** As DevFlow combines project management (Jira-style) and repository synchronization (GitHub-style), clear bounded contexts prevent the "Commit" model in the Repository module from polluting the "Task" model in the Project Management module.

### 2.5 Feature-First Package Organization

Files are organized by business domain and feature module rather than technical layer. Rather than having top-level `controllers`, `services`, and `entities` directories, the code is partitioned by Maven modules containing functional packages:

```
devflow-backend/ (Root Maven Project)
  ├── devflow-shared-kernel/
  ├── devflow-auth/
  ├── devflow-project-management/
  │     └── src/main/java/com/devflow/modules/pm/
  │           ├── controller/
  │           ├── service/
  │           ├── repository/
  │           ├── model/ (Entities, DTOs)
  │           └── event/ (Spring Domain Events)
  ├── devflow-repository-intelligence/
  ├── devflow-ai-engine/
  └── pom.xml
```

**Why it matters for DevFlow:** Feature-first organization improves cognitive locality. When an engineer works on code review automation, all relevant classes are in `devflow-repository-intelligence` and `devflow-ai-engine`, avoiding hunting through global technical folders.

### 2.6 API-First Design

API contracts are the primary deliverables. External endpoints are defined using OpenAPI 3.1 specifications. Internal interfaces, Java records, and event envelopes are designed and verified before implementation begins. Type safety is maintained from the database layer (via Hibernate and Java Types) to the presentation layer.

**Why it matters for DevFlow:** DevFlow supports a Next.js web client, a CLI tool, and a VS Code extension. API-first design ensures these clients can be developed concurrently using mock engines, guaranteeing consistent validation and error responses.

### 2.7 Cloud-Native Design

The application adheres to Twelve-Factor App methodologies. It is packaged as an OCI-compliant container image, stores all configuration in environment variables, delegates state storage to backing services (PostgreSQL, Redis, MinIO), and supports graceful shutdown signals (SIGTERM) for cloud scheduling platforms.

**Why it matters for DevFlow:** Kubernetes deployment is the ultimate scaling target. Design choices made on day one—such as stateless JVM instances, externalized configuration, and healthy readiness probes—ensure smooth operation in cloud environments.

### 2.8 Event-Driven Architecture (EDA)

Within the monolith, modules interact asynchronously using Spring's `ApplicationEventPublisher`. High-frequency or long-running work (e.g., repository syncing, email delivery, AI prompt evaluations) is triggered via events processed by specialized executor thread pools (`@Async`).

**Why it matters for DevFlow:** EDA decouples high-latency integrations (like querying external Git APIs or third-party LLMs) from the core HTTP thread pool, protecting API latency.

### 2.9 Security by Design

Security checks are enforced at every tier: transport (forced TLS 1.3), entry point (Spring Security Filter Chain), application method level (`@PreAuthorize`), and database query level (tenant filters). No sensitive credentials or secrets are stored within the code; all are externalized and injected at runtime.

**Why it matters for DevFlow:** As an engineering intelligence platform, DevFlow acts as a custodian for proprietary source code and engineering velocity data. Secure architectural defaults are critical for enterprise adoption.

### 2.10 Observability by Default

Instrumentation is built into every module using Spring Boot Actuator, Micrometer, and OpenTelemetry APIs. Every request generates a W3C trace context, propagated through background tasks and external client requests, feeding logs, metrics, and traces into a centralized observability stack.

**Why it matters for DevFlow:** Debugging transient failures in asynchronous background processes (e.g., a failed repository synchronization or a slow RAG vector search) requires distributed tracing and structured logs from the outset.

---

## 3. System Overview

### 3.1 System Context

DevFlow is structured as a multi-tenant SaaS platform. Organizations register, authorize secure access to Git repositories (via OAuth App or GitHub App), invite collaborators, and interact with unified project boards, developer dashboards, and conversational AI agents.

### 3.2 Client Applications

- **Web Application:** A responsive React 19 single-page application built on Next.js 15, styled with Tailwind CSS, using Zustand for local state management and TanStack Query for cache synchronization.
- **CLI Tool:** A command-line client built using GraalVM Native Image for JVM compilation, providing instantaneous startup times (< 20ms) for terminal-based developer interactions.
- **VS Code Extension:** A TypeScript extension that interacts with the backend APIs to display project tasks and inline AI suggestions inside the editor.

### 3.3 Backend Application

A single, scalable Spring Boot 3.x backend application running on Java 21 LTS, composed of Maven submodules representing independent bounded contexts.

### 3.4 Internal Modules

- **Auth & Identity:** Handles multi-tenant organization creation, user registration, JWT generation, OAuth2 authentication, SSO integration, and RBAC enforcement via Spring Security.
- **Project Management:** Manages projects, boards, columns, tasks, epics, cycles, and roadmaps. Implements the core task state machines.
- **Repository Intelligence:** Connects to Git providers, coordinates repository cloning/fetching via JGit, parses commit logs, tracks pull requests, and compiles repository structural data.
- **AI Engine:** Orchestrates interactions with LLMs (OpenAI, Anthropic, Gemini, Ollama) via Spring AI, manages prompt templates, executes vector embedding creation, and routes queries.
- **Knowledge Base:** Powers documentation wikis, allows linking files to tasks, and prepares documents for semantic indexing.
- **Developer Analytics:** Aggregates commit, PR, and project cycle metadata to calculate metrics such as lead time, change failure rate, deployment frequency, and MTTR (DORA metrics).
- **Workflow Automation:** Evaluates conditional triggers (e.g., "If PR is approved, move linked task to QA") and executes automated actions.
- **Collaboration:** Manages nested comments, real-time presence indicators, system mentions, and collaborative activities.
- **Notifications:** Orchestrates in-app alerts, email dispatches, and outgoing Slack/Discord webhooks.
- **Integration Hub:** Houses the API clients and auth handling for external integrations (GitHub, GitLab, MS Teams).
- **Search:** Interfaces with Elasticsearch to support full-text search across documentation, tasks, code, and database records.
- **File & Asset Management:** Handles attachment uploads, validation, and secure storage in MinIO.

### 3.5 Infrastructure Services

- **PostgreSQL 16:** Relational database storage, utilizing PostgreSQL Schemas for tenant isolation, and the `pgvector` extension for storing and querying AI vector embeddings.
- **Redis 7:** Key-value store utilized for API rate-limiting, user session caching, database query caching, and real-time WebSocket state management.
- **Elasticsearch 8:** Distributed full-text search engine and log/metric aggregator.
- **MinIO:** Enterprise-grade, S3-compatible object storage server deployed to handle file uploads, raw code repository snapshots, and database backups.
- **Spring Integration Messaging:** Native multi-threaded messaging structures backed by Redis queues for background job delivery.
- **WebSocket STOMP Server:** Embedded inside the Spring Boot container, utilizing STOMP over WebSockets for real-time collaboration.

### 3.6 External Integrations

External systems include Git hosting providers, transactional mailers, monitoring stacks, and AI provider endpoints.

### 3.7 System Context Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                   CLIENTS                                           │
│                                                                                     │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────┐   │
│   │  Web App      │  │  CLI Tool    │  │  VS Code Ext │  │  Mobile App (Future)  │   │
│   │  (Next.js)    │  │  (GraalVM    │  │  (TypeScript)│  │  (React Native)       │   │
│   │               │  │   Native)    │  │              │  │                       │   │
│   └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └───────────┬───────────┘   │
│          │                 │                  │                      │               │
└──────────┼─────────────────┼──────────────────┼──────────────────────┼───────────────┘
           │                 │                  │                      │
           ▼                 ▼                  ▼                      ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              EDGE / GATEWAY LAYER                                   │
│                                                                                     │
│   ┌─────────────────────────────────────────────────────────────────────────────┐   │
│   │                        Load Balancer / Reverse Proxy                        │   │
│   │                           (Nginx / AWS ALB)                                 │   │
│   └────────────────────────────────┬────────────────────────────────────────────┘   │
│                                    │                                                │
│   ┌────────────────────────────────┼────────────────────────────────────────────┐   │
│   │                    CDN (Static Assets, Media)                               │   │
│   └─────────────────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────┬────────────────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           DEVFLOW BACKEND APPLICATION                               │
│                         (Java 21 / Spring Boot 3.x JVM)                             │
│                                                                                     │
│   ┌─────────────────────────────────────────────────────────────────────────────┐   │
│   │              API LAYER (Spring Web Controllers + WebSocket STOMP)           │   │
│   │    ┌───────────────────────────────────────────────────────────────────┐    │   │
│   │    │ Filter Chain: Spring Security (JWT, CORS, Rate Limit, Tenant)     │    │   │
│   │    └───────────────────────────────────────────────────────────────────┘    │   │
│   └────────────────────────────────┬────────────────────────────────────────────┘   │
│                                    │                                                │
│   ┌────────────────────────────────┼────────────────────────────────────────────┐   │
│   │              INTERNAL EVENT BUS (ApplicationEventPublisher)                 │   │
│   └────────────────────────────────┼────────────────────────────────────────────┐   │
│                                    │                                                │
│   ┌────────────────────────────────┴────────────────────────────────────────────┐   │
│   │                          DOMAIN MODULES                                     │   │
│   │                                                                             │   │
│   │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐    │   │
│   │  │ Auth &       │  │ Project      │  │ Repository   │  │ AI Engine    │    │   │
│   │  │ Identity     │  │ Management   │  │ Intelligence │  │ (Spring AI)  │    │   │
│   │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘    │   │
│   │                                                                             │   │
│   │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐    │   │
│   │  │ Knowledge    │  │ Developer    │  │ Workflow     │  │ Collaboration│    │   │
│   │  │ Base         │  │ Analytics    │  │ Automation   │  │              │    │   │
│   │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘    │   │
│   │                                                                             │   │
│   │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐    │   │
│   │  │ Notifications│  │ Integration  │  │ Search       │  │ File & Asset │    │   │
│   │  │              │  │ Hub          │  │              │  │ Management   │    │   │
│   │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘    │   │
│   │                                                                             │   │
│   └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│   ┌─────────────────────────────────────────────────────────────────────────────┐   │
│   │                      ASYNC BACKGROUND THREAD POOLS                          │   │
│   │   ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐     │   │
│   │   │Repo Sync  │ │AI RAG     │ │Analytics  │ │Notif      │ │Workflow   │     │   │
│   │   │ThreadPool │ │ThreadPool │ │ThreadPool │ │ThreadPool │ │ThreadPool │     │   │
│   │   └───────────┘ └───────────┘ └───────────┘ └───────────┘ └───────────┘     │   │
│   └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└────────────────────┬──────────────┬──────────────┬──────────────┬───────────────────┘
                     │              │              │              │
                     ▼              ▼              ▼              ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          INFRASTRUCTURE SERVICES                                    │
│                                                                                     │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐   │
│   │ PostgreSQL 16│  │ Redis 7      │  │Elasticsearch8│  │ MinIO Storage        │   │
│   │              │  │              │  │              │  │                      │   │
│   │ • Primary DB │  │ • Cache      │  │ • Full-text  │  │ • File uploads       │   │
│   │ • pgvector   │  │ • Sessions   │  │   search     │  │ • Repo snapshots     │   │
│   │ • Schema-    │  │ • Pub/Sub    │  │ • Log store  │  │ • Backups            │   │
│   │   tenancy    │  │ • Rate limit │  │   (Loki)     │  │                      │   │
│   └──────────────┘  └──────────────┘  └──────────────┘  └──────────────────────┘   │
│                                                                                     │
│   ┌──────────────┐  ┌──────────────────────────────────────────────────────────┐    │
│   │ Job Queue    │  │            Observability Stack (OpenTelemetry)           │    │
│   │ (Spring      │  │   ┌──────────┐  ┌──────────┐  ┌──────────┐             │    │
│   │  Integration/│  │   │Prometheus│  │ Grafana  │  │ Loki     │             │    │
│   │  Redis)      │  │   └──────────┘  └──────────┘  └──────────┘             │    │
│   └──────────────┘  └──────────────────────────────────────────────────────────┘    │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          EXTERNAL INTEGRATIONS                                      │
│                                                                                     │
│   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│   │ GitHub   │ │ GitLab   │ │ OpenAI   │ │ Anthropic│ │ SendGrid │ │ Slack    │   │
│   └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
│   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│   │Bitbucket │ │ Google   │ │ AWS      │ │ Discord  │ │ Datadog  │ │ Stripe   │   │
│   │          │ │ Gemini   │ │ Bedrock  │ │          │ │          │ │          │   │
│   └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Component Diagram

### 4.1 Detailed Component Architecture

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                               FRONTEND (Next.js SPA)                                 │
│                                                                                      │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌──────────────┐  │
│  │ Dashboard   │ │ Project     │ │ Repository  │ │ AI          │ │ Knowledge    │  │
│  │ Views       │ │ Board/List  │ │ Explorer    │ │ Assistant   │ │ Base Editor  │  │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘ └──────────────┘  │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌──────────────┐  │
│  │ Analytics   │ │ Workflow    │ │ Settings &  │ │ Activity    │ │ Search       │  │
│  │ Dashboards  │ │ Builder     │ │ Admin Panel │ │ Feed        │ │ Interface    │  │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘ └──────────────┘  │
│                                                                                      │
│  ┌──────────────────────────────────────────────────────────────────────────────┐    │
│  │                            CLIENT INFRASTRUCTURE                             │    │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐          │    │
│  │  │ Fetch    │ │ Zustand  │ │ WebSocket│ │ NextAuth │ │ Custom   │          │    │
│  │  │ API      │ │ State    │ │ Client   │ │ Provider │ │ Tailwind │          │    │
│  │  │ Client   │ │ Store    │ │ (STOMP)  │ │          │ │ UI Kit   │          │    │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘          │    │
│  └──────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                      │
└───────────────────────────────────┬──────────────────────────────────────────────────┘
                                    │
                          HTTPS / WSS (STOMP)
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                            SPRING BOOT BACKEND CONTAINER                             │
│                                                                                      │
│  ┌──────────────────────────────────────────────────────────────────────────────┐    │
│  │                            API ADAPTER LAYER                                 │    │
│  │                                                                              │    │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐          │    │
│  │  │ Spring   │ │ STOMP    │ │ Spring   │ │ Bucket4j │ │ Logback  │          │    │
│  │  │ MVC REST │ │ WebSocket│ │ Security │ │ Redis    │ │ JSON     │          │    │
│  │  │ Controller││ Endpoint │ │ Filter   │ │ RateLmtr │ │ Appender │          │    │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘          │    │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐                                    │    │
│  │  │ Tenant   │ │ CORS     │ │ Global   │                                    │    │
│  │  │ Resolver │ │ Config   │ │ Exception│                                    │    │
│  │  │ Filter   │ │          │ │ Handler  │                                    │    │
│  │  └──────────┘ └──────────┘ └──────────┘                                    │    │
│  └──────────────────────────────────────────────────────────────────────────────┘    │
│                                    │                                                 │
│  ┌─────────────────────────────────┴────────────────────────────────────────────┐    │
│  │                 APPLICATION EVENT BUS (ApplicationEventPublisher)            │    │
│  │    Events: TaskCreatedEvent, RepoSyncedEvent, AiRequestCompletedEvent, etc.   │    │
│  └─────────────────────────────────┬────────────────────────────────────────────┘    │
│                                    │                                                 │
│  ┌─────────────────────────────────┴────────────────────────────────────────────┐    │
│  │                        DOMAIN MODULES (MAVEN MODULES)                        │    │
│  │                                                                              │    │
│  │  ┌────────────────────────────────────────────────────────────────────┐      │    │
│  │  │  AUTH & IDENTITY MODULE (devflow-auth)                             │      │    │
│  │  │  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐      │      │    │
│  │  │  │ UserService│ │ OrgService │ │ RBACEngine │ │ OAuthClient│      │      │    │
│  │  │  └────────────┘ └────────────┘ └────────────┘ └────────────┘      │      │    │
│  │  └────────────────────────────────────────────────────────────────────┘      │    │
│  │                                                                              │    │
│  │  ┌────────────────────────────────────────────────────────────────────┐      │    │
│  │  │  PROJECT MANAGEMENT MODULE (devflow-project-management)            │      │    │
│  │  │  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐      │      │    │
│  │  │  │ ProjectSvc │ │ TaskService│ │ SprintSvc  │ │ RoadmapSvc │      │      │    │
│  │  │  └────────────┘ └────────────┘ └────────────┘ └────────────┘      │      │    │
│  │  └────────────────────────────────────────────────────────────────────┘      │    │
│  │                                                                              │    │
│  │  ┌────────────────────────────────────────────────────────────────────┐      │    │
│  │  │  REPOSITORY INTELLIGENCE MODULE (devflow-repository-intelligence)  │      │    │
│  │  │  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐      │      │    │
│  │  │  │ SyncService│ │ CommitParser││ PRTracker  │ │ CodeInsight│      │      │    │
│  │  │  └────────────┘ └────────────┘ └────────────┘ └────────────┘      │      │    │
│  │  └────────────────────────────────────────────────────────────────────┘      │    │
│  │                                                                              │    │
│  │  ┌────────────────────────────────────────────────────────────────────┐      │    │
│  │  │  AI ENGINE MODULE (devflow-ai-engine)                              │      │    │
│  │  │  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐      │      │    │
│  │  │  │ Spring AI  │ │ PromptSvc  │ │ ContextBldr│ │ VectorSvc  │      │      │    │
│  │  │  │ Router     │ │            │ │            │ │            │      │      │    │
│  │  │  └────────────┘ └────────────┘ └────────────┘ └────────────┘      │      │    │
│  │  └────────────────────────────────────────────────────────────────────┘      │    │
│  │                                                                              │    │
│  │  ┌────────────────────────────────────────────────────────────────────┐      │    │
│  │  │  REMAINING SERVICES                                                │      │    │
│  │  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐             │      │    │
│  │  │  │WikiService││DORAMetrics││Workflow  │ │Presence  │             │      │    │
│  │  │  │          │ │          │ │Engine    │ │Manager   │             │      │    │
│  │  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘             │      │    │
│  │  │  ┌──────────┐ ┌──────────┐ ┌──────────┐                          │      │    │
│  │  │  │NotifPool │ │Integration││MinioAsset│                          │      │    │
│  │  │  │          │ │Manager   │ │Manager   │                          │      │    │
│  │  │  └──────────┘ └──────────┘ └──────────┘                          │      │    │
│  │  └────────────────────────────────────────────────────────────────────┘      │    │
│  │                                                                              │    │
│  └──────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                      │
│  ┌──────────────────────────────────────────────────────────────────────────────┐    │
│  │                       SHARED KERNEL (devflow-shared-kernel)                  │    │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐          │    │
│  │  │ Base     │ │ Common   │ │ Base     │ │ Base     │ │ MapStruct│          │    │
│  │  │ Entity   │ │ DTOs     │ │ Events   │ │Exceptions│ │ Config   │          │    │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘          │    │
│  └──────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                      │
└───────────┬──────────┬───────────┬──────────┬──────────┬──────────┬──────────────────┘
            │          │           │          │          │          │
            ▼          ▼           ▼          ▼          ▼          ▼
┌──────────────┐ ┌──────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌──────────────┐
│ PostgreSQL 16│ │ Redis 7  │ │Elastic │ │ MinIO  │ │Spring  │ │Observability │
│              │ │          │ │search 8│ │        │ │Task    │ │(Prometheus,  │
│ Primary DB   │ │ Cache +  │ │Search +│ │ Files +│ │Executor│ │ Grafana,     │
│ pgvector     │ │ Pub/Sub +│ │Logs    │ │ Assets │ │(Redis- │ │ Loki,        │
│ Schema RLS   │ │ Sessions │ │        │ │        │ │backed) │ │ Micrometer)  │
└──────────────┘ └──────────┘ └────────┘ └────────┘ └────────┘ └──────────────┘
```

### 4.2 Module Interaction Rules

```
┌──────────────────────────────────────────────────────────────────┐
│                    MODULE INTERACTION RULES                      │
│                                                                  │
│  ✅ ALLOWED                    ❌ PROHIBITED                      │
│  ─────────                    ────────────                       │
│  Module A → Module B's        Module A → Module B's              │
│  Public Java Interface        Internal Services/Impl             │
│                                                                  │
│  Module A → Spring Event →    Module A → Module B's              │
│  Module B Async Listener      Database Schemas Directly          │
│                                                                  │
│  Module A → Shared Kernel     Circular Dependencies              │
│  Classes                      (A → B → A via Maven build block)  │
│                                                                  │
│  Module A → Spring Boot       Direct Thread Control              │
│  Infrastructure Abstractions  (Use TaskExecutor Pools)           │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 5. Data Flow

### 5.1 Flow A: User Login (Spring Security OAuth2/JWT)

```
┌──────┐       ┌───────────┐       ┌──────────┐       ┌──────────┐       ┌──────────┐
│Client│       │Spring Sec.│       │AuthModule│       │PostgreSQL│       │  Redis   │
└──┬───┘       └─────┬─────┘       └────┬─────┘       └────┬─────┘       └────┬─────┘
   │                 │                   │                   │                  │
   │ 1. POST /api/auth/login             │                   │                  │
   │    {email, password}               │                   │                  │
   │────────────────►│                   │                   │                  │
   │                 │                   │                   │                  │
   │                 │ 2. Rate limit     │                   │                  │
   │                 │    check (Bucket4j)─────────────────────────────────────►│
   │                 │                   │                   │                  │
   │                 │ 3. Authenticate   │                   │                  │
   │                 │    credentials    │                   │                  │
   │                 │──────────────────►│                   │                  │
   │                 │                   │                   │                  │
   │                 │                   │ 4. Query user +   │                  │
   │                 │                   │    roles by email │                  │
   │                 │                   │──────────────────►│                  │
   │                 │                   │                   │                  │
   │                 │                   │ 5. Validate Hash  │                  │
   │                 │                   │    (BCryptPasswordEncoder)           │
   │                 │                   │                   │                  │
   │                 │                   │ 6. Generate JWT   │                  │
   │                 │                   │    Tokens (RS256) │                  │
   │                 │                   │                   │                  │
   │                 │                   │ 7. Cache session  │                  │
   │                 │                   │    metadata ──────┼─────────────────►│
   │                 │                   │                   │                  │
   │                 │                   │ 8. Publish Event  │                  │
   │                 │                   │    (UserLoggedIn) │                  │
   │                 │                   │                   │                  │
   │ 9. Return {accessToken,            │                   │                  │
   │    refreshToken, user}             │                   │                  │
   │◄────────────────┼───────────────────│                   │                  │
   │                 │                   │                   │                  │
```

**Steps:**
1. Client submits credentials over HTTPS to `/api/auth/login`.
2. Spring Security delegates rate validation to Bucket4j, verifying the request quota inside Redis.
3. Spring Security’s `AuthenticationManager` forwards credentials to the `AuthModule`.
4. `AuthModule` uses Spring Data JPA to query PostgreSQL (via read-only transaction) for the user record by email.
5. `BCryptPasswordEncoder` validates the submitted password against the database hash.
6. The token generator signs a JWT (access token, 15 min expiration) and a refresh token (7 days) using RS256 private keys.
7. Active token identifiers are registered in Redis to facilitate global revocation.
8. A `UserLoggedInEvent` is published asynchronously into Spring's ApplicationEventPublisher.
9. Spring Security serializes the token payload to the client response.

---

### 5.2 Flow B: Project Creation

```
┌──────┐       ┌───────────┐       ┌─────────┐       ┌──────────┐  ┌─────────────┐  ┌──────────┐
│Client│       │Spring Sec.│       │Project  │       │PostgreSQL│  │Application  │  │Analytics │
└──┬───┘       └─────┬─────┘       │Module   │       └────┬─────┘  │EventPublisher│ │Module    │
   │                 │             └────┬────┘            │        └──────┬──────┘  └────┬─────┘
   │                 │                  │                  │               │             │
   │ 1. POST /api/projects              │                  │               │             │
   │    {name, description,             │                  │               │             │
   │     key, settings}                 │                  │               │             │
   │────────────────►│                  │                  │               │             │
   │                 │                  │                  │               │             │
   │                 │ 2. Authenticate  │                  │               │             │
   │                 │    & resolve     │                  │               │             │
   │                 │    tenant (JWT)  │                  │               │             │
   │                 │                  │                  │               │             │
   │                 │ 3. Forward       │                  │               │             │
   │                 │──────────────────►                  │               │             │
   │                 │                  │                  │               │             │
   │                 │                  │ 4. MapDTO &      │               │             │
   │                 │                  │    validate model│               │             │
   │                 │                  │    (MapStruct)   │               │             │
   │                 │                  │─────────────────►│               │             │
   │                 │                  │                  │               │             │
   │                 │                  │ 5. Save Project  │               │             │
   │                 │                  │    and Board     │               │             │
   │                 │                  │    within TX     │               │             │
   │                 │                  │─────────────────►│               │             │
   │                 │                  │                  │               │             │
   │                 │                  │ 6. Publish Event │               │             │
   │                 │                  │    (ProjCreated) │               │             │
   │                 │                  │─────────────────────────────────►│             │
   │                 │                  │                  │               │             │
   │                 │                  │                  │               │ 7. Trigger  │
   │                 │                  │                  │               │    Async    │
   │                 │                  │                  │               │────────────►
   │                 │                  │                  │               │  Init      │
   │                 │                  │                  │               │  metrics   │
   │                 │                  │                  │               │             │
   │ 8. Return {project}                │                  │               │             │
   │◄────────────────┼──────────────────│                  │               │             │
   │                 │                  │                  │               │             │
```

**Steps:**
1. Client submits a request to instantiate a new project.
2. Spring Security extracts the tenant identifier from the JWT and configures the Tenant Context ThreadLocal.
3. The request is routed to the Project Management module controller.
4. Input validation (Jakarta Bean Validation) is evaluated, and MapStruct maps the incoming DTO to the `Project` entity.
5. In a Spring-managed database transaction, the project record, default workflow boards, and settings are saved.
6. The `ProjectCreatedEvent` containing metadata is published.
7. Asynchronously, the Developer Analytics module captures this event to initialize metric baselines.
8. The project object is mapped to a DTO and returned to the client.

---

### 5.3 Flow C: Task Creation

```
┌──────┐    ┌─────────┐    ┌─────────┐    ┌──────────┐  ┌──────────────┐  ┌────────┐  ┌─────────┐
│Client│    │Spring   │    │Project  │    │PostgreSQL│  │Application   │  │Spring  │  │Search   │
└──┬───┘    │Security │    │Module   │    └────┬─────┘  │EventPublisher│  │AI Engine│  │Module   │
   │        └────┬────┘    └────┬────┘         │        └──────┬───────┘  └───┬────┘  └────┬────┘
   │             │              │              │               │              │            │
   │ 1. POST /api/projects/:id/tasks           │               │              │            │
   │    {title, description, assignee}         │               │              │            │
   │────────────►│              │              │               │              │            │
   │             │              │              │               │              │            │
   │             │ 2. Verify    │              │               │              │            │
   │             │    RBAC permission          │               │              │            │
   │             │              │              │               │              │            │
   │             │ 3. Forward   │              │               │              │            │
   │             │─────────────►│              │               │              │            │
   │             │              │              │               │              │            │
   │             │              │ 4. Generate  │               │              │            │
   │             │              │    atomic task key           │              │            │
   │             │              │    (DEVF-42) │               │              │            │
   │             │              │─────────────►│               │              │            │
   │             │              │              │               │              │            │
   │             │              │ 5. Save Task │               │              │            │
   │             │              │    inside TX │               │              │            │
   │             │              │─────────────►│               │              │            │
   │             │              │              │               │              │            │
   │             │              │ 6. Publish   │               │              │            │
   │             │              │    TaskCreatedEvent          │              │            │
   │             │              │─────────────────────────────►│              │            │
   │             │              │              │               │              │            │
   │             │              │              │               │ 7a. Async    │            │
   │             │              │              │               │    trigger ──►            │
   │             │              │              │               │    AI class  │            │
   │             │              │              │               │    & labels  │            │
   │             │              │              │               │              │            │
   │             │              │              │               │ 7b. Async                 │
   │             │              │              │               │    index ─────────────────►
   │             │              │              │               │    in search              │
   │             │              │              │               │                           │
   │ 8. Return {task}           │              │               │              │            │
   │◄────────────┼──────────────│              │               │              │            │
   │             │              │              │               │              │            │
```

**Steps:**
1. Client requests task creation inside a project container.
2. Spring Security evaluates `@PreAuthorize("hasPermission(#projectId, 'task:create')")`.
3. Request is routed to the PM module task service.
4. The service generates the task index atomically within the SQL transaction scope (e.g., key `DEVF-42`).
5. The task entity is persisted in PostgreSQL.
6. A `TaskCreatedEvent` is published.
7. Asynchronous listeners process the event:
   - **7a.** The AI Engine intercepts the event, constructs an enrichment prompt, and queries the configured LLM to classify and label the task.
   - **7b.** The Search module indexes the task content in Elasticsearch.
8. The finalized task entity is returned to the client.

---

### 5.4 Flow D: AI Request (Contextual Chat / SSE Stream)

```
┌──────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│Client│    │Spring    │    │Spring AI │    │  Redis   │  │PostgreSQL│  │External  │  │Observabi-│
│      │    │Security  │    │Engine    │    │ (Cache)  │  │(pgvector)│  │LLM API   │  │lity Stack│
└──┬───┘    └────┬─────┘    └────┬─────┘    └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
   │             │              │              │             │             │             │
   │ 1. POST /api/ai/chat        │              │             │             │             │
   │    {prompt, context}        │              │             │             │             │
   │────────────►│              │              │             │             │             │
   │             │              │              │             │             │             │
   │             │ 2. Validate  │              │             │             │             │
   │             │    tenant /  │              │             │             │             │
   │             │    quota     │              │             │             │             │
   │             │              │              │             │             │             │
   │             │ 3. Forward   │              │             │             │             │
   │             │─────────────►│              │             │             │             │
   │             │              │              │             │             │             │
   │             │              │ 4. Build context           │             │             │
   │             │              │    (Query vector database) │             │             │
   │             │              │───────────────────────────►│             │             │
   │             │              │              │             │             │             │
   │             │              │ 5. Retrieve  │             │             │             │
   │             │              │    embeddings│             │             │             │
   │             │              │◄───────────────────────────│             │             │
   │             │              │              │             │             │             │
   │             │              │ 6. Check cache             │             │             │
   │             │              │─────────────►│             │             │             │
   │             │              │              │             │             │             │
   │ 7. Open SSE Connection     │              │             │             │             │
   │◄ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │              │             │             │             │
   │             │              │ 8. Stream request          │             │             │
   │             │              │    (Spring AI ChatModel)   │             │             │
   │             │              │─────────────────────────────────────────►│             │
   │             │              │              │             │             │             │
   │ 9. Stream response chunks  │              │             │             │             │
   │◄ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┼──────────────┼─────────────┼─────────────│             │
   │             │              │              │             │             │             │
   │             │              │ 10. Update Cache           │             │             │
   │             │              │─────────────►│             │             │             │
   │             │              │              │             │             │             │
   │             │              │ 11. Log token metrics (Micrometer) ───────────────────►│
   │             │              │              │             │             │             │
```

**Steps:**
1. Client requests an AI completion via `/api/ai/chat`.
2. Spring Security verifies valid user session and rate limits the request.
3. The request payload is forwarded to the Spring AI module.
4. The AI Engine reads semantic documents from PostgreSQL via the `pgvector` store using Cosine Similarity queries.
5. Semantic vectors are returned to construct the prompt context.
6. The prompt cache in Redis is evaluated to prevent duplicate LLM execution.
7. The server opens an HTTP Server-Sent Events (SSE) stream back to the client (`SseEmitter`).
8. Spring AI initiates a streaming call to the target LLM API (OpenAI/Anthropic/Gemini/Ollama) using `ChatClient.stream()`.
9. The stream chunks are written to the client in real-time as they arrive.
10. The complete response is cached in Redis.
11. Token usage metrics are recorded in Micrometer.

---

### 5.5 Flow E: Repository Synchronization

```
┌──────────┐  ┌───────────┐  ┌──────────────┐  ┌───────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│GitHub App│  │Spring Sec.│  │Repo Sync     │  │Spring Task│  │PostgreSQL│  │  Redis   │  │Elastic-  │
│(Webhook) │  │Endpoint   │  │Service       │  │Executor   │  │          │  │  (Cache) │  │  search  │
└────┬─────┘  └─────┬─────┘  └──────┬───────┘  └─────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │              │               │                │             │             │             │
     │ 1. Delivery  │               │                │             │             │             │
     │    webhook   │               │                │             │             │             │
     │─────────────►│               │                │             │             │             │
     │              │               │                │             │             │             │
     │              │ 2. Validate   │               │             │             │             │
     │              │    signature  │                │             │             │             │
     │              │               │                │             │             │             │
     │              │ 3. Forward to │                │             │             │             │
     │              │    service    │                │             │             │             │
     │              │──────────────►│                │             │             │             │
     │              │               │                │             │             │             │
     │              │               │ 4. Enqueue     │             │             │             │
     │              │               │    Sync Job ──►│             │             │             │
     │              │               │                │             │             │             │
     │ 5. HTTP 202 ACK              │                │             │             │             │
     │◄─────────────│               │                │             │             │             │
     │              │               │                │ 6. Process  │             │             │
     │              │               │                │    Job ────►│             │             │
     │              │               │                │             │             │             │
     │              │               │ 7. Query Git API             │             │             │
     │◄─────────────────────────────┼────────────────│             │             │             │
     │─────────────────────────────►│                │             │             │             │
     │              │               │                │             │             │             │
     │              │               │ 8. Analyze &   │             │             │             │
     │              │               │    persist database updates  │             │             │
     │              │               │─────────────────────────────►│             │             │
     │              │               │                │             │             │             │
     │              │               │ 9. Invalidate cache          │             │             │
     │              │               │───────────────────────────────────────────►│             │
     │              │               │                │             │             │             │
     │              │               │ 10. Index search updates                   │             │
     │              │               │─────────────────────────────────────────────────────────►│
     │              │               │                │             │             │             │
```

**Steps:**
1. GitHub delivers a webhook event indicating repository updates.
2. The endpoint verifies signature authenticity using SHA-256 HMAC keys.
3. The parsed payload is handed over to the repository synchronization service.
4. A synchronization task is scheduled in the Spring Thread Pool Executor (backed by Redis to persist job queues).
5. The gateway returns an immediate HTTP 202 Accepted response to GitHub.
6. A background execution thread picks up the job.
7. The service executes API requests to GitHub using integration tokens to fetch metadata, commits, and diffs.
8. The fetched data is parsed and persisted to PostgreSQL using Hibernate batch processing.
9. Cache keys corresponding to repository metrics are invalidated in Redis.
10. The sync results are indexed in Elasticsearch.

---

## 6. External Systems

### 6.1 Git Providers

- **GitHub App:** Primary integration. Authenticates via OAuth 2.0 and JWT (GitHub App installation tokens). Uses Git REST APIs and GraphQL v4 APIs to sync repositories and track pull requests.
- **GitLab:** Integrates via REST API v4, OAuth, and webhooks.
- **Bitbucket:** Integrates via REST API v2, OAuth, and webhooks.
- **Azure DevOps:** Supports repository sync and pipeline monitoring.

### 6.2 Authentication Providers

- **Google OAuth / Workspace:** Facilitates SSO and Google Workspace authentication via Spring Security OAuth2.
- **GitHub OAuth:** Authenticates developers using their GitHub credentials.
- **SAML 2.0:** Integrates with corporate identity providers (Okta, Ping Identity, Azure AD) for Enterprise organizations.

### 6.3 AI / LLM Providers

- **OpenAI:** Provides advanced reasoning and code parsing models (GPT-4o, GPT-4.1) via Spring AI OpenAI bindings.
- **Anthropic:** Used for deep contextual code reviews and architectural analysis (Claude models).
- **Google Gemini:** Leveraged for long-context code repository analysis.
- **Ollama:** Facilitates self-hosted local model executions (e.g., Llama 3, Codegemma) for offline developer workspaces and strict data security compliance.

### 6.4 Email Services

- **SendGrid / Resend:** Delivers transactional messages, invitations, and alerts via REST APIs.
- **AWS SES:** Provides fallback capacity and cost-effective bulk email dispatches.

### 6.5 Notification Platforms

- **Slack:** Outgoing alerts and interactive notifications via Slack App APIs.
- **Discord:** Integrates with developer workspaces via webhooks.
- **Microsoft Teams:** Delivers corporate messaging updates via the Graph API.

### 6.6 Cloud Storage

- **MinIO:** S3-compatible local/self-hosted object storage used for development and local deployments.
- **AWS S3 / Cloudflare R2:** Production object storage hosting persistent workspace assets with low data egress fees.

### 6.7 Monitoring & Observability

- **Prometheus:** Collects application-level JVM, Spring Boot, and business metrics.
- **Grafana:** Visualizes metrics and traces.
- **Loki:** Aggregates JSON application logs.
- **Sentry:** Captures uncaught JVM and frontend exceptions in real-time.

### 6.8 CI/CD Systems

- **GitHub Actions / GitLab CI:** Synchronizes build statuses and deployment metrics to calculate DORA analytics.

### 6.9 Payments

- **Stripe:** Manages multi-tenant billing subscriptions and metered API consumption.

---

## 7. Scalability Strategy

### 7.1 Growth Stage Evolution

```
┌────────────────────────────────────────────────────────────────────────────────────┐
│                        DEVFLOW SCALING EVOLUTION                                    │
│                                                                                    │
│  Stage 1: MVP              Stage 2: Early Startup    Stage 3: Growing SaaS         │
│  (0 → 1K users)            (1K → 50K users)          (50K → 500K users)            │
│                                                                                    │
│  ┌──────────────┐          ┌──────────────┐          ┌──────────────────────┐       │
│  │ 1 Server     │          │ 2-4 App      │          │ 4-12 App Instances   │       │
│  │ 1 DB         │          │ Instances    │          │ DB Primary + 2 Read  │       │
│  │ 1 Redis      │          │ DB Primary+  │          │ Replicas             │       │
│  │              │          │ 1 Read       │          │ Redis Cluster        │       │
│  │ $50-200/mo   │          │ Replica      │          │ ES Cluster (3 nodes) │       │
│  │              │          │ Redis HA     │          │ Multiple Worker Pools│       │
│  └──────────────┘          │              │          │                      │       │
│                            │ $500-2K/mo   │          │ $5K-20K/mo           │       │
│                            └──────────────┘          └──────────────────────┘       │
│                                                                                    │
│  Stage 4: Enterprise Scale                                                          │
│  (500K → 10M+ users)                                                               │
│                                                                                    │
│  ┌────────────────────────────────────────────────────────────────────────┐         │
│  │ Auto-scaling App Clusters    │  Multi-Region Deployment              │         │
│  │ Sharded PostgreSQL           │  Global CDN                           │         │
│  │ Redis Cluster (multi-zone)   │  Dedicated AI Worker Fleet            │         │
│  │ ES Cluster (6+ nodes)        │  Selective Microservice Extraction    │         │
│  │ Dedicated Job Worker Fleet   │  Event-driven architecture (Kafka)    │         │
│  │                              │                                       │         │
│  │ $50K-200K+/mo                │                                       │         │
│  └────────────────────────────────────────────────────────────────────────┘         │
│                                                                                    │
└────────────────────────────────────────────────────────────────────────────────────┘
```

### 7.2 Stage 1: MVP (0 → 1,000 Users)
- **Application Server:** Single instance of Spring Boot on 2 vCPU, 4GB RAM.
- **PostgreSQL:** Managed DB instance, 2 vCPU, 8GB RAM.
- **Redis:** Single node, 1GB RAM cache.
- **Elasticsearch:** Deactivated. Search falls back to PostgreSQL `LIKE` and `tsvector` queries.
- **Storage:** Local MinIO instance running on SSD storage.
- **Deployment:** Docker Compose on a single Linux instance.

### 7.3 Stage 2: Early Startup (1,000 → 50,000 Users)
- **Application Server:** 2-4 instances running behind an ALB.
- **PostgreSQL:** Primary DB + 1 Read Replica for read queries.
- **Redis:** Primary-replica Sentinel configuration for High Availability.
- **Elasticsearch:** Single node (4 vCPU, 8GB RAM) for task and documentation search.
- **Workers:** Background processes separated from API runtimes via Spring Profiles.
- **Deployment:** Containerized deployment using Docker on managed instances.

### 7.4 Stage 3: Growing SaaS (50,000 → 500,000 Users)
- **Application Server:** Auto-scaling cluster of 4-12 instances.
- **PostgreSQL:** Primary DB + 2 Read Replicas.
- **Redis:** Distributed Redis Cluster (3 shards, 3 replicas).
- **Elasticsearch:** Deployed as a 3-node cluster to handle search load and logs.
- **Workers:** Isolated Spring profiles for background task workers.
- **Connection Pool:** PgBouncer introduced to multiplex Postgres connections.
- **CDN:** Cloudflare edge caching for static assets.

### 7.5 Stage 4: Enterprise Scale (500,000 → 10M+ Users)
- **Application Servers:** Regional app instances deployed using Kubernetes (EKS/GKE).
- **PostgreSQL:** Sharded database using Citus or Aurora Serverless.
- **Redis:** Multi-region Redis Cluster.
- **Observability:** Distributed OpenTelemetry tracing collector.
- **AI Processing:** Dedicated worker fleet.
- **Microservices:** Selective extraction of the AI Engine and Repository Intelligence modules.

### 7.6 Scaling Framework
- **App Servers:** Scale horizontally using auto-scaling groups based on CPU/Memory thresholds.
- **Database:** Scale vertically to maximum cloud limits, then scale horizontally using read-replicas and Citus sharding.
- **Virtual Threads:** Java 21 Virtual Threads ensure the system handles thousands of concurrent requests without thread starvation.

### 7.7 Stateless JVM Design
All application servers run as stateless instances:
- User sessions are stored in Redis.
- File assets are stored in MinIO.
- Background tasks are coordinated via Redis.
- Configuration is loaded at startup from environment variables or Spring Cloud Config.

### 7.8 Caching Architecture
- **CDN Cache:** Caches static assets, client code, and public API responses at the edge.
- **Application Cache (Redis):** Caches user sessions, organization settings, and AI query results using Spring Cache.
- **Database Query Cache:** Caches frequently read, slowly changing queries.
- **Cache Invalidation:** Utilizes a write-through strategy with Time-To-Live (TTL) limits.

### 7.9 AI Workloads & Scaling
AI processing runs on dedicated, asynchronously managed JVM pools, protecting web API performance. If external LLM API rate limits are hit, Spring AI routes queries to fallback providers or queues them for processing.

---

## 8. Future Microservice Migration

### 8.1 Migration Priority Order

Modules are prioritized for microservices extraction based on resource utilization and business boundaries:

| Priority | Module                    | Reason for Extraction                                      |
|----------|---------------------------|------------------------------------------------------------|
| 1st      | **AI Engine**             | CPU/GPU intensive. Scales independently from standard API traffic. |
| 2nd      | **Repository Intelligence**| Disk I/O intensive. Requires local workspace storage.      |
| 3rd      | **Notifications**         | Highly asynchronous. Offloads third-party network delays.  |
| 4th      | **Search**                | Memory-intensive. Interfaces with Elasticsearch.          |
| 5th      | **Developer Analytics**   | Read-heavy analytics processing.                          |
| Last     | **Project Management**    | Core domain. Left in the monolith to simplify transactional logic. |

### 8.2 Communication Evolution

```
┌──────────────────────────────────────────────────────────────────────────┐
│                  COMMUNICATION EVOLUTION                                  │
│                                                                          │
│  Phase 1: In-Process (Current)                                           │
│  ┌──────────┐ ──ApplicationEventPublisher──► ┌──────────┐                 │
│  │ Module A │                                │ Module B │                 │
│  └──────────┘ ◄───────method call──────────── └──────────┘                 │
│                                                                          │
│  Phase 2: Redis Pub/Sub (Pre-Extraction)                                 │
│  ┌──────────┐ ──────event──────► ┌──────────┐ ──────event──────► ┌──────┐ │
│  │ Module A │                    │  Redis   │                    │Mod. B│ │
│  └──────────┘                    │ Pub/Sub  │                    └──────┘ │
│                                  └──────────┘                             │
│                                                                          │
│  Phase 3: Distributed Broker (Extracted)                                  │
│  ┌──────────┐ ─────publish─────► ┌──────────┐ ─────consume─────► ┌──────┐ │
│  │Service A │                    │  Apache  │                    │Svc. B│ │
│  └──────────┘                    │  Kafka   │                    └──────┘ │
│                                  └──────────┘                             │
│                                                                          │
│  Synchronous Communication (Internal APIs):                              │
│  ┌──────────┐ ─────────────gRPC / HTTP Client──────────────────► ┌──────┐ │
│  │Service A │                                                    │Svc. B│ │
│  └──────────┘ ◄────────────Protobuf Response──────────────────── └──────┘ │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### 8.3 API Gateway Integration
An API gateway (Spring Cloud Gateway) routes client requests to extracted services:

```
                            ┌─────────────────────────┐
                            │  SPRING CLOUD GATEWAY   │
                            │                         │
    Clients ───────────────►│  • JWT Validation       │
                            │  • Rate Limiting        │
                            │  • Path Routing         │
                            │  • CORS Enforcement     │
                            │                         │
                            └─────┬─────┬─────┬───────┘
                                  │     │     │
                       ┌──────────┘     │     └──────────┐
                       ▼                ▼                ▼
                 ┌──────────┐    ┌──────────┐    ┌──────────┐
                 │ Monolith │    │AI Engine │    │ Repo     │
                 │ (Core)   │    │ Service  │    │ Service  │
                 └──────────┘    └──────────┘    └──────────┘
```

### 8.4 Event-Driven Infrastructure
Spring Modulith events are migrated to an external messaging infrastructure:

| Component             | Monolith Phase                | Microservices Phase           |
|-----------------------|-------------------------------|-------------------------------|
| **Event Transport**   | ApplicationEventPublisher     | Apache Kafka / RabbitMQ       |
| **Job Queue**         | Spring TaskExecutor (Redis)   | RabbitMQ                      |
| **Data Consistency**  | Local Database Transactions  | Saga Pattern / Outbox Pattern |
| **Schema Validation** | Java Records                  | Avro / Protobuf               |

### 8.5 Database Isolation Strategy
During service extraction, schemas are migrated from a shared database to dedicated database instances:

```
Step 1: Schema Logical Separation      Step 2: Database Splitting
┌─────────────────────────────────┐    ┌──────────────┐  ┌──────────────┐
│       Single PostgreSQL         │    │   Core DB    │  │    AI DB     │
│ ┌────────┐ ┌────────┐ ┌───────┐ │    │ (Postgres)   │  │  (Postgres)  │
│ │Schema  │ │Schema  │ │Schema │ │    │              │  │  pgvector    │
│ │ auth   │ │   pm   │ │  ai   │ │    └──────────────┘  └──────────────┘
│ └────────┘ └────────┘ └───────┘ │    ┌──────────────┐
└─────────────────────────────────┘    │   Repo DB    │
                                       │  (Postgres)  │
                                       └──────────────┘
```

### 8.6 Migration Risks and Mitigations
- **Eventual Consistency Latency:** Mitigation: Implement UI optimistic updates and WebSocket sync events.
- **Cascading Failures:** Mitigation: Implement Resilience4j circuit breakers and fallback interfaces.
- **Distributed Query Complexity:** Mitigation: Build read-only materialized views for dashboards via Elasticsearch.

---

## 9. Technology Decisions

### 9.1 Frontend

| Layer            | Technology              | Why Selected                                                          | Alternatives Considered       | Trade-off                                           |
|------------------|-------------------------|-----------------------------------------------------------------------|-------------------------------|-----------------------------------------------------|
| **Framework**    | Next.js 15 (React 19)   | Support for SSR, SSG, and App Router for dynamic dashboard layouts.   | Vue.js, Svelte, Angular       | Larger bundle sizes; managed via code splitting.    |
| **Language**     | TypeScript 5.x          | Code safety, explicit type contracts matching the backend APIs.        | JavaScript                    | Compilation step; necessary for project scale.       |
| **State Mgmt**   | Zustand + TanStack Query| Zustand for local state; TanStack Query for caching and API sync.     | Redux Toolkit, Jotai          | Unopinionated; requires code conventions.           |
| **Styling**      | Tailwind CSS 4          | Fast development, utility-first styling, and optimized tree-shaking.  | CSS Modules                   | Verbose class declarations in HTML markup.          |
| **Component Lib**| Radix UI + shadcn/ui    | Unstyled, accessible component primitives allowing custom brand design.| MUI, Chakra UI                | Custom styling configuration overhead.              |
| **Real-time**    | STOMP client            | Standards-compliant WebSocket message format mapping to Spring backend.| Raw WebSockets, Socket.io     | Dependency overhead; justified by routing features. |
| **Rich Text**    | Tiptap (ProseMirror)    | Extensible framework for building block-based editors.                | Slate.js, Draft.js            | Stiff learning curve.                               |

### 9.2 Backend

| Layer            | Technology              | Why Selected                                                          | Alternatives Considered       | Trade-off                                           |
|------------------|-------------------------|-----------------------------------------------------------------------|-------------------------------|-----------------------------------------------------|
| **Runtime**      | Java 21 LTS             | Virtual Threads (Project Loom) handle concurrent requests efficiently.| Go, Rust, Node.js             | Higher memory baseline than Rust/Go.                |
| **Build Tool**   | Maven 3.9               | Standardized build cycles and dependency management.                   | Gradle                       | Verbose XML configuration.                          |
| **Framework**    | Spring Boot 3.x         | Mature ecosystem, dependency injection, and integration with cloud APIs.| Quarkus, Micronaut            | JVM cold-start latency; mitigated via GraalVM.      |
| **ORM**          | Spring Data JPA         | Integrates with Hibernate 6.x to simplify database operations.        | MyBatis, JOOQ                 | Query translation overhead.                         |
| **API Mapping**  | MapStruct               | High-performance DTO-to-Entity mapping generated at compile-time.      | ModelMapper                   | Code generation configuration overhead.             |
| **Validation**   | Jakarta Bean Validation | Standard validation annotations (`@NotNull`, `@Size`).                 | Manual Java Validation        | Validation annotations are tied to model classes.   |
| **Event Bus**    | ApplicationEvents       | Synchronous and asynchronous events within a single JVM.              | Guava EventBus                | Local to a single JVM.                              |
| **Job Queue**    | Spring TaskScheduler    | Configurable task pools executing background jobs.                    | Quartz Scheduler              | Local to JVM; needs Redis backing for scale.        |

### 9.3 Database

| Layer            | Technology              | Why Selected                                                          | Alternatives Considered       | Trade-off                                           |
|------------------|-------------------------|-----------------------------------------------------------------------|-------------------------------|-----------------------------------------------------|
| **Primary DB**   | PostgreSQL 16           | ACID transactions, JSONB, and row-level security for multi-tenancy.    | MySQL, CockroachDB            | Manual setup required for active-active setups.     |
| **Vector DB**    | pgvector                | Stores and queries vector embeddings inside the primary database.       | Pinecone, Milvus, Qdrant      | Relational performance limits for large datasets.  |
| **Pool Manager** | HikariCP                | High-performance database connection pool configured by Spring Boot.  | Commons DBCP2                 | Limited dynamic tuning settings.                    |

### 9.4 Caching

| Layer            | Technology              | Why Selected                                                          | Alternatives Considered       | Trade-off                                           |
|------------------|-------------------------|-----------------------------------------------------------------------|-------------------------------|-----------------------------------------------------|
| **Cache Storage**| Redis 7                 | Distributed cache, rate-limiting, and WebSocket session store.        | Memcached                     | Additional infrastructure component to maintain.    |

### 9.5 Search

| Layer            | Technology              | Why Selected                                                          | Alternatives Considered       | Trade-off                                           |
|------------------|-------------------------|-----------------------------------------------------------------------|-------------------------------|-----------------------------------------------------|
| **Search Engine**| Elasticsearch 8         | Distributed full-text search, vector query capabilities, and analytics.| Meilisearch, Typesense        | High memory consumption.                             |

### 9.6 Object Storage

| Layer            | Technology              | Why Selected                                                          | Alternatives Considered       | Trade-off                                           |
|------------------|-------------------------|-----------------------------------------------------------------------|-------------------------------|-----------------------------------------------------|
| **S3 Storage**   | MinIO                   | Deploys locally as an S3-compatible asset store.                      | Local File System             | Requires infrastructure provisioning.               |

### 9.7 Messaging

| Layer            | Technology              | Why Selected                                                          | Alternatives Considered       | Trade-off                                           |
|------------------|-------------------------|-----------------------------------------------------------------------|-------------------------------|-----------------------------------------------------|
| **Phase 1**      | Redis Queues            | Asynchronous job management using Redis data structures.              | RabbitMQ, AWS SQS             | Memory limits.                                      |
| **Phase 3+**     | Apache Kafka            | Distributed event streaming, logging, and message durability.          | RabbitMQ, ActiveMQ            | Complex operations management.                      |

### 9.8 Observability

| Layer            | Technology              | Why Selected                                                          | Alternatives Considered       | Trade-off                                           |
|------------------|-------------------------|-----------------------------------------------------------------------|-------------------------------|-----------------------------------------------------|
| **Metrics**      | Micrometer / Actuator   | Standard metrics collection for Spring Boot.                          | Dropwizard Metrics            | Setup required for custom metrics.                  |
| **Traces**       | OpenTelemetry           | Industry-standard distributed tracing.                                 | Jaeger SDK                    | Integration overhead.                               |
| **Logs**         | Logback JSON / Loki     | Logs formatted as structured JSON and aggregated in Grafana Loki.      | ELK Stack                     | Resource footprint of agents.                       |

### 9.9 Deployment & Infrastructure

| Layer            | Technology              | Why Selected                                                          | Alternatives Considered       | Trade-off                                           |
|------------------|-------------------------|-----------------------------------------------------------------------|-------------------------------|-----------------------------------------------------|
| **Containers**   | Docker                  | Reproducible application builds.                                      | Podman                        | Docker Desktop licensing limitations.                |
| **Orchestrator** | Kubernetes              | Container orchestration, scaling, and self-healing deployments.        | Nomad, Docker Swarm           | Significant configuration complexity.               |
| **IaC**          | Terraform               | Multi-cloud infrastructure as code.                                   | Ansible, Pulumi               | State management configuration overhead.            |
| **CI/CD**        | GitHub Actions          | Integrated build and test execution pipelines.                        | GitLab CI, Jenkins            | Bound to the GitHub platform.                       |

### 9.10 Architecture Constraints & MVP Simplification

For the MVP phase, the architecture is simplified to reduce infrastructure overhead:

```
┌──────────────────────────────────────────────────────────────────────────┐
│                   MVP SIMPLIFIED RUNTIME STACK                           │
│                                                                          │
│  Frontend:   Next.js 15 + Tailwind CSS + Zustand + TanStack Query        │
│  Backend:    Java 21 + Spring Boot 3.x + Spring Data JPA + Hibernate     │
│  Database:   PostgreSQL 16 (Relational DB & pgvector for Search)         │
│  Cache:      Redis 7 (Sessions, rate-limiting, and queues)               │
│  Storage:    MinIO (Local S3-compatible filesystem storage)              │
│  Messaging:  Spring ApplicationEventPublisher (In-JVM Async Events)      │
│  Deploy:     Docker Compose                                              │
│  Monitor:    Logback JSON + Spring Boot Actuator                         │
│                                                                          │
│  DEFERRED:   Elasticsearch, Apache Kafka, Kubernetes, multi-provider AI  │
│              SSO configurations, AWS deployment pipelines                │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 10. Architecture Decision Summary

| #  | Decision Area              | Decision                                    | Rationale                                                  | Risk / Trade-off                                    |
|----|---------------------------|---------------------------------------------|------------------------------------------------------------|-----------------------------------------------------|
| 1  | Architecture Style         | Modular Monolith                            | Low operational overhead, simple transactions.             | Scaled together. Mitigated by worker isolation.    |
| 2  | Backend Language           | Java 21                                     | Virtual Threads, rich ecosystem, and compiler performance. | Higher memory usage.                                |
| 3  | Backend Framework          | Spring Boot 3.x                             | Standardized integrations, dependency injection.            | JVM startup times. Mitigated by GraalVM.            |
| 4  | Frontend Framework         | Next.js 15 (React 19)                       | SSR, SSG, and App Router for dynamic layouts.              | Larger bundle sizes. Managed by code splitting.     |
| 5  | Database                   | PostgreSQL 16                               | Row-level security and JSONB query support.                | Scaling requires sharding at enterprise scale.     |
| 6  | Cache                      | Redis 7                                     | Distributed cache, rate-limiting, and state management.    | Memory limits.                                      |
| 7  | Search Engine              | PostgreSQL (MVP) → Elasticsearch (Phase 2)  | Simplify MVP. Scale to Elasticsearch when query volume demands. | Postgres FTS lacks relevance ranking.               |
| 8  | Object Storage             | MinIO                                       | Local S3-compatible asset store.                           | Requires infrastructure provisioning.               |
| 9  | Job Queue                  | Spring TaskExecutor (Redis)                 | Asynchronous job management using Redis.                   | Memory limits. Kafka for Phase 3.                  |
| 10 | AI Integration             | Spring AI                                   | Native Spring LLM integration, prompt management.          | Tightly coupled to Spring runtime.                  |
| 11 | Real-time Communication    | STOMP over WebSockets                       | Standards-compliant WebSocket message routing.             | Protocol overhead.                                  |
| 12 | ORM                        | Hibernate 6.x                               | ORM framework integrated with Spring Data JPA.             | Query translation overhead.                         |
| 13 | Module Communication       | ApplicationEvents                           | In-JVM asynchronous events.                                 | Local to single JVM instance.                       |
| 14 | Multi-tenancy Strategy     | Schema-based Isolation                      | Separates tenant data while sharing database instances.    | Schema migration overhead.                         |
| 15 | Deployment (MVP)           | Docker Compose                              | Simplifies local development and deployments.              | Scaling limits.                                     |
| 16 | Deployment (Scale)         | Kubernetes                                  | Container orchestration and auto-scaling.                  | Significant configuration complexity.               |
| 17 | CI/CD                      | GitHub Actions                              | Integrated build and test execution pipelines.            | Bound to the GitHub platform.                       |
| 18 | Infrastructure as Code     | Terraform                                   | Multi-cloud infrastructure as code.                       | State management configuration overhead.            |
| 19 | Observability              | OpenTelemetry + Grafana Stack               | Structured logs, metrics, and traces.                      | Resource footprint of collectors.                   |
| 20 | Code Organization          | Feature-first (domain modules)              | Locality of reference, modular boundaries.                 | Duplication of shared logic.                        |
| 21 | API Style                  | REST + STOMP WebSockets                     | Standard HTTP APIs and WebSocket routing.                  | Lacks GraphQL query flexibility.                    |
| 22 | Authentication             | JWT (RS256)                                 | Secure stateless sessions with revocation in Redis.        | Token size overhead.                                |
| 23 | Rich Text Editor           | Tiptap (ProseMirror-based)                  | Extensible editor framework.                               | Stiff learning curve.                               |
| 24 | First Microservice Extract | AI Engine                                   | Heavy CPU/GPU resource usage.                              | Distributed transactions required.                  |

---

## 11. Core System Configurations & Design

### 11.1 Multi-tenancy Strategy
DevFlow implements a **Pool Multi-tenancy** model using **logical schema separation** in PostgreSQL. Hibernate's `MultiTenancyConnectionProvider` and `CurrentTenantIdentifierResolver` capture the tenant context from the incoming request (JWT) and select the corresponding database schema:

```java
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver {
    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenant = TenantContext.getCurrentTenant();
        return tenant != null ? tenant : "public";
    }
    // ...
}
```

This model isolates tenant data while utilizing a shared database instance, reducing infrastructure costs.

### 11.2 Security Architecture
Spring Security intercepts all API traffic via a filter chain:
- **JWT Authentication Filter:** Validates the signatures of RS256-encrypted JSON Web Tokens.
- **Tenant Validation Filter:** Restricts database connection requests to the active tenant schema.
- **Rate-Limiting Filter:** Tracks IP and tenant rate limit buckets.

Method-level security is enforced using annotations:

```java
@PreAuthorize("hasPermission(#projectId, 'project:write')")
public void updateProject(Long projectId, ProjectDTO dto) { ... }
```

### 11.3 Caching Architecture
Caches are managed via Spring Cache and backed by Redis. To prevent stale cache reads, entities employ lifecycle listeners:

```java
@EntityListeners(CacheInvalidationListener.class)
public class Project { ... }
```

On entity updates, listeners emit events to invalidate cache keys:

```java
public class CacheInvalidationListener {
    @PostUpdate
    @PostRemove
    public void invalidate(Object entity) {
        // Emit cache invalidation event
    }
}
```

### 11.4 Asynchronous Task Queue & Scheduling
Long-running background tasks (e.g., repository syncs, notification dispatches) are scheduled via Spring Integration and backed by Redis:

```java
@Configuration
@EnableAsync
public class ThreadPoolConfiguration {
    @Bean(name = "syncExecutor")
    public Executor syncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(10000);
        executor.setThreadNamePrefix("SyncWorker-");
        executor.initialize();
        return executor;
    }
}
```

This prevents long-running operations from blocking the web server's connection threads.

### 11.5 AI Retrieval-Augmented Generation (RAG) & Vector Search
The platform uses the PostgreSQL `pgvector` extension, integrated via Spring AI `PgVectorStore`. Documents in the Knowledge Base and Repository modules are split into chunks, converted into vector embeddings, and indexed using an Hierarchical Navigable Small World (HNSW) index:

```sql
CREATE INDEX ON document_embeddings USING hnsw (embedding vector_cosine_ops);
```

During RAG workflows, Spring AI executes cosine similarity queries to retrieve the context documents:

```java
List<Document> similarDocuments = vectorStore.similaritySearch(
    SearchRequest.query("Code review guidelines").withSimilarityThreshold(0.7)
);
```

This ensures LLM prompt generations are grounded in the active repository context.

### 11.6 Resilience & Fault Tolerance
Resilience is implemented using **Resilience4j** to handle third-party dependency outages (e.g., Git API, LLM endpoints):
- **Circuit Breaker:** Opens the circuit if LLM API error rates exceed 50%.
- **Rate Limiter:** Limits API consumption to prevent token budget exhaustion.
- **Retry:** Retries failed third-party network requests with exponential backoff.

```java
@CircuitBreaker(name = "llmService", fallbackMethod = "fallbackLlmResponse")
public ChatResponse executeQuery(Prompt prompt) {
    return chatModel.call(prompt);
}
```

### 11.7 Secrets & Configuration Management
Configuration variables are loaded at startup. In local/development profiles, variables are read from Docker Compose files. In production, variables are loaded from AWS Secrets Manager or HashiCorp Vault. No raw secrets are stored within the version control repositories.

### 11.8 Observability & Distributed Tracing
Observability is built into the Spring Boot container via Micrometer and OpenTelemetry:
- **Metrics:** Core runtime and database metrics are exposed via Spring Boot Actuator endpoints.
- **Logs:** Logs are structured as JSON and shipped to Grafana Loki.
- **Traces:** A W3C trace parent header is propagated through the JVM lifecycle to trace asynchronous background executions.

---

## Appendix A: Module Dependency Map

```
┌────────────────────────────────────────────────────────────────────────────────────┐
│                         MODULE DEPENDENCY MAP                                       │
│                                                                                    │
│  Direction of dependency: A ──► B means "A depends on B's public interface"        │
│                                                                                    │
│                          ┌─────────────────┐                                       │
│                          │  Shared Kernel   │                                       │
│                          │  (Base types,    │                                       │
│                          │   utils, events) │                                       │
│                          └────────┬─────────┘                                       │
│                                   │                                                 │
│               ┌───────────────────┼───────────────────────────┐                     │
│               │                   │                           │                     │
│               ▼                   ▼                           ▼                     │
│   ┌──────────────────┐  ┌─────────────────┐  ┌──────────────────────┐              │
│   │  Auth & Identity │  │ Integration Hub │  │ File & Asset Mgmt    │              │
│   │  (0 module deps) │  │ (0 module deps) │  │ (0 module deps)      │              │
│   └────────┬─────────┘  └────────┬────────┘  └──────────┬───────────┘              │
│            │                     │                       │                          │
│            ▼                     ▼                       │                          │
│   ┌──────────────────────────────────────┐               │                          │
│   │         Project Management           │◄──────────────┘                          │
│   │  Depends on: Auth, Integration Hub   │                                          │
│   └────────┬──────────────┬──────────────┘                                          │
│            │              │                                                         │
│            ▼              ▼                                                         │
│  ┌─────────────────┐  ┌──────────────────────┐                                     │
│  │ Repository      │  │ Knowledge Base       │                                     │
│  │ Intelligence    │  │ Depends on: Auth, PM │                                     │
│  │ Depends on:     │  └──────────┬───────────┘                                     │
│  │ Auth, IntHub    │             │                                                  │
│  └────────┬────────┘             │                                                  │
│           │                      │                                                  │
│           ▼                      ▼                                                  │
│  ┌──────────────────────────────────────────────┐                                   │
│  │              AI Engine                        │                                   │
│  │  Depends on: Auth, Repo Intel, PM, KB         │                                   │
│  └────────┬──────────────────────────────────────┘                                   │
│           │                                                                          │
│           ▼                                                                          │
│  ┌─────────────────┐  ┌──────────────────┐  ┌──────────────────┐                    │
│  │ Developer       │  │ Workflow         │  │ Collaboration    │                    │
│  │ Analytics       │  │ Automation       │  │                  │                    │
│  │ Depends on:     │  │ Depends on:      │  │ Depends on:      │                    │
│  │ Auth, PM, Repo  │  │ Auth, PM, Repo,  │  │ Auth, PM         │                    │
│  └─────────────────┘  │ AI, Notif        │  └──────────────────┘                    │
│                       └──────────────────┘                                          │
│                                                                                    │
│  ┌─────────────────┐  ┌──────────────────┐                                         │
│  │ Notifications   │  │ Search           │                                         │
│  │ Depends on:     │  │ Depends on:      │                                         │
│  │ Auth, IntHub    │  │ All modules      │  ← Search indexes content from          │
│  └─────────────────┘  │ (read-only)      │    all modules via events               │
│                       └──────────────────┘                                         │
│                                                                                    │
└────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Appendix B: Non-Functional Requirements

| Requirement         | Target (MVP)          | Target (Enterprise)        |
|--------------------|-----------------------|----------------------------|
| **API Latency (p95)** | < 200ms            | < 100ms                    |
| **AI Response TTFB** | < 2s                | < 1s                       |
| **Availability**    | 99.5%                | 99.95%                     |
| **Recovery Time (RTO)** | < 4 hours       | < 15 minutes               |
| **Recovery Point (RPO)** | < 1 hour       | < 5 minutes                |
| **Concurrent Users** | 500                 | 50,000+                    |
| **Data Retention**  | 1 year               | Configurable (compliance)  |
| **Max File Upload** | 50MB                 | 500MB                      |
| **WebSocket Connections** | 1,000          | 100,000+                   |

---

> **Next Steps:** This architecture specification serves as the foundation. The following design documents will be prepared in subsequent phases:
>
> 1. **Database Schema Design** — Relational schema structures, multi-tenancy mappings, and `pgvector` indexes.
> 2. **API Design** — OpenEndpoints REST API specification, STOMP routing schema, and event contracts.
> 3. **Auth & Identity Design** — OAuth configurations, SSO flows, and tenant isolation policies.
> 4. **AI & RAG Internal Design** — Spring AI clients, embedding models, and vector ingest pipelines.
> 5. **CI/CD & Deployment Configurations** — Terraform scripts, Docker configurations, and target deployment designs.

---

*This document is the official architecture specification of DevFlow. Any changes require approval from the Architecture Review Board.*
