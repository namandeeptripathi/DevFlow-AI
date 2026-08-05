rootProject.name = "devflow-backend"

// ──────────────────────────────────────────────────────────────────────────────
// Submodules
//
// Each entry here corresponds to a bounded context defined in the Domain Model
// Specification (docs/architecture/DOMAIN_MODEL.md).
//
// Only the application bootstrap module is active at this stage.
// Domain modules will be added incrementally as they are scaffolded.
// ──────────────────────────────────────────────────────────────────────────────
include(":app")

// Future domain modules — uncomment as they are scaffolded:
// include(":devflow-shared-kernel")
// include(":devflow-auth")
// include(":devflow-project-management")
// include(":devflow-repository-intelligence")
// include(":devflow-ai-engine")
// include(":devflow-knowledge-base")
// include(":devflow-developer-analytics")
// include(":devflow-workflow-automation")
// include(":devflow-collaboration")
// include(":devflow-notifications")
