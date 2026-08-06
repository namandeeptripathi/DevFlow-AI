package com.devflow.user.service;

import com.devflow.user.domain.DateFormat;
import com.devflow.user.domain.Theme;
import com.devflow.user.domain.TimeFormat;
import com.devflow.user.domain.User;
import com.devflow.user.domain.UserPreferences;
import com.devflow.user.dto.NotificationPreferencesDto;
import com.devflow.user.dto.UpdateUserPreferencesRequest;
import com.devflow.user.exception.InvalidPreferencesException;
import com.devflow.user.exception.UserPreferencesNotFoundException;
import com.devflow.user.repository.UserPreferencesRepository;
import com.devflow.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserPreferencesService")
class UserPreferencesServiceTest {

    @Mock
    private UserPreferencesRepository userPreferencesRepository;

    @Mock
    private UserRepository userRepository;

    private UserPreferencesService preferencesService;
    private User testUser;
    private UserPreferences testPreferences;
    private UUID userId;

    @BeforeEach
    void setUp() {
        preferencesService = new UserPreferencesService(userPreferencesRepository, userRepository);

        userId = UUID.randomUUID();
        testUser = User.builder().id(userId).email("pref@devflow.com").username("prefuser").build();

        Map<String, Object> initialNotifs = new HashMap<>();
        initialNotifs.put("emailNotifications", true);
        initialNotifs.put("pushNotifications", false);
        initialNotifs.put("mentionNotifications", true);

        testPreferences = UserPreferences.builder()
                .id(UUID.randomUUID())
                .user(testUser)
                .theme(Theme.DARK)
                .timezone("UTC")
                .language("en")
                .dateFormat(DateFormat.ISO)
                .timeFormat(TimeFormat.TWENTY_FOUR_HOUR)
                .notificationPreferences(initialNotifs)
                .build();
    }

    @Nested
    @DisplayName("Preferences Retrieval")
    class PreferencesRetrieval {

        @Test
        @DisplayName("getPreferences returns existing preferences when found")
        void getPreferences_returnsPreferences_whenFound() {
            when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(testPreferences));

            UserPreferences result = preferencesService.getPreferences(userId);

            assertThat(result.getTheme()).isEqualTo(Theme.DARK);
            assertThat(result.getTimezone()).isEqualTo("UTC");
        }

        @Test
        @DisplayName("getPreferences initialises defaults when preferences not found for user")
        void getPreferences_initialisesDefaults_whenMissing() {
            when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.empty());
            when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
            when(userPreferencesRepository.saveAndFlush(any(UserPreferences.class))).thenAnswer(i -> i.getArgument(0));

            UserPreferences result = preferencesService.getPreferences(userId);

            assertThat(result).isNotNull();
            assertThat(result.getTimezone()).isEqualTo("UTC");
            assertThat(result.getLanguage()).isEqualTo("en");
            verify(userPreferencesRepository).saveAndFlush(any(UserPreferences.class));
        }

        @Test
        @DisplayName("getPreferences throws UserPreferencesNotFoundException when user entity missing")
        void getPreferences_throwsException_whenUserMissing() {
            when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.empty());
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> preferencesService.getPreferences(userId))
                    .isInstanceOf(UserPreferencesNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Preferences Update & Validation")
    class PreferencesUpdate {

        @Test
        @DisplayName("updatePreferences updates theme and validates valid IANA timezone")
        void updatePreferences_validTimezone_success() {
            when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(testPreferences));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenAnswer(i -> i.getArgument(0));

            UpdateUserPreferencesRequest request = UpdateUserPreferencesRequest.builder()
                    .theme(Theme.LIGHT)
                    .timezone("America/New_York")
                    .language("es")
                    .build();

            UserPreferences updated = preferencesService.updatePreferences(userId, request);

            assertThat(updated.getTheme()).isEqualTo(Theme.LIGHT);
            assertThat(updated.getTimezone()).isEqualTo("America/New_York");
            assertThat(updated.getLanguage()).isEqualTo("es");
        }

        @Test
        @DisplayName("updatePreferences throws InvalidPreferencesException for invalid IANA timezone")
        void updatePreferences_invalidTimezone_throwsException() {
            when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(testPreferences));

            UpdateUserPreferencesRequest request = UpdateUserPreferencesRequest.builder()
                    .timezone("Invalid/Timezone_Location")
                    .build();

            assertThatThrownBy(() -> preferencesService.updatePreferences(userId, request))
                    .isInstanceOf(InvalidPreferencesException.class)
                    .hasMessageContaining("Invalid IANA timezone identifier");
        }

        @Test
        @DisplayName("updatePreferences merges notification channel toggles and retains custom keys")
        void updatePreferences_mergesNotificationPreferences() {
            when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(testPreferences));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenAnswer(i -> i.getArgument(0));

            Map<String, Object> customExtra = new HashMap<>();
            customExtra.put("slackChannel", true);

            NotificationPreferencesDto notifDto = NotificationPreferencesDto.builder()
                    .pushNotifications(true)
                    .additionalChannels(customExtra)
                    .build();

            UpdateUserPreferencesRequest request = UpdateUserPreferencesRequest.builder()
                    .notificationPreferences(notifDto)
                    .build();

            UserPreferences updated = preferencesService.updatePreferences(userId, request);

            Map<String, Object> resultMap = updated.getNotificationPreferences();
            assertThat(resultMap.get("emailNotifications")).isEqualTo(true); // preserved
            assertThat(resultMap.get("pushNotifications")).isEqualTo(true);  // updated
            assertThat(resultMap.get("slackChannel")).isEqualTo(true);       // added
        }
    }
}
