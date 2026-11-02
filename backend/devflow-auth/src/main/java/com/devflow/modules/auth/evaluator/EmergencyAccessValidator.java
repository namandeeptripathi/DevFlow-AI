package com.devflow.modules.auth.evaluator;

import java.time.Instant;
import java.util.UUID;

/**
 * Framework-independent port for validating platform emergency access execution (§11.5, ADR-001).
 *
 * <p>Enforces the security gate that emergency access execution ({@code platform.emergency_access.execute}):
 * <ul>
 *   <li>Requires verified out-of-band approval from a designated security officer.</li>
 *   <li>Enforces a strict maximum 4-hour authorization window from the time of approval.</li>
 *   <li>Fails closed immediately if approval or window expiry cannot be independently verified.</li>
 *   <li>Is evaluated independently from ordinary role grants.</li>
 * </ul>
 *
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §11.5</a>
 * @see <a href="docs/security/ADR-001-AUTHORIZATION-POLICY-RESOLUTION.md">ADR-001</a>
 */
@FunctionalInterface
public interface EmergencyAccessValidator {

    /**
     * Checks whether emergency access execution is authorized for the given user at the specified time.
     *
     * @param userId          the UUID of the platform administrator requesting emergency execution
     * @param evaluationTime the instant of request evaluation against the 4-hour authorization window
     * @return {@code true} if independently verified and within the 4-hour window; {@code false} otherwise
     */
    boolean isEmergencyAccessAuthorized(UUID userId, Instant evaluationTime);
}
