package com.devflow.user.service;

import com.devflow.config.AvatarProperties;
import com.devflow.user.avatar.AvatarStorageService;
import com.devflow.user.domain.UserProfile;
import com.devflow.user.exception.InvalidAvatarException;
import com.devflow.user.exception.UserProfileNotFoundException;
import com.devflow.user.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AvatarService")
class AvatarServiceTest {

    @Mock
    private AvatarStorageService storageService;

    @Mock
    private UserProfileRepository profileRepository;

    private AvatarProperties avatarProperties;
    private AvatarService avatarService;

    private UUID userId;
    private UserProfile testProfile;

    @BeforeEach
    void setUp() {
        avatarProperties = new AvatarProperties();
        avatarProperties.setMaxSizeBytes(5_242_880L);

        avatarService = new AvatarService(storageService, profileRepository, avatarProperties);

        userId = UUID.randomUUID();
        testProfile = UserProfile.builder()
                .id(UUID.randomUUID())
                .displayName("Avatar Test User")
                .avatarUrl("http://localhost:8080/uploads/avatars/old-avatar.png")
                .build();
    }

    @Nested
    @DisplayName("Avatar Upload & Replacement")
    class UploadAndReplacement {

        @Test
        @DisplayName("uploadAvatar removes old avatar file, stores new file, and updates profile URL")
        void uploadAvatar_successfulReplacement() {
            when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
            when(storageService.store(any(), eq(userId))).thenReturn("http://localhost:8080/uploads/avatars/new-avatar.png");
            when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            MockMultipartFile file = new MockMultipartFile(
                    "file", "avatar.jpg", "image/jpeg", new byte[]{1, 2, 3, 4}
            );

            UserProfile updated = avatarService.uploadAvatar(userId, file);

            verify(storageService).delete("http://localhost:8080/uploads/avatars/old-avatar.png");
            verify(storageService).store(file, userId);
            assertThat(updated.getAvatarUrl()).isEqualTo("http://localhost:8080/uploads/avatars/new-avatar.png");
        }

        @Test
        @DisplayName("uploadAvatar logs warning and continues if deleting previous avatar file fails")
        void uploadAvatar_oldFileDeleteFailure_doesNotBlockUpload() {
            when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
            doThrow(new RuntimeException("IO error")).when(storageService).delete(any());
            when(storageService.store(any(), eq(userId))).thenReturn("http://localhost:8080/uploads/avatars/new-avatar.png");
            when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1, 2, 3});

            UserProfile updated = avatarService.uploadAvatar(userId, file);

            assertThat(updated.getAvatarUrl()).isEqualTo("http://localhost:8080/uploads/avatars/new-avatar.png");
        }

        @Test
        @DisplayName("uploadAvatar throws InvalidAvatarException for empty file")
        void uploadAvatar_emptyFile_throwsException() {
            MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

            assertThatThrownBy(() -> avatarService.uploadAvatar(userId, emptyFile))
                    .isInstanceOf(InvalidAvatarException.class)
                    .hasMessageContaining("must not be empty");
        }

        @Test
        @DisplayName("uploadAvatar throws InvalidAvatarException for oversized file (> 5 MB)")
        void uploadAvatar_oversizedFile_throwsException() {
            byte[] largeContent = new byte[6 * 1024 * 1024]; // 6 MB
            MockMultipartFile largeFile = new MockMultipartFile("file", "large.png", "image/png", largeContent);

            assertThatThrownBy(() -> avatarService.uploadAvatar(userId, largeFile))
                    .isInstanceOf(InvalidAvatarException.class)
                    .hasMessageContaining("exceeds the maximum allowed size");
        }
    }

    @Nested
    @DisplayName("Avatar Delete")
    class AvatarDelete {

        @Test
        @DisplayName("deleteAvatar deletes file from storage and clears avatarUrl")
        void deleteAvatar_successful() {
            when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));
            when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            UserProfile result = avatarService.deleteAvatar(userId);

            verify(storageService).delete("http://localhost:8080/uploads/avatars/old-avatar.png");
            assertThat(result.getAvatarUrl()).isNull();
        }

        @Test
        @DisplayName("deleteAvatar is no-op if avatarUrl is already null")
        void deleteAvatar_noOp_whenUrlNull() {
            testProfile.setAvatarUrl(null);
            when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(testProfile));

            UserProfile result = avatarService.deleteAvatar(userId);

            verify(storageService, never()).delete(any());
            assertThat(result.getAvatarUrl()).isNull();
        }
    }
}
