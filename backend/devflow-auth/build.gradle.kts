// ──────────────────────────────────────────────────────────────────────────────
// DevFlow — Centralized Authorization Module
//
// Reusable, framework-independent Java library module providing the core
// authorization domain models, public contracts, and policy validation
// interfaces for the DevFlow platform.
//
// Root package: com.devflow.modules.auth
// Reference:    docs/security/AUTHORIZATION_MODEL.md
// ──────────────────────────────────────────────────────────────────────────────

plugins {
    `java-library`
}

group = "com.devflow"
version = "0.0.1-SNAPSHOT"

dependencies {
    // ── Testing ───────────────────────────────────────────────────────────────
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.3")
}
