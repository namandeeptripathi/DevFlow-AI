# DevFlow — Docker Architecture Specification

> **Version:** 1.0.0
> **Status:** Final / Architecture Review Board (ARB) Approved
> **Author:** Principal Platform Architect
> **Date:** 2026-07-29
> **Classification:** Internal — Engineering & Infrastructure

---

## Table of Contents

1. [Purpose](#1-purpose)
2. [Containerization Philosophy](#2-containerization-philosophy)
3. [Docker Architecture](#3-docker-architecture)
4. [Container Inventory](#4-container-inventory)
5. [Image Strategy](#5-image-strategy)
6. [Runtime Architecture](#6-runtime-architecture)
7. [Networking Strategy](#7-networking-strategy)
8. [Persistent Storage](#8-persistent-storage)
9. [Configuration Injection](#9-configuration-injection)
10. [Resource Management](#10-resource-management)
11. [Local Development](#11-local-development)
12. [CI/CD Integration](#12-cicd-integration)
13. [Production Deployment](#13-production-deployment)
14. [Disaster Recovery](#14-disaster-recovery)
15. [Future Evolution](#15-future-evolution)
16. [Architectural Principles & Key Design Decisions](#16-architectural-principles--key-design-decisions)

---

## 1. Purpose

### 1.1 Scope

This document defines the containerization architecture for DevFlow — an AI-First Engineering Intelligence & Delivery Platform. It establishes the structural, operational, and security decisions that govern how DevFlow is packaged, distributed, and executed as a containerized system across all deployment environments.

This document is an architecture specification. It does not describe Docker CLI usage, Compose file syntax tutorials, or Kubernetes operator configuration. It establishes the *principles*, *rationale*, and *constraints* that govern every containerization decision made by the engineering, platform, and DevOps teams. Operational run-books and environment-specific configuration guides are maintained separately.

### 1.2 Why a Container Architecture Specification Exists

Container architecture decisions made early in a platform's lifecycle propagate deeply into operations, security, CI/CD pipelines, and infrastructure costs. Decisions that appear minor in local development — running a container as root, baking credentials into an image layer, conflating application state with container filesystem writes — become vulnerabilities, operational liabilities, and migration blockers at production scale.

DevFlow's container architecture specification exists to:

- Establish a shared, auditable decision record for all containerization choices.
- Prevent architectural drift between local, staging, and production container configurations.
- Enforce consistency between the containerization model and the previously approved Configuration Strategy, Logging Strategy, Authentication Strategy, and High-Level Architecture.
- Provide a vendor-neutral reference that remains valid as DevFlow evolves toward orchestration platforms such as Kubernetes, Amazon ECS, or HashiCorp Nomad.

### 1.3 Relationship to Existing Architecture Documents

| Reference Document | Relationship |
| :--- | :--- |
| High-Level Architecture Specification | Container topology follows the Modular Monolith deployment model. The single Spring Boot JAR maps to a single application container. |
| Configuration Strategy | Configuration injection into containers follows the approved precedence hierarchy. No configuration is baked into images. |
| Logging Strategy | Containers emit structured JSON logs to stdout/stderr. No log files exist inside containers. |
| Authentication Strategy | JWT signing keys and OAuth credentials are supplied to containers via external secret management — never embedded in images. |
| Authorization Model | RBAC policies are runtime-configured values, not compile-time artifacts baked into container images. |

---

## 2. Containerization Philosophy

### 2.1 Why Containerization

DevFlow adopts containerization not for operational fashion but for architectural necessity. The platform must operate consistently across the local workstations of engineers in different operating systems, staging environments with different cloud providers, and production infrastructure with specific OS and kernel requirements. Without containerization, this environment consistency requires extensive VM provisioning automation, manual dependency management, and environment-specific build procedures — all of which are sources of drift and defect.

Containerization provides three primary guarantees that align directly with DevFlow's architectural goals:

**Environment Consistency.** The same OCI-compliant image that a developer builds on a macOS workstation runs identically on a Linux CI runner, on a staging virtual machine, and on a production Kubernetes node. The operating system, JVM version, dependency library versions, and filesystem layout are sealed within the image layer graph and do not vary between environments.

**Deployment Isolation.** Each container instance has an isolated filesystem, network namespace, and process tree. PostgreSQL, Redis, and the Spring Boot application cannot interfere with each other's filesystems or port bindings. Isolation boundaries that would require complex operating system configuration in bare-metal deployments are provided automatically by the container runtime.

**Operational Portability.** OCI-compliant images are vendor-neutral artifacts. An image built for the Docker runtime runs without modification on containerd, Podman, or any Kubernetes-native runtime. DevFlow's containerization strategy explicitly targets OCI compliance to avoid lock-in to any single container runtime or orchestration platform.

### 2.2 Immutable Infrastructure

DevFlow's container architecture implements the immutable infrastructure principle at the image level. Once an image is built from a verified commit, that image is sealed. No post-build modification occurs: no files are changed inside running containers, no configuration is patched into image layers, no runtime script alters the container's executable content.

The consequences of this principle are deliberate:

- Every behavioral difference between environments is expressed entirely through externally injected configuration and secrets.
- The image promoted from the staging environment to production is bitwise identical to the image validated in the test pipeline.
- Rollback to a previous version is a registry tag promotion operation, not a code reversal, reducing rollback risk and duration.

Immutable infrastructure eliminates the "configuration drift" failure mode, where the running system gradually diverges from its documented state through accumulated ad-hoc changes.

### 2.3 One Process Per Container

Each DevFlow container runs exactly one primary operating system process. The application container runs the Spring Boot JVM process. The PostgreSQL container runs the Postgres server process. The Redis container runs the Redis server process. No container bundles multiple logical services.

This principle provides:

- **Operational clarity.** The health of a container directly reflects the health of its single process. Ambiguity about which process is responsible for a failure is eliminated.
- **Signal fidelity.** Operating system signals (SIGTERM for graceful shutdown, SIGKILL for forced termination) are delivered to a single, well-known process. Multi-process containers require a process supervisor (init daemon, tini) to correctly forward signals, introducing additional complexity.
- **Independent lifecycle management.** Scaling, restarting, or replacing any service does not affect unrelated services collocated in the same container.
- **Resource attribution.** CPU and memory limits assigned to a container directly constrain a single process, enabling accurate capacity planning.

### 2.4 Environment-Agnostic Images

A DevFlow container image carries no environment-specific values. The image produced by the CI/CD pipeline contains the application binary, its runtime dependencies, and the JVM — nothing else. It contains no connection strings, no credentials, no environment-specific feature flags, no infrastructure hostnames.

All environment-specific values are injected at container runtime through the mechanisms defined in the Configuration Strategy: environment variables, Secrets Manager references, and mounted configuration files. The container image is a generic, reusable artifact that becomes environment-specific only at the moment of instantiation.

This principle directly implements Twelve-Factor App Factor III (Config) and Factor VI (Processes) and ensures that the DevFlow artifact pipeline produces a single, promotable artifact rather than environment-specific build variants.

### 2.5 Reproducible Deployments

Every DevFlow deployment must be reproducible: given the same image tag and the same configuration inputs, any deployment of that image produces a system with identical behavior. Reproducibility depends on:

- **Pinned base images.** Base images are referenced by digest (SHA256), not by mutable tags. `latest` tags are prohibited in production image definitions.
- **Pinned dependency versions.** Maven dependency versions are locked in the project's `pom.xml`. No floating dependency ranges are permitted.
- **Immutable image layers.** Once pushed to the registry, an image tag is not overwritten. Mutable image tags (`latest`, `main`) are permitted only in local development and are never deployed to staging or production.
- **Explicit build provenance.** Every produced image is labeled with the source commit SHA, build timestamp, and pipeline identifier, enabling full traceability from a running container back to the exact source code revision.

---

## 3. Docker Architecture

### 3.1 Overall Container Topology

The DevFlow container topology separates concerns across distinct containers connected by an isolated internal network. External traffic reaches only the application container. All backing services are internal-only.

```
 +------------------------------------------------------------------------------+
 |                           EXTERNAL BOUNDARY                                  |
 |                                                                              |
 |   Clients: Web App (Next.js), CLI, VS Code Extension, API Consumers          |
 |                                                                              |
 +-----------------------------------+------------------------------------------+
                                     |  HTTPS / WSS (Port 443)
                                     v
 +------------------------------------------------------------------------------+
 |                        LOAD BALANCER / REVERSE PROXY                         |
 |            (Nginx, Caddy, Cloud Load Balancer -- external to Docker)          |
 +-----------------------------------+------------------------------------------+
                                     |  HTTP (Port 8080, internal)
                                     v
 +------------------------------------------------------------------------------+
 |                     devflow-network  (isolated bridge network)               |
 |                                                                              |
 |  +-----------------------------------------------------------------------+   |
 |  |                      devflow-app container                             |   |
 |  |        Spring Boot 3.x  Java 21  Modular Monolith                     |   |
 |  |        Port 8080 (HTTP API)   Port 8443 (HTTPS, if terminated here)   |   |
 |  |        Port 8090 (Actuator / Management -- internal only)              |   |
 |  +--------------------+------------------------------+--------------------+   |
 |                       |                              |                        |
 |               JDBC / HikariCP                Lettuce / Redis Protocol        |
 |               (TCP Port 5432)                (TCP Port 6379)                 |
 |                       |                              |                        |
 |                       v                              v                        |
 |  +--------------------------+  +------------------------------------------+  |
 |  |   devflow-postgres        |  |          devflow-redis                   |  |
 |  |   PostgreSQL 16           |  |          Redis 7                         |  |
 |  |   Port 5432 (internal)    |  |          Port 6379 (internal)            |  |
 |  |   pgvector extension      |  |          Persistence: AOF + RDB          |  |
 |  |   Multi-schema tenancy    |  |          No external exposure            |  |
 |  +--------------------------+  +------------------------------------------+  |
 |                                                                              |
 +------------------------------------------------------------------------------+

  NOTE: PostgreSQL and Redis ports are not published to the host.
        External clients cannot reach backing services directly.
```

### 3.2 Container Responsibilities

| Container | Primary Responsibility | Internal Port | External Port |
| :--- | :--- | :--- | :--- |
| `devflow-app` | Spring Boot application — REST API, WebSocket, AI Engine, background jobs, RBAC enforcement | 8080 (HTTP), 8090 (Actuator) | 8080 (proxied) |
| `devflow-postgres` | Relational data persistence — multi-tenant schemas, pgvector embeddings, ACID transactions | 5432 | None |
| `devflow-redis` | Caching, rate limiting, session store, real-time WebSocket state | 6379 | None |
| `devflow-maildev` (local only) | SMTP trap for development email inspection | 1025 (SMTP), 1080 (Web UI) | 1080 (dev only) |

### 3.3 Internal Communication

All communication between the application container and its backing services occurs exclusively over the `devflow-network` Docker bridge network, using container hostnames for service discovery. No IP addresses are hardcoded. No service communicates over the host loopback interface.

| Communication Path | Protocol | Authentication |
| :--- | :--- | :--- |
| `devflow-app` to `devflow-postgres` | TCP / JDBC (HikariCP) | Database username and password, injected via Secrets Manager |
| `devflow-app` to `devflow-redis` | TCP / Lettuce (Redis Protocol) | Redis AUTH password, injected via Secrets Manager |
| `devflow-app` to External AI Providers | HTTPS / Spring AI clients | API keys injected via Secrets Manager |
| `devflow-app` to External Git Providers | HTTPS / JGit, GitHub App | OAuth tokens, App private keys via Secrets Manager |

### 3.4 External Communication

External traffic — from web clients, CLI tools, VS Code extensions, and API consumers — reaches the DevFlow platform exclusively through a reverse proxy or load balancer that operates outside the Docker network boundary. Backing services (PostgreSQL, Redis) have no published ports and are unreachable from outside the Docker network boundary.

---

## 4. Container Inventory

### 4.1 DevFlow Application Container

**Container name:** `devflow-app`

The application container packages the compiled Spring Boot Modular Monolith into a runtime-optimized, minimal Linux environment. This is the only container that participates in external-facing communication. Its responsibilities span every domain module defined in the High-Level Architecture: Auth & Identity, Project Management, Repository Intelligence, AI Engine, Developer Analytics, Workflow Automation, Collaboration, Notifications, Integration Hub, Search, and File & Asset Management.

The application container is stateless by architectural constraint. No user data, session state, cached computation results, or file uploads reside on the container filesystem. All durable state is delegated to designated backing services: PostgreSQL for relational data, Redis for ephemeral shared state, and external object storage for binary assets. This statelessness enables horizontal scaling without session affinity and guarantees that container replacement is transparent to clients.

| Attribute | Value |
| :--- | :--- |
| Base runtime image | Eclipse Temurin 21 (JRE), Alpine Linux variant |
| Process | Java 21 JVM executing the Spring Boot fat JAR |
| Logging output | stdout (structured JSON) / stderr (error diagnostics) |
| Configuration | Injected at runtime via environment variables |
| Secrets | Mounted from external Secrets Manager at startup |
| Filesystem writes | None (read-only root filesystem in production) |
| Restart policy | Always (production); unless-stopped (staging/dev) |
| Health check | HTTP GET /actuator/health (liveness and readiness) |

### 4.2 PostgreSQL Container

**Container name:** `devflow-postgres`

The PostgreSQL container provides relational persistence for all DevFlow domain modules. It runs an unmodified, official PostgreSQL 16 image extended with the `pgvector` extension for AI vector embedding storage. The container is the canonical source of durable, ACID-transactional data for the platform.

PostgreSQL exposes its port only on the internal Docker network. No external tooling or client can connect to the database without being on the `devflow-network` or without SSH tunneling through a bastion host (production security posture). Direct external database access is prohibited.

| Attribute | Value |
| :--- | :--- |
| Image | postgres:16 with pgvector extension |
| Schemas | `auth`, `pm`, `repo`, `ai`, `analytics`, `kb`, `workflow`, `collab`, `notify`, `integration`, `search` |
| Port | 5432 (internal only) |
| Persistence | Named Docker volume — `devflow-postgres-data` |
| Credentials | Injected via Secrets Manager at container startup |
| Backup | Volume snapshots and pg_dump, coordinated externally |

### 4.3 Redis Container

**Container name:** `devflow-redis`

The Redis container provides the shared ephemeral state store used for API rate limiting, distributed session caching, HikariCP connection pool coordination, Spring Integration message queuing, and real-time WebSocket pub/sub coordination. Redis is not used as a primary data store; all data in Redis is either reproducible from PostgreSQL or carries a defined time-to-live.

| Attribute | Value |
| :--- | :--- |
| Image | redis:7-alpine |
| Port | 6379 (internal only) |
| Persistence | AOF (Append-Only File) enabled, RDB snapshots for recovery |
| Authentication | AUTH password injected via Secrets Manager |
| Memory policy | `allkeys-lru` (evict least-recently-used keys when at capacity) |
| Persistence volume | Named Docker volume — `devflow-redis-data` |

### 4.4 Optional Local Development Services

The following containers exist exclusively in local development environments. They are not present in staging or production, where equivalent managed services are used.

| Container | Purpose | Port (Local Only) |
| :--- | :--- | :--- |
| `devflow-maildev` | Local SMTP trap — captures outgoing emails for inspection | 1025 (SMTP), 1080 (Web UI) |
| `devflow-minio` | S3-compatible local object storage — replaces production cloud object storage | 9000 (API), 9001 (Console) |
| `devflow-pgadmin` | PostgreSQL administration UI for local schema inspection | 5050 |
| `devflow-redis-insight` | Redis inspection and monitoring UI for local development | 8001 |

### 4.5 Future Containers

The following containers are anticipated as the platform evolves. Their inclusion here provides architectural foresight without commitment.

| Future Container | Anticipated Purpose | Trigger Condition |
| :--- | :--- | :--- |
| `devflow-elasticsearch` | Full-text search and log aggregation | Growth in search usage requiring dedicated indexing |
| `devflow-ollama` | Local AI model serving for self-hosted inference | Enterprise customers requiring air-gapped AI deployment |
| `devflow-temporal` | Durable workflow orchestration for complex long-running automation | Workflow Automation module maturity requiring distributed task queuing |
| `devflow-keycloak` | Enterprise identity provider for SSO/SAML federation | Enterprise customer demand for identity federation |

---

## 5. Image Strategy

### 5.1 Multi-Stage Builds

DevFlow application images are produced using multi-stage Docker builds. Multi-stage builds are an architectural tool, not a build optimization. They enforce a strict separation between the build environment (which requires compilers, Maven, test runners, and build-time tooling) and the runtime environment (which requires only the JRE, the compiled artifact, and its runtime dependencies).

Without multi-stage builds, a production image would contain the Maven wrapper, the Maven local cache (~/.m2), the JDK compiler, source code, test resources, and build tooling — expanding the image attack surface by hundreds of megabytes and introducing executable tools that have no business purpose in a running container.

```
 Stage 1: Build Image
 +-------------------------------------------------------------+
 |  Base: eclipse-temurin:21-jdk-alpine                        |
 |                                                             |
 |  Inputs:                                                    |
 |  - pom.xml files (all Maven modules)                        |
 |  - src/ directories (all Maven modules)                     |
 |                                                             |
 |  Operations:                                                |
 |  - Maven dependency resolution (cached layer)              |
 |  - Maven compile (all modules)                              |
 |  - Maven test (unit tests only)                             |
 |  - Maven package (fat JAR assembly)                         |
 |                                                             |
 |  Output: /build/target/devflow-app.jar                      |
 +-----------------------------+-------------------------------+
                               |  COPY only the JAR
                               v
 Stage 2: Runtime Image
 +-------------------------------------------------------------+
 |  Base: eclipse-temurin:21-jre-alpine                        |
 |                                                             |
 |  Contents:                                                  |
 |  - JRE 21 runtime only (no compiler, no javac)             |
 |  - devflow-app.jar (copied from Stage 1)                    |
 |  - Non-root user (devflow:devflow, UID 1000)                |
 |  - Minimal Alpine filesystem                                |
 |                                                             |
 |  NOT Present:                                               |
 |  - Maven wrapper or Maven cache                             |
 |  - JDK compiler                                             |
 |  - Source code                                              |
 |  - Test resources                                           |
 |  - Build tooling (curl, wget, package managers)             |
 |                                                             |
 |  Entrypoint: java [JVM flags] -jar devflow-app.jar          |
 +-------------------------------------------------------------+
```

### 5.2 Base Images

Base image selection directly determines the attack surface of the runtime image. DevFlow standardizes on the following base images:

| Stage | Base Image | Rationale |
| :--- | :--- | :--- |
| Build | `eclipse-temurin:21-jdk-alpine` | Official Adoptium JDK 21, Alpine Linux reduces build image size, verified provenance |
| Runtime | `eclipse-temurin:21-jre-alpine` | JRE-only (no compiler), Alpine minimal OS, verified Adoptium provenance |
| PostgreSQL | `postgres:16-alpine` | Official PostgreSQL image, Alpine variant reduces footprint |
| Redis | `redis:7-alpine` | Official Redis image, Alpine variant |

**Base Image Pinning.** Every base image reference in production image definitions uses a SHA256 digest pin alongside the tag. Tags are mutable; a new push to `eclipse-temurin:21-jre-alpine` could introduce a breaking change or a compromised layer. The SHA256 digest is immutable and guarantees that the exact image layer graph used during security scanning is the same layer graph used in production.

**Why Eclipse Temurin.** Eclipse Temurin (Adoptium) is the leading vendor-neutral, community-maintained OpenJDK distribution. It is not controlled by any single cloud provider, receives regular security patches through the Eclipse Foundation governance model, and is certified as TCK-compliant. Oracle JDK is not used to avoid commercial licensing complications in multi-cloud deployments.

**Why Alpine Linux.** Alpine Linux is selected for its minimal installed package set, small filesystem footprint (approximately 5 MB base), and absence of package management tooling that could be exploited by a compromised container process. The tradeoff (musl libc instead of glibc) is explicitly validated for the Java 21 / Temurin combination before adoption.

### 5.3 Runtime Image Characteristics

The DevFlow runtime image is designed around the principle of minimum viable runtime. Every component present in the image is there because it is required for the JVM process to execute. No component is present for operational convenience, debugging, or historical inclusion.

| Component | Included | Rationale |
| :--- | :--- | :--- |
| JRE 21 | Yes | Required to execute the Spring Boot fat JAR |
| devflow-app.jar | Yes | The application artifact |
| curl / wget | No | Not required at runtime; creates network exfiltration risk |
| bash / sh | No (sh only via Alpine) | Alpine sh is retained for health check scripts only |
| Package manager (apk) | No | Removed post-build to prevent runtime package installation |
| SSH client | No | No SSH access into production containers |
| JDK compiler (javac) | No | No runtime compilation; eliminates JIT attack surface that requires compiler tools |
| Source code | No | Source is not present in the runtime image layer graph |

### 5.4 Image Versioning

| Tag Pattern | Environment | Mutability | Example |
| :--- | :--- | :--- | :--- |
| `{major}.{minor}.{patch}` | Production | Immutable | `1.4.2` |
| `{major}.{minor}.{patch}-rc.{n}` | Staging / Pre-release | Immutable | `1.4.2-rc.3` |
| `{branch}-{short-sha}` | Development / PR validation | Immutable | `feature-ai-engine-a3f9c12` |
| `latest` | Local development only | Mutable | `latest` |

Semantic versioning is applied to all production images. A version increment is required for every production deployment. The `latest` tag is explicitly prohibited in any staging or production deployment specification. Mutable tags in production environments violate the immutability principle and prevent reliable rollback.

### 5.5 Image Immutability

Once an image tag is pushed to the image registry, it is write-protected. The registry must be configured to prevent tag overwriting. This policy applies to all tags except `latest` in the local development namespace. In practice:

- A CI/CD pipeline that builds `devflow-app:1.4.2` writes that image once to the registry.
- Any subsequent build with the same semantic version fails at the push step if the tag already exists.
- Patching an existing release requires incrementing the patch version and producing a new, traceable image.

This policy creates a one-to-one correspondence between deployed image tags and auditable pipeline runs, enabling precise post-incident forensic analysis.

### 5.6 Image Security

Image security is a multi-layered concern addressed at the build, registry, and deployment stages.

| Security Control | Mechanism | Stage |
| :--- | :--- | :--- |
| Minimal base image | Eclipse Temurin JRE + Alpine | Build |
| Non-root execution | `USER devflow` (UID 1000) | Build |
| Read-only root filesystem | Enforced via container runtime flags | Deploy |
| Vulnerability scanning | Trivy or Grype integrated into CI/CD pipeline | Build / Registry |
| Secret-free image layers | Build-time secrets mounted via `--secret` flag, never committed to layers | Build |
| Signed images | Cosign (Sigstore) -- images signed with pipeline identity | Registry |
| Digest pinning | Base images referenced by SHA256 digest | Build |
| No privileged capabilities | No `--privileged` flag, no capability additions | Deploy |
| Supply chain attestation | SBOM (Software Bill of Materials) generated per image | Build |

---

## 6. Runtime Architecture

### 6.1 Startup Flow

Container startup follows a deterministic dependency ordering. The application container must not begin accepting traffic until its backing services are verified ready and its own application context is fully initialized.

```
 STARTUP SEQUENCE
 ================================================================

  [1] Infrastructure Layer Initialization
      |
      +-- devflow-postgres starts
      |     - PostgreSQL server process initializes
      |     - WAL files, shared memory, background workers initialize
      |     - TCP socket binds on port 5432 (internal network only)
      |
      +-- devflow-redis starts
            - Redis server process initializes
            - AOF log replays if previous data exists
            - TCP socket binds on port 6379 (internal network only)

  [2] Backing Service Readiness Verification
      |
      +-- Health probe: PostgreSQL accepts connections on port 5432
      |     pg_isready exits 0
      |     Retry with exponential backoff (max 60s)
      |
      +-- Health probe: Redis responds to PING command
            redis-cli PING returns PONG
            Retry with exponential backoff (max 30s)

  [3] Application Container Startup
      |
      +-- JVM process launches with configured heap and thread settings
      +-- Spring Boot ApplicationContext initializes
      +-- Configuration validation (fail-fast):
      |     - Database URL, credentials reachable
      |     - Redis URL, credentials reachable
      |     - Required environment variables present
      |     - JWT signing key accessible
      +-- Flyway database migration executes
      +-- Spring Security filter chain initializes
      +-- Module startup events fire in order:
      |     Auth -> PM -> Repo -> AI -> Analytics -> (remaining modules)
      +-- Actuator endpoints activate

  [4] Health Check Verification
      |
      +-- Startup probe: /actuator/health/liveness
      |     Returns HTTP 200 when JVM is alive
      |
      +-- Readiness probe: /actuator/health/readiness
      |     Returns HTTP 200 when all health indicators pass:
      |         db (PostgreSQL connectivity)
      |         redis (Redis connectivity)
      |         diskSpace
      |         ping
      |
      +-- All probes pass -> Container marked READY

  [5] Traffic Acceptance
      |
      +-- Load balancer begins routing traffic to this container instance
```

### 6.2 Dependency Readiness

The application container does not attempt to start its Spring Boot ApplicationContext until backing services pass their readiness checks. This is implemented at the container orchestration level via health check dependencies, not within application code itself.

The fail-fast startup validation defined in the Configuration Strategy applies here: Spring Boot will fail to start if required configuration properties (database URL, Redis URL, JWT signing key path) are missing or unreachable. This failure surfaces immediately in container logs and causes the container to exit with a non-zero status code, triggering the restart policy and alerting the operations team.

The architectural consequence is that a container that cannot reach its dependencies will never silently serve requests in a degraded state. It will fail visibly and loudly.

### 6.3 Health Checks

DevFlow implements three distinct health check categories, each serving a different operational purpose:

```
 HEALTH CHECK ARCHITECTURE
 -----------------------------------------------------------------

  STARTUP PROBE
  Purpose : Determine whether the JVM has started successfully.
  Endpoint: /actuator/health/liveness
  Period  : Every 10 seconds
  Threshold: Fail after 30 consecutive failures (5 minutes tolerance)
  Rationale: Spring Boot on Java 21 may take 15-45 seconds to
             initialize all application modules. The startup probe
             prevents premature container kills during normal
             initialization and is distinct from the liveness probe
             to avoid masking true runtime failures.

  LIVENESS PROBE
  Purpose : Determine whether the running process is alive and not
            deadlocked. A failing liveness probe causes the container
            runtime to kill and restart the container.
  Endpoint: /actuator/health/liveness (HTTP GET)
  Period  : Every 30 seconds
  Failure Threshold: 3 consecutive failures -> container restart
  Rationale: A JVM that is alive but stuck in a deadlock or infinite
             loop should be restarted. The liveness probe measures
             the process's ability to respond at all -- not whether
             all dependencies are reachable.

  READINESS PROBE
  Purpose : Determine whether the container is ready to serve traffic.
            A failing readiness probe removes the container from the
            load balancer rotation without restarting it.
  Endpoint: /actuator/health/readiness (HTTP GET)
  Indicators evaluated:
    - db        : PostgreSQL connection pool has at least one active connection
    - redis     : Redis Lettuce client responds to PING
    - diskSpace : Available disk space above threshold
    - ping      : Application is responding
  Period  : Every 15 seconds
  Rationale: Readiness is distinct from liveness. A container may be
             alive (JVM running, process responding) but not ready
             (database connection pool exhausted, Redis unreachable).
             Routing traffic to a not-ready container causes request
             failures that should instead be routed to healthy instances.
```

| Probe Type | Endpoint | Period | Failure Action | Architectural Purpose |
| :--- | :--- | :--- | :--- | :--- |
| Startup | `/actuator/health/liveness` | 10s | Wait | Tolerate slow JVM initialization |
| Liveness | `/actuator/health/liveness` | 30s | Container restart | Recover from deadlocks and process hangs |
| Readiness | `/actuator/health/readiness` | 15s | Remove from LB rotation | Protect clients from degraded instances |

The Actuator management port (8090) is separate from the application port (8080). This separation ensures that health check endpoints remain accessible even when the application port is saturated with traffic, and prevents health check traffic from being subject to rate limiting or authentication requirements that protect the application API.

### 6.4 Graceful Shutdown

Graceful shutdown is the mechanism by which a container terminates without dropping in-flight requests or leaving database transactions in an undefined state. DevFlow's graceful shutdown behavior is defined architecturally as follows:

```
 GRACEFUL SHUTDOWN SEQUENCE
 -----------------------------------------------------------------

  [Signal] SIGTERM received by JVM process
      |
      v
  [1] Readiness probe returns HTTP 503 (Not Ready)
      -> Load balancer stops routing new requests to this instance
      -> Existing in-flight requests continue processing

  [2] Drain period (configurable, default: 30 seconds)
      -> HTTP server stops accepting new connections
      -> In-flight HTTP requests complete (up to drain period)
      -> WebSocket connections receive close frames
      -> Active clients gracefully reconnect to other instances

  [3] Spring Boot ApplicationContext shutdown hooks fire
      -> Spring Integration message consumers stop accepting new messages
      -> In-progress background jobs complete or checkpoint their state
      -> Scheduled task executor shuts down gracefully

  [4] HikariCP connection pool shutdown
      -> Active database connections complete their current transactions
      -> No new connections are acquired
      -> All connections are returned to the pool and closed

  [5] Lettuce (Redis) client shutdown
      -> Pending Redis commands complete
      -> All Redis connections are closed

  [6] JVM process exits with status 0

  [7] Container runtime reports container stopped
```

If the drain period expires before all in-flight requests complete, the container runtime sends SIGKILL, which immediately terminates the process. The shutdown timeout must be set above the 95th-percentile request processing time for AI Engine operations, which are the longest-running synchronous operations in DevFlow (target: p95 < 4 seconds). A 30-second shutdown timeout provides a conservative safety margin.

---

## 7. Networking Strategy

### 7.1 Internal Network

All DevFlow containers share a single isolated Docker bridge network named `devflow-network`. This network is created explicitly, not implicitly, to allow precise configuration of network properties and to prevent accidental connectivity with containers belonging to other projects.

```
 devflow-network (Docker Bridge Network)
 ---------------------------------------------------------------
 Subnet  : 172.28.0.0/16 (example -- configured per environment)
 Driver  : bridge
 Internal: false (containers may initiate outbound connections)
 Labels  : project=devflow, managed-by=compose|terraform

 Container Assignments:
 +----------------------+---------------------+----------------+
 | Container            | Hostname            | DNS Resolution |
 +----------------------+---------------------+----------------+
 | devflow-app          | devflow-app         | Internal DNS   |
 | devflow-postgres     | devflow-postgres     | Internal DNS   |
 | devflow-redis        | devflow-redis        | Internal DNS   |
 +----------------------+---------------------+----------------+
```

### 7.2 Service Discovery

Within the `devflow-network`, containers resolve each other by hostname using Docker's embedded DNS server. The application container references its backing services as `devflow-postgres:5432` and `devflow-redis:6379`. These hostnames are supplied as injected configuration values, not hardcoded into the application binary.

The Spring Boot configuration receives connection strings via environment variables. The values of `DB_HOST`, `REDIS_HOST`, and related variables are injected at container runtime from external configuration sources. In local development, they resolve to Docker container hostnames. In production Kubernetes deployments, they resolve to Kubernetes Service DNS names. The application binary is unchanged across environments.

### 7.3 Port Exposure

Port publication (binding a container port to a host port) is a deliberate security decision. Only the application container's API port is published to the host. All backing service ports remain exclusively within the Docker network.

| Container | Container Port | Published to Host | Justification |
| :--- | :--- | :--- | :--- |
| `devflow-app` | 8080 (HTTP) | Yes (proxied via reverse proxy) | External API traffic must reach the application |
| `devflow-app` | 8090 (Actuator) | No (internal only) | Health checks originate from within the network |
| `devflow-postgres` | 5432 | No | Database access is restricted to the application |
| `devflow-redis` | 6379 | No | Cache access is restricted to the application |

In local development, database ports may be published to the host (e.g., `localhost:5432`) to enable developer tools such as database clients and schema migration tools. This configuration must never be replicated in staging or production environments.

### 7.4 External Traffic

External traffic reaches the DevFlow platform through a reverse proxy or load balancer operating outside the Docker network boundary. This component (Nginx, Caddy, AWS ALB, Google Cloud Load Balancing) handles TLS termination, request routing, and rate limiting before forwarding requests to the application container's published HTTP port.

The application container is not responsible for TLS termination in the standard deployment topology. TLS termination at the load balancer layer enables certificate management centralized outside application code and image builds, zero-downtime certificate rotation without application restarts, and consistent TLS policy enforcement across all instances. In environments where end-to-end encryption is required (mutual TLS), the application can be configured to accept HTTPS on port 8443, with the certificate injected at runtime via Secrets Manager.

---

## 8. Persistent Storage

### 8.1 Storage Architecture

The fundamental storage principle for DevFlow containers is that application containers are stateless and ephemeral. No permanent data resides on a container filesystem. Data that must survive container restarts, replacements, or host migrations lives in exactly one of three places: a named Docker volume (for local/single-host deployments), managed cloud storage (for production), or external object storage.

```
 STORAGE ARCHITECTURE
 -----------------------------------------------------------------

  devflow-app
  +----------------------------------+
  |  Ephemeral Container Filesystem  |   <- tmpfs or overlay2
  |  - JVM class files (read-only)   |   <- Destroyed on container stop
  |  - Spring Boot JAR (read-only)   |
  |  - /tmp (JVM temporary files)    |   <- tmpfs mount (memory-backed)
  +----------------+-----------------+
                   |   Writes delegated to:
                   v
  +----------------------------------------------------------------+
  |                      Persistent Storage Layer                  |
  |                                                                |
  |  devflow-postgres-data (Named Volume)                         |
  |  ---------------------------------------------------------    |
  |  Mount: /var/lib/postgresql/data                              |
  |  Contents: WAL files, data files, indexes, pgvector indexes   |
  |  Lifecycle: Survives container restarts and replacements       |
  |                                                                |
  |  devflow-redis-data (Named Volume)                            |
  |  ---------------------------------------------------------    |
  |  Mount: /data                                                  |
  |  Contents: AOF log, RDB snapshot                              |
  |  Lifecycle: Survives container restarts                        |
  |                                                                |
  |  Object Storage (External -- S3-compatible)                   |
  |  ---------------------------------------------------------    |
  |  Contents: File uploads, repository snapshots, backups        |
  |  Lifecycle: Managed by cloud provider (versioning, lifecycle) |
  +----------------------------------------------------------------+
```

### 8.2 Database Volumes

The PostgreSQL data directory is backed by a named Docker volume (`devflow-postgres-data`). Named volumes are managed by Docker independently of the container lifecycle: the volume persists when the PostgreSQL container is stopped, replaced, or upgraded. Volume data is bound to the host filesystem in single-host deployments and to a managed persistent disk in cloud deployments.

| Volume | Mount Point | Driver | Backup Strategy |
| :--- | :--- | :--- | :--- |
| `devflow-postgres-data` | `/var/lib/postgresql/data` | local (dev), cloud persistent disk (prod) | `pg_dump` + WAL archival to object storage |

Database volume management policies:

- Volumes are never deleted as part of a routine container restart or image update. Only explicit volume removal commands — which require deliberate administrative action — can delete volume data.
- In production, database volumes are backed by cloud-managed block storage (AWS EBS, GCP Persistent Disk, Azure Managed Disk) with provider-level snapshot scheduling.
- Volume encryption at rest is enforced by the block storage provider in production.

### 8.3 Redis Persistence

Redis is configured with both Append-Only File (AOF) and RDB snapshot persistence to balance recovery granularity with startup performance:

- **AOF (Append-Only File):** Every write command is appended to a durable log. On Redis restart, the AOF log is replayed to reconstruct the dataset. AOF provides the finest recovery granularity and is the primary persistence mechanism.
- **RDB Snapshots:** Periodic point-in-time snapshots reduce AOF replay time after a restart. RDB snapshots represent a compromise: they may lose recent writes, but they allow faster restarts when AOF replay would be time-consuming.

Given that Redis in DevFlow stores reproducible cache data (query results, session tokens with known TTLs, rate limit counters), the acceptable data loss window for Redis is higher than for PostgreSQL. An AOF-first policy with RDB recovery option is architecturally appropriate.

### 8.4 Temporary Storage

The application container's `/tmp` directory is backed by a tmpfs mount (memory-backed, not disk-backed). This configuration provides:

- **Performance:** JVM temporary file operations run at memory speed.
- **Security:** Temporary files do not persist to the host disk and are destroyed when the container stops.
- **Isolation:** Temporary file data does not accumulate across container restarts, preventing disk exhaustion.

The tmpfs mount size is bounded to prevent a malicious or buggy operation from exhausting host memory through temporary file accumulation.

### 8.5 Backup Strategy

| Data Store | Backup Method | Frequency | Retention | Restoration Mechanism |
| :--- | :--- | :--- | :--- | :--- |
| PostgreSQL | `pg_dump` logical backup + WAL archival | Logical: daily, WAL: continuous | 30 days (standard), 7 years (compliance) | Point-in-time recovery via WAL replay |
| PostgreSQL | Volume snapshot (block-level) | Every 6 hours | 7 days | Volume clone and container restart |
| Redis | AOF + RDB backup to object storage | RDB: every 15 minutes | 48 hours | Redis restart with AOF replay |
| Object Storage | Provider-managed versioning | Continuous (versioning) | Per lifecycle policy | Version restore via provider API |

Backup execution is orchestrated outside the Docker container layer. Database backup jobs run as separate, short-lived containers that share network access to the PostgreSQL container, preventing backup I/O from competing with live database operations.

---

## 9. Configuration Injection

### 9.1 Configuration Architecture Within Containers

Configuration injection into containers directly implements the externalized configuration principle established in the DevFlow Configuration Strategy. No environment-specific values are baked into images. The image is a static, environment-agnostic artifact; the runtime environment is the configuration context.

```
 CONFIGURATION INJECTION FLOW
 -----------------------------------------------------------------

  External Sources
  +---------------------+  +---------------------+  +------------------+
  |  Secrets Manager    |  |  Platform Config     |  |  Container       |
  |  (Vault / AWS SM /  |  |  (ConfigMap /        |  |  Orchestration   |
  |   Azure KV)         |  |   env file)          |  |  Environment     |
  +----------+----------+  +----------+-----------+  +--------+---------+
             |                        |                        |
             v                        v                        v
  +----------------------------------------------------------------------+
  |                        Container Environment                          |
  |                                                                      |
  |  ENV: DB_HOST=devflow-postgres       (platform config)               |
  |  ENV: DB_PORT=5432                   (platform config)               |
  |  ENV: DB_NAME=devflow                (platform config)               |
  |  ENV: SECRETS_MANAGER_PATH=/devflow/ (platform config)              |
  |                                                                      |
  |  MOUNTED SECRETS:                                                    |
  |  /run/secrets/db_password            (secrets manager)               |
  |  /run/secrets/jwt_signing_key        (secrets manager)               |
  |  /run/secrets/redis_password         (secrets manager)               |
  +------------------------------+---------------------------------------+
                                 |
                                 v
  +----------------------------------------------------------------------+
  |                    Spring Boot Configuration Layer                    |
  |                                                                      |
  |  Priority Order (high to low):                                       |
  |  1. Secrets Manager mounted values (/run/secrets/*)                 |
  |  2. Container environment variables                                  |
  |  3. Spring profile configuration (application-{profile}.yml)        |
  |  4. Base application configuration (application.yml)                |
  |  5. Spring Boot defaults                                             |
  +----------------------------------------------------------------------+
```

### 9.2 Environment Variables

Environment variables injected into DevFlow containers carry only non-sensitive operational configuration: connection hostnames, port numbers, database names, Spring profile activation, feature flags, and infrastructure endpoint references.

| Variable Pattern | Category | Example Value |
| :--- | :--- | :--- |
| `SPRING_PROFILES_ACTIVE` | Runtime behavior | `production` |
| `DB_HOST` | Infrastructure endpoint | `devflow-postgres` |
| `DB_PORT` | Infrastructure endpoint | `5432` |
| `DB_NAME` | Infrastructure endpoint | `devflow` |
| `REDIS_HOST` | Infrastructure endpoint | `devflow-redis` |
| `REDIS_PORT` | Infrastructure endpoint | `6379` |
| `JAVA_OPTS` | JVM tuning | `-Xms512m -Xmx2g -XX:+UseContainerSupport` |
| `DEVFLOW_AI_PROVIDER` | Feature configuration | `openai` |
| `SECRETS_MANAGER_PATH` | Secret resolution | `/devflow/production/` |

### 9.3 Secrets

Secrets — database passwords, JWT signing keys, API keys for external providers, OAuth client secrets — are never injected as raw environment variable values. Environment variable values appear in process listings, container inspection outputs, CI/CD logs, and crash dumps. Any of these exposure vectors can result in credential compromise.

Secrets are injected through one of two mechanisms:

- **Mounted secret files:** The Secrets Manager injects secret values as files mounted into `/run/secrets/` within the container. The application reads secret values from the filesystem, not from environment variable values. Filesystem-mounted secrets are readable only by the container's primary process (UID 1000).

- **Secrets Manager SDK reference resolution:** The application resolves a Secrets Manager path at startup to fetch the actual credential value, avoiding placement of the secret value anywhere in the container environment.

| Secret Type | Injection Mechanism | Rotation Mechanism |
| :--- | :--- | :--- |
| Database password | Mounted secret file (`/run/secrets/db_password`) | Secrets Manager rotation, container restart |
| Redis AUTH password | Mounted secret file (`/run/secrets/redis_password`) | Secrets Manager rotation, container restart |
| JWT RSA signing key | Mounted secret file (`/run/secrets/jwt_private_key.pem`) | Key rotation policy, rolling restart |
| AI provider API keys | Mounted secret file (`/run/secrets/openai_api_key`) | Secrets Manager rotation |
| OAuth client secrets | Secrets Manager SDK resolution at startup | Provider-managed rotation |

### 9.4 Configuration Files

Non-sensitive configuration that is environment-specific but not secret may be supplied as mounted configuration files. In Kubernetes deployments, these files are supplied via ConfigMap volume mounts. In Docker Compose local deployments, they are supplied via bind mounts. In both cases, the files are read-only within the container, and the application parses them at startup through the standard Spring Boot configuration source hierarchy.

### 9.5 Runtime Configuration Constraints

The following constraints apply unconditionally to all containers in all environments:

- Credentials are never present in the `Dockerfile` or image layers.
- Credentials are never present in Docker Compose files or environment variable definition files committed to version control.
- The `docker inspect` output of a running container must never reveal a raw credential value.
- Build-time secrets, if any build step requires a credential, are injected using Docker BuildKit's `--secret` mechanism, which mounts the secret during the specific build step and never commits it to an image layer.

---

## 10. Resource Management

### 10.1 CPU Allocation

CPU resource management for containers follows a two-parameter model: CPU shares (relative weight) for normal operations, and CPU limits for hard ceiling enforcement.

| Container | CPU Request (Relative Share) | CPU Limit | Rationale |
| :--- | :--- | :--- | :--- |
| `devflow-app` | 1.0 vCPU | 4.0 vCPU | Spring Boot handles burst request load; AI Engine operations are CPU-intensive |
| `devflow-postgres` | 0.5 vCPU | 2.0 vCPU | PostgreSQL is I/O-bound for most DevFlow workloads; CPU limit prevents runaway queries |
| `devflow-redis` | 0.25 vCPU | 0.5 vCPU | Redis is single-threaded; high CPU usage indicates a workload anti-pattern |

CPU requests represent the minimum guaranteed CPU share allocated to a container under resource contention. CPU limits represent the maximum CPU time a container may consume. Setting CPU limits prevents a CPU-intensive container from monopolizing host CPU at the expense of other containers — particularly critical for the application container running AI inference operations.

### 10.2 Memory Allocation

Memory limits for containers are a security and stability control. A container without a memory limit can allocate unbounded host memory, causing out-of-memory (OOM) conditions on the host that affect all running containers.

| Container | Memory Request | Memory Limit | Rationale |
| :--- | :--- | :--- | :--- |
| `devflow-app` | 768 MiB | 2 GiB | JVM heap + metaspace + off-heap (Netty, thread stacks, NIO buffers) |
| `devflow-postgres` | 256 MiB | 1 GiB | `shared_buffers` (25% of limit), connection process overhead |
| `devflow-redis` | 64 MiB | 256 MiB | In-memory dataset with `maxmemory` set below container limit |

### 10.3 JVM Memory Awareness

Java Virtual Machine memory management within containers requires explicit attention. Pre-Java 11 JVM instances were container-unaware: they read physical host memory to determine heap defaults, resulting in JVMs that sized their heaps assuming 128 GB when the container limit was 2 GB — causing immediate OOM kills.

Java 21 (Eclipse Temurin) is fully container-aware by default. When `UseContainerSupport` is active (the default), the JVM reads cgroup memory limits rather than host memory to size heap defaults.

| JVM Flag | Value | Architectural Purpose |
| :--- | :--- | :--- |
| `-XX:+UseContainerSupport` | Default (enabled) | JVM reads container memory limits for heap sizing |
| `-XX:MaxRAMPercentage` | `75.0` | JVM heap uses at most 75% of container memory limit, leaving room for off-heap |
| `-XX:InitialRAMPercentage` | `50.0` | JVM initializes heap at 50% of container limit to avoid premature GC pressure |
| `-XX:+UseZGC` | Enabled | ZGC provides low-latency garbage collection appropriate for API response time targets |
| `-XX:+ZGenerational` | Enabled (Java 21+) | Generational ZGC improves throughput for mixed short-lived / long-lived object workloads |
| `-Djava.security.egd=file:/dev/./urandom` | Set | Prevents JVM from blocking on `/dev/random` in containerized environments |

**Virtual Threads.** Java 21's Virtual Threads (Project Loom) significantly change the memory profile of the Spring Boot application. Traditional platform thread stacks consume 256 KB to 2 MB of off-heap memory per thread. Virtual threads consume significantly less memory (a few KB of heap per virtual thread), enabling DevFlow to handle thousands of concurrent HTTP connections without proportional memory growth. The application container's memory budget is dominated by heap data (entity objects, connection pools, cache structures) rather than thread stack allocations.

### 10.4 OOM Prevention

Out-of-memory conditions that cause container kills are disruptive and can cause cascading failures if multiple instances are killed simultaneously. The following architectural controls prevent OOM events:

- **JVM heap ceiling:** `MaxRAMPercentage=75` ensures the JVM heap cannot consume all container memory, leaving headroom for off-heap allocations.
- **Redis `maxmemory`:** Redis is configured with a `maxmemory` value set to 80% of the container memory limit. When the Redis dataset reaches this threshold, the `allkeys-lru` eviction policy removes the least recently used keys.
- **HikariCP pool ceiling:** The maximum JDBC connection pool size is configured based on the PostgreSQL instance's `max_connections` parameter. Unbounded pool sizes cause connection exhaustion at the database level.
- **Memory limit over request:** Container memory limits are set at a ratio to requests (typically 2:1 to 4:1) to allow burst absorption while maintaining a hard ceiling.

### 10.5 Scaling Strategy

| Scaling Dimension | Approach | Trigger |
| :--- | :--- | :--- |
| Application horizontal scale-out | Add additional `devflow-app` container instances behind the load balancer | CPU utilization > 70%, request queue depth, p95 latency degradation |
| Application vertical scale-up | Increase container memory and CPU limits | Sustained GC pressure, memory pressure without OOM events |
| PostgreSQL vertical scale-up | Increase container resource limits or promote to a managed RDS instance | Sustained high CPU from complex queries, connection pool exhaustion |
| Redis vertical scale-up | Increase memory limit and Redis `maxmemory` | Dataset growth, eviction rate increase |

The Spring Boot Modular Monolith is stateless: any number of `devflow-app` instances can run concurrently behind the load balancer without coordination. Session state (JWT tokens are stateless; WebSocket state is stored in Redis) does not create affinity requirements. This enables elastic horizontal scaling without application changes.

---

## 11. Local Development

### 11.1 Docker Compose Philosophy

Docker Compose serves a specific and narrowly defined purpose in the DevFlow development workflow: it provides a consistent, reproducible local infrastructure environment for developers to run backing services (PostgreSQL, Redis, MinIO, MailDev) without requiring local installation of those services.

Docker Compose is not a production deployment tool. It is not a staging environment tool. It is a developer experience tool. The distinction has architectural consequences: the Docker Compose configuration for local development may enable conveniences (exposed database ports, relaxed security constraints, debug tooling containers) that are strictly prohibited in staging and production deployments.

Docker Compose local environments reflect the production container topology in structure while differing in security posture and external accessibility. The same container names, network names, and environment variable names are used to minimize cognitive friction when transitioning from local development to production debugging.

### 11.2 Developer Workflow

The local development workflow is designed around a single invariant: the developer runs the same application code against real backing services (PostgreSQL, Redis) in a containerized environment, not against in-memory mocks. In-memory H2 database usage is explicitly prohibited in DevFlow development for the following reasons:

- H2 does not support PostgreSQL-specific features used by DevFlow: `pgvector`, PostgreSQL-specific JSON operators, advisory locks, and PostgreSQL schema-per-tenant isolation.
- Tests passing against H2 do not validate the actual database interaction layer that runs in production.
- Schema migrations (Flyway) executed against H2 may not detect PostgreSQL-specific migration errors.

Developers interact with a local Docker Compose environment to start, stop, and reset the infrastructure tier. The Spring Boot application process runs directly on the developer's JVM — outside Docker — to allow IDE integration, hot reload, and debugger attachment. Only the backing services (PostgreSQL, Redis, and optional local services) run as containers.

### 11.3 Hot Reload and Developer Experience

Spring Boot DevTools enables class reloading on detected classpath changes without a full JVM restart. When the developer's IDE compiles modified classes, DevTools detects the change and performs a rapid context restart (typically 1-3 seconds for incremental changes) rather than a full application restart (15-45 seconds for cold start).

This capability requires that the Spring Boot application process runs outside the container (on the host JVM), where the IDE's incremental compilation output is directly accessible. Containers do not participate in the hot reload loop; they provide stable backing services that persist across application restarts.

### 11.4 Local AI Development

AI Engine development in the local environment supports two configurations:

**Remote AI Provider Mode.** The application is configured with API keys for external AI providers (OpenAI, Anthropic, Google Gemini) injected via the local `.env` file (never committed to version control). All inference requests travel to the external provider. This configuration requires internet connectivity and incurs API usage costs.

**Local Model Mode.** An optional Ollama container provides local model serving. Developers can run open-weight models (LLaMA 3, Mistral, Qwen) entirely on their local machine without external API dependencies. The Spring AI client is configured to target the local Ollama endpoint. The local development configuration explicitly does not replicate production AI infrastructure; AI integration tests use recorded response fixtures for deterministic testing.

---

## 12. CI/CD Integration

### 12.1 Build Pipeline Architecture

The CI/CD pipeline is the sole authorized producer of container images eligible for deployment. No developer workstation produces deployable images. Every image that reaches the registry originates from a verifiable, auditable pipeline run.

```
 CI/CD IMAGE BUILD PIPELINE
 ================================================================

  Source Commit (Git Push / PR Merge)
        |
        v
  +-------------------------------------------------------------+
  |  Stage 1: Code Quality & Security Analysis                   |
  |                                                             |
  |  - Static code analysis (SpotBugs, PMD, Checkstyle)        |
  |  - Dependency vulnerability scan (OWASP Dependency-Check)   |
  |  - License compliance check                                 |
  |  - Secret detection scan (no credentials in source)         |
  +-----------------------------+-------------------------------+
                                | (pass)
                                v
  +-------------------------------------------------------------+
  |  Stage 2: Build & Unit Test                                  |
  |                                                             |
  |  - Maven multi-module build (all modules)                   |
  |  - Unit test execution (Spring Boot Test, JUnit 5)          |
  |  - Test coverage validation (minimum threshold enforced)    |
  |  - Maven SBOM generation (CycloneDX)                        |
  +-----------------------------+-------------------------------+
                                | (pass)
                                v
  +-------------------------------------------------------------+
  |  Stage 3: Integration Test                                   |
  |                                                             |
  |  - Testcontainers: real PostgreSQL + Redis in CI            |
  |  - Integration tests against actual database and cache      |
  |  - Flyway migration validation                              |
  |  - Spring Security filter chain integration tests           |
  +-----------------------------+-------------------------------+
                                | (pass)
                                v
  +-------------------------------------------------------------+
  |  Stage 4: Container Image Build (Multi-Stage)                |
  |                                                             |
  |  - Docker BuildKit multi-stage build                        |
  |  - Build stage: compile + package fat JAR                  |
  |  - Runtime stage: JRE + JAR only                           |
  |  - Image labeled with: git_sha, build_id, timestamp, ver   |
  |  - SBOM attached to image manifest                         |
  +-----------------------------+-------------------------------+
                                |
                                v
  +-------------------------------------------------------------+
  |  Stage 5: Image Security Scanning                            |
  |                                                             |
  |  - Trivy / Grype: OS package vulnerability scan             |
  |  - Trivy: Java JAR dependency vulnerability scan            |
  |  - Critical/High CVE threshold: fail build on discovery    |
  |  - Scan results persisted to artifact registry              |
  +-----------------------------+-------------------------------+
                                | (pass -- no critical/high CVEs)
                                v
  +-------------------------------------------------------------+
  |  Stage 6: Image Signing & Registry Push                      |
  |                                                             |
  |  - Image signed with Cosign (Sigstore keyless signing)      |
  |  - Signature attached to image manifest in registry         |
  |  - Image pushed to registry with immutable tag              |
  |  - SBOM attestation pushed to registry                     |
  +-----------------------------+-------------------------------+
                                |
                                v
  +-------------------------------------------------------------+
  |  Stage 7: Deployment (Staging)                               |
  |                                                             |
  |  - Deployment manifest updated with new image tag           |
  |  - Rolling update initiated on staging environment          |
  |  - Health check validation post-deployment                  |
  |  - Smoke tests against staging environment                  |
  +-----------------------------+-------------------------------+
                                | (pass)
                                v
  +-------------------------------------------------------------+
  |  Stage 8: Production Promotion (Manual Approval Gate)        |
  |                                                             |
  |  - Human approval required (release manager or on-call)     |
  |  - Same immutable image promoted from staging to production |
  |  - No new build -- the staging-validated image is deployed  |
  +-------------------------------------------------------------+
```

### 12.2 Image Registry

The container image registry serves as the canonical, immutable artifact store for all DevFlow container images.

| Requirement | Rationale |
| :--- | :--- |
| Image immutability enforcement | Tags once pushed cannot be overwritten (write-once policy) |
| Vulnerability scanning integration | Native or integrable with Trivy / Grype |
| Image signing support | Cosign / Notary v2 signature attachment and verification |
| Access control | RBAC on namespace / repository level |
| Audit logging | Every push, pull, and deletion is logged with identity |
| Geo-replication (production) | Images replicated across regions to reduce pull latency |

Compatible registries include GitHub Container Registry (GHCR), AWS Elastic Container Registry (ECR), Google Artifact Registry, Azure Container Registry (ACR), and self-hosted Harbor. The architecture is registry-agnostic; the pipeline interacts with the registry via standard OCI registry API calls.

### 12.3 Security Scanning Integration

Image security scanning is a mandatory gate in the CI/CD pipeline. No image with known Critical or High-severity CVEs reaches the staging registry.

| Severity Level | Pipeline Action |
| :--- | :--- |
| Critical | Build fails immediately; image not pushed |
| High | Build fails; image not pushed |
| Medium | Warning emitted; build continues; tracked in security backlog |
| Low | Informational; recorded in scan artifact |
| Negligible / Unknown | No action |

Scanning targets include: OS package vulnerabilities (Alpine apk packages), Java library vulnerabilities (Maven JAR dependencies), and configuration issues (running as root, capabilities). The vulnerability database is updated before each scan run to ensure scan results are based on the current advisory state.

### 12.4 Supply Chain Security

DevFlow implements supply chain security controls to reduce the risk of compromised build dependencies or tampered images reaching production:

- **SBOM Generation.** Every image build produces a Software Bill of Materials (CycloneDX format) enumerating all OS packages, Java libraries, and transitive dependencies included in the image. The SBOM is attached as an OCI artifact to the image manifest.
- **Build Provenance Attestation.** The CI/CD pipeline attaches a SLSA build provenance attestation to each image, cryptographically linking the image to the specific pipeline run and source commit that produced it.
- **Image Signing.** Cosign (Sigstore) is used to sign images with the pipeline's workload identity. Deployment environments require and verify image signatures before pulling any image. An unsigned image is rejected at the deployment stage.

---

## 13. Production Deployment

### 13.1 Production Runtime Topology

Production deployments of DevFlow run multiple instances of the application container behind a load balancer. PostgreSQL and Redis operate as managed services (AWS RDS, AWS ElastiCache, or equivalent) rather than Docker containers in production — managed services provide operational guarantees (automated failover, snapshot management, patch management, monitoring) that self-hosted containers cannot match at production scale.

```
 PRODUCTION RUNTIME ARCHITECTURE
 -----------------------------------------------------------------------

  Internet Traffic
        |
        v
  +-------------------------------------------------------------+
  |              Cloud Load Balancer (L7 -- HTTPS)               |
  |   TLS Termination  Health-check-based routing  WAF           |
  +--------------------+------------------------+----------------+
                       |                        |
                       v                        v
             +-----------------+      +-----------------+
             |  devflow-app    |      |  devflow-app    |     (N instances)
             |  Instance 1     |      |  Instance 2     |
             |  Container      |      |  Container      |
             |  [Active]       |      |  [Active]       |
             +--------+--------+      +--------+--------+
                      |                        |
                      +----------+-------------+
                                 |
                    +------------+------------------+
                    |                               |
                    v                               v
          +------------------+          +---------------------+
          |  Managed         |          |  Managed Redis       |
          |  PostgreSQL 16   |          |  (ElastiCache /      |
          |  (RDS)           |          |   MemoryDB)          |
          |  Primary+Replica |          |  Primary+Replica     |
          +------------------+          +---------------------+
```

### 13.2 Rolling Updates

Rolling updates replace running container instances incrementally, ensuring that the service remains available throughout the deployment cycle.

The rolling update sequence for a production `devflow-app` deployment:

1. A new image version is available in the registry, validated by the CI/CD pipeline against staging.
2. The orchestration platform begins replacing instances according to the configured rolling update policy.
3. For each instance: a new container is started from the updated image and begins its startup sequence. The startup probe monitors the new container until it passes.
4. Once the new container passes its readiness probe, the load balancer adds it to the rotation.
5. The old container receives SIGTERM, drains in-flight requests, and terminates gracefully.
6. The process repeats for the next instance until all instances run the new version.

The rolling update configuration enforces a minimum available instances constraint (e.g., `maxUnavailable: 0`), ensuring that at no point during the update does the active instance count drop below the minimum required for the current traffic load.

### 13.3 Zero Downtime

Zero downtime during deployments is achieved through the combination of:

- **Graceful shutdown** on the departing container (SIGTERM → drain → terminate).
- **Readiness probe gate** on the arriving container (traffic is sent only after readiness probe passes).
- **Minimum available instances** constraint in the rolling update policy.
- **Database schema compatibility:** Flyway migrations are versioned and must be backward-compatible with the previous application version for the duration of the rolling update window. Any migration that is not backward-compatible requires a multi-phase deployment.

### 13.4 Blue/Green and Canary Compatibility

The DevFlow container architecture is compatible with Blue/Green and Canary deployment strategies without application changes:

- **Blue/Green.** Two complete environment stacks (Blue and Green) run in parallel. The load balancer switches 100% of traffic from one stack to the other atomically. The inactive stack provides an instant rollback target. Both stacks run the same stateless application container; their PostgreSQL and Redis connections point to the same backing services.
- **Canary.** A small percentage of traffic (typically 1-5%) is routed to a new version while the majority continues on the current version. The stateless application container and shared backing services support canary without any architectural changes. Canary traffic is controlled at the load balancer routing layer.

### 13.5 Container Observability

| Observability Signal | Source | Sink |
| :--- | :--- | :--- |
| Structured logs (JSON) | Container stdout/stderr | Log aggregator (Grafana Alloy / Fluentd) → Loki / Elasticsearch |
| Application metrics | Spring Boot Actuator → Micrometer → Prometheus endpoint | Prometheus → Grafana |
| Distributed traces | Micrometer Tracing → OpenTelemetry exporter | Tempo / Jaeger / Zipkin |
| Container resource metrics | Container runtime (cAdvisor) | Prometheus |
| Health check status | Orchestration platform | Alertmanager |
| Image signature verification | Admission controller | Audit log |

The logging architecture follows the approved Logging Strategy without modification. Container log collection is the exclusive mechanism for capturing application log output. No log files exist inside containers. The container runtime captures all stdout and stderr output and forwards it to the configured log aggregator. Log aggregator agents add infrastructure-level fields (pod name, node name, namespace) to every log event.

---

## 14. Disaster Recovery

### 14.1 Container Failure Recovery

Container failures are the most frequent failure scenario and have the shortest recovery path. The container runtime's restart policy handles individual container failures automatically.

| Failure Scenario | Detection | Recovery Mechanism | Recovery Time |
| :--- | :--- | :--- | :--- |
| Application container crash (OOM, unhandled exception) | Liveness probe fails; exit code non-zero | Container runtime restarts container per restart policy | < 60 seconds |
| Application container unresponsive (deadlock, GC pause) | Liveness probe timeout | Container runtime kills and restarts container | < 2 minutes |
| Application container OOM kill by host | Host OOM killer sends SIGKILL | Container restart; memory limit review initiated | < 60 seconds |
| PostgreSQL container crash | Application DB health indicator fails | Container restart; WAL recovery from volume | 1-5 minutes |
| Redis container crash | Application Redis health indicator fails | Container restart; AOF replay | < 2 minutes |

### 14.2 Host Failure Recovery

Host failure requires that the application container be rescheduled to a surviving host. In orchestrated environments (Kubernetes, ECS), this is handled automatically by the control plane.

| Recovery Requirement | Mechanism |
| :--- | :--- |
| Application container rescheduling | Orchestration platform detects node failure; schedules container on available node; startup and readiness probes gate traffic resumption |
| PostgreSQL data persistence | Data resides in cloud-managed persistent disk; volume is reattachable to the new host; managed database services handle this automatically |
| Redis data persistence | AOF log on persistent volume; Redis restarts and replays AOF on new host; in managed Redis services, failover to replica is automatic |
| Secret access | Secrets Manager is a managed service; access from a new host requires only that the new host's identity is authorized in the Secrets Manager IAM policy |

### 14.3 Image Rollback

Image rollback is the fastest and safest mechanism for recovering from a defective deployment. Because images are immutable and every previous version remains in the registry, rollback is a tag promotion operation.

```
 IMAGE ROLLBACK PROCEDURE
 -----------------------------------------------------------------

  Defective version detected in production (health checks failing,
  error rate spike, availability degradation)
         |
         v
  [1] Incident declared; rollback decision made by on-call engineer
         |
         v
  [2] Previous known-good image tag identified from deployment record
      devflow-app:1.3.9  (previous stable version)
         |
         v
  [3] Deployment manifest updated to reference devflow-app:1.3.9
         |
         v
  [4] Rolling update initiated with previous image
      (same rolling update procedure -- no special rollback mode)
         |
         v
  [5] New instances (running 1.3.9) pass health checks
      -> Traffic shifts to healthy 1.3.9 instances
         |
         v
  [6] Defective 1.4.0 instances drained and terminated
         |
         v
  [7] Post-rollback validation: smoke tests, error rate monitoring
         |
         v
  [8] Root cause analysis initiated; defect addressed in source
```

The rollback time is bounded by the rolling update duration, which is in turn bounded by the health check probe periods and the container startup time. For DevFlow, the expected rollback duration from decision to full traffic migration is 3-8 minutes.

### 14.4 Registry Outage Recovery

A container registry outage prevents new container pulls but does not affect running containers. Mitigation strategies:

- **Image pull policy: IfNotPresent.** Container instances that are already running do not need to re-pull the image on restart if it is cached on the host. In Kubernetes, the `imagePullPolicy: IfNotPresent` policy ensures that cached images are used when the registry is unavailable.
- **Registry replication.** Production image registries are replicated across availability zones or regions. A single availability zone failure does not affect image availability.
- **Registry failover.** A secondary registry maintains a mirror of production images. Deployment configurations reference the primary registry; manual failover to the secondary registry is documented in the operations run-book.

### 14.5 Persistent Volume Recovery

| Data Store | Recovery Point Objective | Recovery Procedure |
| :--- | :--- | :--- |
| PostgreSQL | < 5 minutes (WAL archival) | Restore from latest RDB snapshot + replay WAL from object storage |
| PostgreSQL | < 1 day (logical backup) | Restore from `pg_dump` backup; replay any missed data manually |
| Redis | < 15 minutes | Restore from RDB snapshot + AOF replay; some recent cache state lost (acceptable) |
| Object Storage | Near-zero (versioning) | Restore from provider versioning; deleted objects recoverable within lifecycle window |

---

## 15. Future Evolution

### 15.1 Kubernetes Migration Path

The DevFlow container architecture is engineered for Kubernetes adoption without requiring changes to the application container. Kubernetes compatibility is a design constraint, not a future consideration.

| DevFlow Architecture Decision | Kubernetes Native Equivalent |
| :--- | :--- |
| Environment variables for configuration | ConfigMap → env injection or Downward API |
| Mounted secret files from Secrets Manager | Kubernetes Secrets backed by external-secrets-operator + Vault / AWS SM |
| Liveness, readiness, and startup probes | Kubernetes native probeSpec (identical endpoint contracts) |
| Named Docker volumes | PersistentVolumeClaim (PVC) with StorageClass |
| Docker bridge network service discovery | Kubernetes Service DNS (`devflow-postgres.default.svc.cluster.local`) |
| Rolling update deployment | Kubernetes Deployment rolling update strategy |
| Docker bridge network isolation | Kubernetes NetworkPolicy for pod-level traffic control |
| Container resource limits | Kubernetes resource requests and limits (identical semantics) |

The migration from Docker Compose or single-host Docker deployment to Kubernetes is a configuration change — not an application code change — because the application binary is already environment-agnostic.

### 15.2 Amazon ECS

Amazon ECS is a fully managed container orchestration service that operates with OCI-standard images without Kubernetes complexity. DevFlow's container architecture is compatible with ECS without modification:

- Task definitions replace Docker Compose / Kubernetes manifests.
- AWS Secrets Manager native integration provides secret injection into ECS tasks.
- ECS Service rolling updates implement the same rolling update semantics.
- AWS Application Load Balancer (ALB) provides health-check-based traffic routing.
- ECS Fargate (serverless compute) is a viable deployment option that eliminates host-level infrastructure management.

### 15.3 HashiCorp Nomad

HashiCorp Nomad provides a lightweight, multi-cloud workload orchestrator that supports Docker containers natively. Nomad's vendor-neutral positioning aligns with DevFlow's architecture principles:

- Nomad job specifications reference OCI images by tag or digest — identical to the images produced by the CI/CD pipeline.
- Nomad Vault integration provides secret injection directly into task environments.
- Nomad's service mesh (Consul Connect) provides mTLS-based inter-service communication for future microservice extraction scenarios.
- Nomad supports heterogeneous workloads (containers, JVM JARs, binary executables) on the same cluster, enabling gradual extraction of DevFlow modules without full Kubernetes adoption.

### 15.4 Multi-Region Deployments

Multi-region deployment extends the container topology across geographic availability zones without architectural changes to the container model.

| Challenge | Architectural Response |
| :--- | :--- |
| Database replication across regions | PostgreSQL read replicas in secondary regions; writes route to primary region; future: CockroachDB or Neon for global distribution |
| Redis replication | Redis cluster with cross-region replication; or separate Redis instances per region with cache warm-up strategy |
| Image distribution | Registry geo-replication ensures images are available in each region's cache without cross-region pull latency |
| Secret distribution | Secrets Manager replication (Vault DR replication; AWS SM cross-region) ensures secrets are available in each region |
| Configuration consistency | GitOps-managed configuration ensures each region's configuration is version-controlled and auditable |

### 15.5 Microservice Extraction Compatibility

When DevFlow modules are extracted into independent microservices, the container architecture scales accordingly:

- Each extracted microservice becomes its own container image, built by the same multi-stage build pipeline.
- The same image strategy (base image pinning, multi-stage builds, security scanning, signing) applies to each microservice image.
- The same configuration injection patterns (environment variables, mounted secrets) apply to each microservice container.
- The same health check contract (`/actuator/health` liveness and readiness) applies to each Spring Boot microservice.
- The network topology expands: each microservice container joins the internal network; inter-service communication uses service discovery (Docker DNS, Kubernetes Service DNS, or service mesh sidecar).

The container architecture does not need to be redesigned for microservices. It scales by replication.

---

## 16. Architectural Principles & Key Design Decisions

| # | Principle | Rationale |
| :--- | :--- | :--- |
| **1** | **Container images are immutable after production.** Once an image is pushed to the registry with a versioned tag, that tag references a fixed, immutable manifest. No post-build modifications are permitted. | Immutability guarantees that the image deployed to production is identical to the image validated in staging. Any modification — however small — invalidates that guarantee and breaks the traceability chain from deployment back to the validated artifact. |
| **2** | **Images are environment-agnostic.** A DevFlow container image contains no environment-specific values: no connection strings, no credentials, no environment identifiers, no feature flag values. The same image is deployed to local, staging, and production environments without modification. | Environment-specific images require environment-specific build pipelines, prevent artifact promotion, and embed configuration into a binary layer where it cannot be changed without rebuilding. Environment-agnostic images enable immutable deployments and simplify the promotion path from staging to production. |
| **3** | **One process per container.** Each container runs exactly one primary process. The application container runs the JVM. The PostgreSQL container runs the Postgres server. No container bundles multiple services. | Multi-process containers require process supervision, complicate signal handling (SIGTERM must reach all processes), obscure health check semantics, and create operational confusion about which process is responsible for a failure. One process per container provides precise operational clarity. |
| **4** | **Configuration is external and injected at runtime.** No configuration is baked into image layers during the build process. All configuration — from database hostnames to JVM tuning flags — is injected at container startup from external sources (environment variables, Secrets Manager, mounted configuration files). | Baked configuration prevents artifact promotion and makes the image environment-specific. External configuration enables the same image to run in any environment and allows configuration changes to be applied without rebuilding and redeploying the image. |
| **5** | **Secrets never enter image layers.** Database passwords, JWT signing keys, API keys, and OAuth secrets are never present in Dockerfile instructions, build arguments committed to layers, or any artifact that can be extracted with `docker history` or `docker save`. | Image layer history is permanent and immutable. A secret baked into an image layer — even in a layer that is subsequently overwritten — remains recoverable from the image manifest. Secret exposure through image layer inspection is a known and exploited attack vector. |
| **6** | **Containers are stateless and disposable.** The application container holds no durable state on its filesystem. It can be stopped, killed, and replaced at any time without data loss, because all durable state is delegated to designated backing services (PostgreSQL, Redis, object storage). | Container statefulness creates operational dependencies: a stateful container cannot be replaced without a data migration, cannot be scaled horizontally without shared state coordination, and creates data loss risk on container replacement. Statelessness enables safe horizontal scaling, zero-downtime deployments, and frictionless container replacement. |
| **7** | **Runtime images are minimal by design.** The production runtime image contains only the JRE, the application JAR, and the minimal Alpine OS. No build tools, package managers, shell utilities, network diagnostic tools, or debugging aids are present. | Every additional component in a runtime image is a potential attack surface. Tools that exist for operational convenience can be exploited by a compromised container process for lateral movement, data exfiltration, or persistence. Minimality is a security control, not an optimization. |
| **8** | **Containers run as non-root users.** The application container process runs under a non-privileged user (`devflow`, UID 1000). No container requires root privileges or elevated Linux capabilities for normal operation. | A container process running as root, if compromised, can escape container isolation through kernel vulnerabilities with no privilege escalation step required. A non-root process requires an additional privilege escalation step to break container boundaries, materially reducing the impact of a compromised container. |
| **9** | **Health determines traffic eligibility.** The load balancer routes traffic exclusively to containers that pass their readiness probe. A container that is alive but not ready (database unreachable, Redis unavailable, application context not fully initialized) does not receive traffic. | Routing requests to a not-ready container causes request failures that are visible to end users. The readiness probe exists precisely to prevent this: it provides the load balancer with an authoritative signal about whether a container can correctly serve a request at any given moment. |
| **10** | **Logs are written to stdout and stderr exclusively.** No log files are written to the container filesystem. No log rotation is performed within the container. The container runtime captures stdout and stderr and forwards them to the logging infrastructure. | Log files on a container filesystem require log rotation management, disk space provisioning, and file shipping infrastructure. They are destroyed when the container is destroyed, losing historical log data. stdout/stderr collection by the container runtime is universal, automatic, and consistent across all environments. |
| **11** | **Base images are pinned by SHA256 digest.** Every base image reference in production image definitions uses both a tag and a SHA256 digest. Tags are mutable references; a new push to a tag can change the image it references. A SHA256 digest is an immutable reference to a specific image manifest. | A mutable base image tag creates an implicit dependency on an external change: a new push to `eclipse-temurin:21-jre-alpine` could introduce new packages, patch a vulnerability, or break a compatibility assumption. Digest pinning eliminates this risk and guarantees that scan results are valid for the deployed image. |
| **12** | **Images are signed and verified before deployment.** Every image produced by the CI/CD pipeline is signed with a cryptographic identity (Cosign, Sigstore). Deployment environments are configured to verify the signature before pulling any image. Unsigned images are rejected. | Image signing provides supply chain integrity: a signed image can be verified to originate from the CI/CD pipeline that produced it. An attacker who compromises the registry and pushes a malicious image cannot get it deployed to production without also compromising the signing key. |
| **13** | **Backing services are network-isolated from external access.** The PostgreSQL and Redis containers are not accessible from outside the Docker network. No ports are published to the host for these services in staging or production. External access requires deliberate, auditable mechanisms. | A publicly exposed database port is a direct attack surface. Even a correctly configured database with strong passwords is vulnerable to brute-force attacks, zero-day vulnerabilities in the database protocol implementation, and credential theft attacks. Network isolation is the primary defense. |
| **14** | **Every deployment is reproducible from version control.** The complete state required to produce and deploy a DevFlow container image is captured in version-controlled artifacts: Dockerfile, pom.xml, deployment manifests. Given a specific commit SHA and the corresponding external configuration, the deployment is fully reproducible. | Reproducibility enables disaster recovery (rebuild from source when the registry is unavailable), forensic analysis (determine exactly what code was running during an incident), and auditable change management (every deployment change is a version-controlled commit, traceable to an author, review, and approval). |
| **15** | **The container architecture is orchestration-agnostic.** No architectural decision binds DevFlow to a specific container orchestration platform. Health check endpoints, configuration injection patterns, image tagging conventions, and graceful shutdown behaviors are designed to work with Docker Compose, Kubernetes, Amazon ECS, HashiCorp Nomad, and direct Docker Engine deployments without code changes. | Orchestration platform selection is an infrastructure decision that changes over DevFlow's lifecycle. A platform-agnostic container architecture prevents the cost of a future orchestration migration from falling on the application engineering team. The application container is a portable artifact; the orchestration layer is a deployment detail. |

---

*This document is the official Docker Architecture Specification for DevFlow. Changes to the container topology, image strategy, health check contracts, networking architecture, or security controls require review and approval from the Architecture Review Board (ARB) and the Infrastructure Security Review function.*
