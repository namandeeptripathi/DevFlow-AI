package com.devflow.user.service;

import com.devflow.user.domain.User;
import com.devflow.user.domain.UserPreferences;
import com.devflow.user.dto.NotificationPreferencesDto;
import com.devflow.user.dto.UpdateUserPreferencesRequest;
import com.devflow.user.exception.InvalidPreferencesException;
import com.devflow.user.exception.UserPreferencesNotFoundException;
import com.devflow.user.repository.UserPreferencesRepository;
import com.devflow.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain service managing the retrieval and mutation of a DevFlow user's application preferences.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Retrieve the current user's {@link UserPreferences}.</li>
 *   <li>Apply partial updates ({@code PATCH}) with strict domain validation (e.g. IANA timezone check).</li>
 *   <li>Serialize/deserialize extensible notification preferences stored in PostgreSQL {@code JSONB}.</li>
 * </ul>
 *
 * @see UserPreferences
 * @see UserPreferencesRepository
 * @see UpdateUserPreferencesRequest
 */
@Service
public class UserPreferencesService {

    private static final Logger log = LoggerFactory.getLogger(UserPreferencesService.class);

    private static final String KEY_EMAIL_NOTIFICATIONS = "emailNotifications";
    private static final String KEY_PUSH_NOTIFICATIONS = "pushNotifications";
    private static final String KEY_MENTION_NOTIFICATIONS = "mentionNotifications";

    private final UserPreferencesRepository userPreferencesRepository;
    private final UserRepository userRepository;

    public UserPreferencesService(
            UserPreferencesRepository userPreferencesRepository,
            UserRepository userRepository
    ) {
        this.userPreferencesRepository = Objects.requireNonNull(
                userPreferencesRepository, "userPreferencesRepository must not be null");
        this.userRepository = Objects.requireNonNull(
                userRepository, "userRepository must not be null");
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Retrieves {@link UserPreferences} for the specified user ID.
     *
     * <p>If preferences do not exist yet for an active user, a default {@link UserPreferences}
     * entity is initialized and persisted.
     *
     * @param userId the UUID of the authenticated user
     * @return the user's preferences entity
     * @throws UserPreferencesNotFoundException if user entity does not exist
     */
    @Transactional
    public UserPreferences getPreferences(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");

        log.debug("Fetching preferences for user [{}]", userId);

        return userPreferencesRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultPreferences(userId));
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Applies partial updates to the user's preferences.
     *
     * @param userId  the UUID of the authenticated user
     * @param request the preferences update command
     * @return the updated and persisted {@link UserPreferences} entity
     * @throws UserPreferencesNotFoundException if no user/preferences exist
     * @throws InvalidPreferencesException      if timezone or language values fail validation
     */
    @Transactional
    public UserPreferences updatePreferences(UUID userId, UpdateUserPreferencesRequest request) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(request, "UpdateUserPreferencesRequest must not be null");

        UserPreferences preferences = userPreferencesRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultPreferences(userId));

        applyUpdates(preferences, request);

