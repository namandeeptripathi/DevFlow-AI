package com.devflow.modules.auth.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class DenialReasonTest {

    @ParameterizedTest
    @EnumSource(DenialReason.class)
    @DisplayName("Every DenialReason must have a descriptive default message")
    void everyReasonMustHaveDefaultMessage(DenialReason reason) {
        assertThat(reason.getDefaultMessage()).isNotBlank();
    }

    @Test
    @DisplayName("Verify expected taxonomy values match Authorization Model §10.1")
    void shouldContainExpectedTaxonomy() {
        assertThat(DenialReason.values()).contains(
                DenialReason.TENANT_MISMATCH,
                DenialReason.MEMBERSHIP_INACTIVE,
                DenialReason.INSUFFICIENT_PERMISSIONS,
                DenialReason.OWNERSHIP_REQUIRED,
                DenialReason.RESOURCE_STATE_CONFLICT,
                DenialReason.GENERAL_DENIED
        );
    }
}

