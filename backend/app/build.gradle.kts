// ──────────────────────────────────────────────────────────────────────────────
// DevFlow — Application Bootstrap Module
//
// This module is the Spring Boot application entry point. It wires together
// all domain modules (added incrementally) and owns application-level
// configuration: Spring Security filter chain, global exception handling,
// OpenAPI specification, Actuator, and cross-cutting infrastructure setup.
//
// Package root:  com.devflow
// Profile docs:  docs/configuration/CONFIGURATION_STRATEGY.md
// ──────────────────────────────────────────────────────────────────────────────

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    java
}

group = "com.devflow"
version = "0.0.1-SNAPSHOT"

// ── Import Spring Boot BOM via dependency management ─────────────────────────
// This ensures all Spring libraries are version-aligned without specifying
// versions individually in each dependency declaration.
dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
        mavenBom("org.testcontainers:testcontainers-bom:${libs.versions.testcontainers.get()}")
    }
}

dependencies {

    // ── Core Spring Boot Starters ─────────────────────────────────────────────
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.redis)

    // ── Security & JWT ────────────────────────────────────────────────────────
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    // ── Database ──────────────────────────────────────────────────────────────
    runtimeOnly(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    // ── API Documentation ─────────────────────────────────────────────────────
    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    // ── Development Utilities ─────────────────────────────────────────────────
    // Lombok: compile-time only — no runtime dependency
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.spring.boot.configuration.processor)

    // DevTools: excluded from production JAR automatically by Spring Boot plugin
    developmentOnly(libs.spring.boot.devtools)

    // ── Testing ───────────────────────────────────────────────────────────────
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.h2)
    testImplementation(libs.mockito.core)

    // Lombok in tests
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

// ── Jar packaging ────────────────────────────────────────────────────────────
// Produces an executable fat JAR as per task specification.
// Spring Boot plugin handles this automatically; plain JAR is disabled.
tasks.named<Jar>("jar") {
    // Disable the plain JAR — only the executable fat JAR is needed
    archiveClassifier.set("")
    enabled = false
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("")
    archiveFileName.set("devflow-backend-${project.version}.jar")
}
