// ──────────────────────────────────────────────────────────────────────────────
// DevFlow Backend — Root Build Script
//
// This is the root Gradle build file for the DevFlow backend monorepo.
// It applies shared conventions and plugin versions for all submodules.
//
// Reference: docs/architecture/REPOSITORY_STRUCTURE.md §10 (Build Organization)
// ──────────────────────────────────────────────────────────────────────────────

plugins {
    // Declare third-party plugins at root level so versions are managed centrally.
    // Each submodule applies the plugins it needs without re-specifying versions.
    // Note: Core Gradle plugins (java, etc.) must NOT be declared here with apply false.
    alias(libs.plugins.spring.boot)                  apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

// Shared configuration applied to all subprojects in the monorepo.
subprojects {
    apply(plugin = "java")

    // ── Enforce Java 21 for all modules ──────────────────────────────────────
    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    // ── Repository configuration shared across all subprojects ───────────────
    repositories {
        mavenCentral()
    }

    // ── Encoding ──────────────────────────────────────────────────────────────
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Display test events in CI-friendly format
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
