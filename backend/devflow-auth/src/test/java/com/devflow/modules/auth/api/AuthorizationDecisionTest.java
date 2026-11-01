package com.devflow.modules.auth.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationDecisionTest {

    @Test
    @DisplayName("Create an ALLOW decision")
    void shouldCreateAllowDecision() {
        AuthorizationDecision decision = AuthorizationDecision.allow();

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.denialReason()).isNull();
        assertThat(decision.getDenialReason()).isEmpty();
        assertThat(decision.message()).isEqualTo("Access granted");
    }

    @Test
    @DisplayName("Create a DENY decision with reason and custom message")
    void shouldCreateDenyDecisionWithCustomMessage() {
        AuthorizationDecision decision = AuthorizationDecision.deny(
                DenialReason.TENANT_MISMATCH, "Cross-tenant access attempted");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.TENANT_MISMATCH);
        assertThat(decision.getDenialReason()).contains(DenialReason.TENANT_MISMATCH);
        assertThat(decision.message()).isEqualTo("Cross-tenant access attempted");
    }

    @Test
    @DisplayName("Create a DENY decision with reason default message")
    void shouldCreateDenyDecisionWithDefaultMessage() {
        AuthorizationDecision decision = AuthorizationDecision.deny(DenialReason.INSUFFICIENT_PERMISSIONS);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.INSUFFICIENT_PERMISSIONS);
        assertThat(decision.message()).isEqualTo(DenialReason.INSUFFICIENT_PERMISSIONS.getDefaultMessage());
    }

    @Test
    @DisplayName("Disallow ALLOW decision with a denial reason")
    void shouldDisallowAllowDecisionWithDenialReason() {
        assertThatThrownBy(() -> new AuthorizationDecision(true, DenialReason.MEMBERSHIP_INACTIVE, "Invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ALLOW decision must not carry a denial reason");
    }

    @Test
    @DisplayName("Default null reason to GENERAL_DENIED for rejected decisions")
    void shouldDefaultNullReasonToGeneralDenied() {
        AuthorizationDecision decision = new AuthorizationDecision(false, null, "Denied without reason");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialReason()).isEqualTo(DenialReason.GENERAL_DENIED);
    }

    @Test
    @DisplayName("Reject null reason in static deny factories")
    void shouldRejectNullReasonInFactories() {
        assertThatThrownBy(() -> AuthorizationDecision.deny(null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> AuthorizationDecision.deny(null, "Custom msg"))
                .isInstanceOf(NullPointerException.class);
    }
}

