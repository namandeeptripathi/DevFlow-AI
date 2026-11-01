package com.devflow.modules.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionTest {

    @Test
    @DisplayName("Create permission with valid resource and action components")
    void shouldCreatePermissionWithComponents() {
        Permission permission = Permission.of("project", "read");

        assertThat(permission.resource()).isEqualTo("project");
        assertThat(permission.action()).isEqualTo("read");
        assertThat(permission.value()).isEqualTo("project.read");
        assertThat(permission.toString()).isEqualTo("project.read");
    }

    @Test
    @DisplayName("Normalize resource and action using Locale.ROOT to lowercase and trimmed")
    void shouldNormalizeWithLocaleRoot() {
        Permission permission = Permission.of("  PROJECT  ", "  WRITE  ");

        assertThat(permission.resource()).isEqualTo("project");
        assertThat(permission.action()).isEqualTo("write");
        assertThat(permission.value()).isEqualTo("project.write");
    }

    @Test
    @DisplayName("Ensure normalization is immune to default locale case-folding anomalies (e.g. Turkish I)")
    void shouldNormalizeImmuneToLocaleAnomalies() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Permission permission = Permission.of("ITEM", "INIT");
            assertThat(permission.resource()).isEqualTo("item");
            assertThat(permission.action()).isEqualTo("init");
            assertThat(permission.value()).isEqualTo("item.init");
        } finally {
            Locale.setDefault(original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "project.read",
            "task.create",
            "repo.connect",
            "ai.review.approve",
            "repo.sync.diff.fetch",
            "cycle-run.start",
            "task_item.delete_all",
            "v1.test",
            "oauth2.authorize",
            "a.b"
    })
    @DisplayName("Accept valid permission strings conforming to documented grammar")
    void shouldAcceptValidPermissions(String validPermission) {
        Permission permission = Permission.of(validPermission);
        assertThat(permission.value()).isEqualTo(validPermission.toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("Parse permission with composite action identifiers")
    void shouldParsePermissionWithMultiSegmentAction() {
        Permission permission = Permission.of("ai.review.approve");

        assertThat(permission.resource()).isEqualTo("ai");
        assertThat(permission.action()).isEqualTo("review.approve");
        assertThat(permission.value()).isEqualTo("ai.review.approve");
    }

    @Test
    @DisplayName("Fail validation on null arguments")
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> Permission.of(null, "read"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> Permission.of("project", null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> Permission.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("Fail validation on blank components")
    void shouldRejectBlankComponents(String blank) {
        assertThatThrownBy(() -> Permission.of(blank, "read"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Permission.of("project", blank))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "project",
            ".read",
            "project.",
            "   ",
            "nodots",
            "project..read",
            "ai.review..approve",
            "project.read.",
            "-project.read",
            "_project.read",
            "project.-read",
            "project.read@all",
            "task$.create",
            "repo/connect",
            "repo:connect",
            "project read"
    })
    @DisplayName("Fail validation on malformed permission strings and grammar violations")
    void shouldRejectMalformedPermissionStrings(String malformed) {
        assertThatThrownBy(() -> Permission.of(malformed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Fail validation on grammar violations in individual components")
    void shouldRejectGrammarViolationsInComponents() {
        assertThatThrownBy(() -> Permission.of("-project", "read"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Resource identifier");

        assertThatThrownBy(() -> Permission.of("project", "read@all"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Action identifier");

        assertThatThrownBy(() -> Permission.of("project", "read..write"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Action identifier");
    }

    @Test
    @DisplayName("Verify equality and hashCode contracts for value object")
    void shouldAdhereToValueObjectEquality() {
        Permission p1 = Permission.of("project.delete");
        Permission p2 = Permission.of("project", "delete");
        Permission p3 = Permission.of("task.delete");

        assertThat(p1).isEqualTo(p2);
        assertThat(p1.hashCode()).isEqualTo(p2.hashCode());
        assertThat(p1).isNotEqualTo(p3);
    }
}