        UserPreferences saved = userPreferencesRepository.save(preferences);
        log.info("Preferences updated for user [{}]", userId);
        return saved;
    }

    // ── DTO Conversion Helpers ────────────────────────────────────────────────

    /**
     * Converts the entity's JSONB notification preferences map into a structured {@link NotificationPreferencesDto}.
     */
    public NotificationPreferencesDto mapNotificationMapToDto(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return NotificationPreferencesDto.builder()
                    .emailNotifications(true)
                    .pushNotifications(true)
                    .mentionNotifications(true)
                    .additionalChannels(new HashMap<>())
                    .build();
        }

        Boolean email = getBooleanValue(map, KEY_EMAIL_NOTIFICATIONS, true);
        Boolean push = getBooleanValue(map, KEY_PUSH_NOTIFICATIONS, true);
        Boolean mention = getBooleanValue(map, KEY_MENTION_NOTIFICATIONS, true);

        Map<String, Object> additional = new HashMap<>();
        map.forEach((k, v) -> {
            if (!KEY_EMAIL_NOTIFICATIONS.equals(k)
                    && !KEY_PUSH_NOTIFICATIONS.equals(k)
                    && !KEY_MENTION_NOTIFICATIONS.equals(k)) {
                additional.put(k, v);
            }
        });

        return NotificationPreferencesDto.builder()
                .emailNotifications(email)
                .pushNotifications(push)
                .mentionNotifications(mention)
                .additionalChannels(additional)
                .build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private UserPreferences createDefaultPreferences(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Preferences lookup failed: user [{}] not found", userId);
                    return new UserPreferencesNotFoundException("User not found: " + userId);
                });

        Map<String, Object> defaultNotificationMap = new HashMap<>();
        defaultNotificationMap.put(KEY_EMAIL_NOTIFICATIONS, true);
        defaultNotificationMap.put(KEY_PUSH_NOTIFICATIONS, true);
        defaultNotificationMap.put(KEY_MENTION_NOTIFICATIONS, true);

        UserPreferences defaultPreferences = UserPreferences.builder()
                .user(user)
                .timezone("UTC")
                .language("en")
                .notificationPreferences(defaultNotificationMap)
                .build();

        try {
            UserPreferences saved = userPreferencesRepository.saveAndFlush(defaultPreferences);
            log.info("Initialised default user preferences for user [{}]", userId);
            return saved;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.debug("Concurrent preferences initialisation race detected for user [{}]; fetching persisted record", userId);
            return userPreferencesRepository.findByUserId(userId)
                    .orElseThrow(() -> new UserPreferencesNotFoundException("Preferences record not found for user: " + userId));
        }
    }

    private void applyUpdates(UserPreferences preferences, UpdateUserPreferencesRequest request) {
        if (request.getTheme() != null) {
            preferences.setTheme(request.getTheme());
        }

        if (request.getTimezone() != null) {
            String timezoneStr = request.getTimezone().trim();
            validateTimezone(timezoneStr);
            preferences.setTimezone(timezoneStr);
        }

        if (request.getLanguage() != null) {
            String languageStr = request.getLanguage().trim();
            validateLanguage(languageStr);
            preferences.setLanguage(languageStr);
        }

        if (request.getDateFormat() != null) {
            preferences.setDateFormat(request.getDateFormat());
        }

        if (request.getTimeFormat() != null) {
            preferences.setTimeFormat(request.getTimeFormat());
        }

        if (request.getNotificationPreferences() != null) {
            Map<String, Object> updatedMap = mergeNotificationMap(
                    preferences.getNotificationPreferences(),
                    request.getNotificationPreferences()
            );
            preferences.setNotificationPreferences(updatedMap);
        }
    }

    private void validateTimezone(String timezoneStr) {
        if (timezoneStr.isEmpty()) {
            throw new InvalidPreferencesException("Timezone must not be empty");
        }
        try {
            ZoneId.of(timezoneStr);
        } catch (DateTimeException e) {
            log.warn("Invalid timezone rejected: [{}]", timezoneStr);
            throw new InvalidPreferencesException("Invalid IANA timezone identifier: " + timezoneStr, e);
        }
    }

    private void validateLanguage(String languageStr) {
        if (languageStr.isEmpty()) {
            throw new InvalidPreferencesException("Language code must not be empty");
        }
        if (languageStr.length() > 10) {
            throw new InvalidPreferencesException("Language code cannot exceed 10 characters");
        }
        java.util.Locale locale = java.util.Locale.forLanguageTag(languageStr);
        if (locale.getLanguage().isEmpty()) {
            throw new InvalidPreferencesException("Invalid BCP 47 / ISO language code: " + languageStr);
        }
    }

    private Map<String, Object> mergeNotificationMap(
            Map<String, Object> existingMap,
            NotificationPreferencesDto dto
    ) {
        Map<String, Object> map = (existingMap != null) ? new HashMap<>(existingMap) : new HashMap<>();

        if (dto.getEmailNotifications() != null) {
            map.put(KEY_EMAIL_NOTIFICATIONS, dto.getEmailNotifications());
        }
        if (dto.getPushNotifications() != null) {
            map.put(KEY_PUSH_NOTIFICATIONS, dto.getPushNotifications());
        }
        if (dto.getMentionNotifications() != null) {
            map.put(KEY_MENTION_NOTIFICATIONS, dto.getMentionNotifications());
        }

        if (dto.getAdditionalChannels() != null) {
            map.putAll(dto.getAdditionalChannels());
        }

        return map;
    }

    private Boolean getBooleanValue(Map<String, Object> map, String key, Boolean defaultValue) {
        Object val = map.get(key);
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
    }
}
