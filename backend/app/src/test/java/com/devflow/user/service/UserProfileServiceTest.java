package com.devflow.user.service;

import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.domain.UserProfile;
import com.devflow.user.exception.UserProfileNotFoundException;
import com.devflow.user.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserProfileService")
class UserProfileServiceTest {

    @Mock
    private UserProfileRepository userProfileRepository;

    private UserProfileService userProfileService;
    private User testUser;
    private UserProfile testProfile;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userProfileService = new UserProfileService(userProfileRepository);

        userId = UUID.randomUUID();
        testUser = User.builder()
                .id(userId)
                .email("john.doe@devflow.com")
                .username("johndoe")
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        testProfile = UserProfile.builder()
                .id(UUID.randomUUID())
                .user(testUser)
                .displayName("John Doe")
                .firstName("John")
                .lastName("Doe")
                .bio("Software Engineer")
                .avatarUrl("http://localhost:8080/uploads/avatars/avatar.png")
                .build();
    }

    @Nested
    @DisplayName("Profile Retrieval")
    class ProfileRetrieval {

        @Test
        @DisplayName("getProfile returns user profile when it exists")
        void getProfile_returnsProfile_whenExists() {
            when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));

            UserProfile result = userProfileService.getProfile(userId);

            assertThat(result).isNotNull();
            assertThat(result.getDisplayName()).isEqualTo("John Doe");
            assertThat(result.getUser().getId()).isEqualTo(userId);
            verify(userProfileRepository).findByUserId(userId);
        }

        @Test
        @DisplayName("getProfile throws UserProfileNotFoundException when profile does not exist")
        void getProfile_throwsException_whenNotFound() {
            when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userProfileService.getProfile(userId))
                    .isInstanceOf(UserProfileNotFoundException.class)
                    .hasMessageContaining("Profile not found for user");
        }

        @Test
        @DisplayName("getProfile throws NullPointerException when userId is null")
        void getProfile_throwsNullPointer_whenUserIdNull() {
            assertThatThrownBy(() -> userProfileService.getProfile(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Profile Update")
    class ProfileUpdate {

        @Test
        @DisplayName("updateProfile applies non-null fields and persists updated profile")
        void updateProfile_appliesUpdates_successfully() {
            when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
            when(userProfileRepository.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

            UpdateProfileRequest updateRequest = UpdateProfileRequest.builder()
                    .displayName("Jane Doe")
                    .bio("Principal Engineer")
                    .build();

            UserProfile result = userProfileService.updateProfile(userId, updateRequest);

            assertThat(result.getDisplayName()).isEqualTo("Jane Doe");
            assertThat(result.getBio()).isEqualTo("Principal Engineer");
            assertThat(result.getFirstName()).isEqualTo("John"); // unchanged
            assertThat(result.getLastName()).isEqualTo("Doe");   // unchanged

            ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
            verify(userProfileRepository).save(captor.capture());
            assertThat(captor.getValue().getDisplayName()).isEqualTo("Jane Doe");
        }

        @Test
        @DisplayName("updateProfile normalises empty strings to null")
        void updateProfile_normalisesEmptyStringsToNull() {
            when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
            when(userProfileRepository.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

            UpdateProfileRequest updateRequest = UpdateProfileRequest.builder()
                    .displayName("   ")
                    .bio("")
                    .build();

            UserProfile result = userProfileService.updateProfile(userId, updateRequest);

            assertThat(result.getDisplayName()).isNull();
            assertThat(result.getBio()).isNull();
        }

        @Test
        @DisplayName("updateProfile throws UserProfileNotFoundException when profile does not exist")
        void updateProfile_throwsException_whenNotFound() {
            when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

            UpdateProfileRequest updateRequest = UpdateProfileRequest.builder()
                    .displayName("Jane Doe")
                    .build();

            assertThatThrownBy(() -> userProfileService.updateProfile(userId, updateRequest))
                    .isInstanceOf(UserProfileNotFoundException.class);

            verify(userProfileRepository, never()).save(any());
        }
    }
}
