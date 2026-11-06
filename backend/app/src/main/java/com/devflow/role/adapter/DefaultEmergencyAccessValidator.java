package com.devflow.role.adapter;

import com.devflow.modules.auth.evaluator.EmergencyAccessValidator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production implementation of {@link EmergencyAccessValidator} from {@code devflow-auth}.
 *
 * <p>Enforces emergency access gates (§11.5, ADR-001):
 * <ul>
 *   <li>Requires explicit security officer out-of-band approval.</li>
 *   <li>Enforces a strict maximum 4-hour window from the approval timestamp.</li>
 *   <li>Fails closed if approval is absent, expired, or unverified.</li>
 * </ul>
 *
 * @see EmergencyAccessValidator
 */
@Component
public class DefaultEmergencyAccessValidator implements EmergencyAccessValidator {

    private static final Duration MAX_EMERGENCY_WINDOW = Duration.ofHours(4);

    private final Map<UUID, EmergencyWindow> activeWindows = new ConcurrentHashMap<>();

    /**
     * Activates an emergency access window for a platform administrator.
     *
     * @param userId platform administrator receiving emergency access
     * @param approvedBySecurityOfficerId security officer authorizing access
     * @param approvedAt approval timestamp
     * @param requestedDuration requested window duration (capped at 4 hours)
     */
    public void grantEmergencyAccess(
            UUID userId,
            UUID approvedBySecurityOfficerId,
            Instant approvedAt,
            Duration requestedDuration
    ) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(approvedBySecurityOfficerId, "approvedBySecurityOfficerId must not be null");
        Objects.requireNonNull(approvedAt, "approvedAt must not be null");
        Objects.requireNonNull(requestedDuration, "requestedDuration must not be null");

        Duration effectiveDuration = requestedDuration.compareTo(MAX_EMERGENCY_WINDOW) > 0
                ? MAX_EMERGENCY_WINDOW
                : requestedDuration;

        Instant windowEnd = approvedAt.plus(effectiveDuration);
        activeWindows.put(userId, new EmergencyWindow(userId, approvedBySecurityOfficerId, approvedAt, windowEnd));
    }

    /**
     * Revokes an active emergency access window.
     *
     * @param userId administrator UUID
     */
    public void revokeEmergencyAccess(UUID userId) {
        if (userId != null) {
            activeWindows.remove(userId);
        }
    }

    /**
     * Clears all active windows (useful for test isolation).
     */
    public void clearAll() {
        activeWindows.clear();
    }

    @Override
    public boolean isEmergencyAccessAuthorized(UUID userId, Instant evaluationTime) {
        if (userId == null || evaluationTime == null) {
            return false;
        }

        EmergencyWindow window = activeWindows.get(userId);
        if (window == null) {
            return false; // Fail closed if no approved window exists
        }

        return window.isValid(evaluationTime);
    }

    private record EmergencyWindow(
            UUID userId,
            UUID approvedBySecurityOfficerId,
            Instant approvedAt,
            Instant windowEnd
    ) {
        boolean isValid(Instant evaluationTime) {
            return !evaluationTime.isBefore(approvedAt) && evaluationTime.isBefore(windowEnd);
        }
    }
}
