package com.devflow.security.user;

import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DevFlowUserDetails")
class DevFlowUserDetailsTest {

    private User createTestUser(AccountStatus status) {
        return User.builder()
                .id(UUID.randomUUID())
                .email("alex.dev@example.com")
                .username("alexdev")
                .passwordHash("$2a$12$eImiTXuWVxfM37uY4JANjO.GkZ.o5.Vq0f3Lw6M5b0gE5Vq0f3Lw6")
                .accountStatus(status)
                .emailVerified(true)
                .build();
    }

    @Test
    @DisplayName("Constructor throws NullPointerException when user is null")
    void constructor_throwsNullPointerException_whenUserIsNull() {
        assertThatThrownBy(() -> new DevFlowUserDetails(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("User entity must not be null");
    }

    @Test
    @DisplayName("Exposes user entity, id, email, and passwordHash correctly")
    void exposesUserAttributesCorrectly() {
        User user = createTestUser(AccountStatus.ACTIVE);
        DevFlowUserDetails userDetails = new DevFlowUserDetails(user);

        assertThat(userDetails.getUser()).isEqualTo(user);
        assertThat(userDetails.getId()).isEqualTo(user.getId());
        assertThat(userDetails.getEmail()).isEqualTo("alex.dev@example.com");
        assertThat(userDetails.getUsername()).isEqualTo("alexdev");
        assertThat(userDetails.getPassword()).isEqualTo(user.getPasswordHash());
    }

    @Test
    @DisplayName("Returns empty authorities collection")
    void getAuthorities_returnsEmptyCollection() {
        User user = createTestUser(AccountStatus.ACTIVE);
        DevFlowUserDetails userDetails = new DevFlowUserDetails(user);

        assertThat(userDetails.getAuthorities()).isEmpty();
    }

    @Nested
    @DisplayName("AccountStatus to UserDetails Flags Mapping")
    class AccountStatusFlagsMapping {

        @Test
        @DisplayName("ACTIVE status: enabled=true, nonLocked=true, nonExpired=true, credentialsNonExpired=true")
        void activeStatus_allFlagsTrue() {
            User user = createTestUser(AccountStatus.ACTIVE);
            DevFlowUserDetails userDetails = new DevFlowUserDetails(user);

            assertThat(userDetails.isEnabled()).isTrue();
            assertThat(userDetails.isAccountNonLocked()).isTrue();
            assertThat(userDetails.isAccountNonExpired()).isTrue();
            assertThat(userDetails.isCredentialsNonExpired()).isTrue();
        }

        @Test
        @DisplayName("INACTIVE status: enabled=false, nonExpired=false")
        void inactiveStatus_disabledAndExpired() {
            User user = createTestUser(AccountStatus.INACTIVE);
            DevFlowUserDetails userDetails = new DevFlowUserDetails(user);

            assertThat(userDetails.isEnabled()).isFalse();
            assertThat(userDetails.isAccountNonExpired()).isFalse();
        }

        @Test
        @DisplayName("LOCKED status: enabled=false, nonLocked=false")
        void lockedStatus_disabledAndLocked() {
            User user = createTestUser(AccountStatus.LOCKED);
            DevFlowUserDetails userDetails = new DevFlowUserDetails(user);

            assertThat(userDetails.isEnabled()).isFalse();
            assertThat(userDetails.isAccountNonLocked()).isFalse();
        }

        @Test
        @DisplayName("SUSPENDED status: enabled=false, nonLocked=false")
        void suspendedStatus_disabledAndLocked() {
            User user = createTestUser(AccountStatus.SUSPENDED);
            DevFlowUserDetails userDetails = new DevFlowUserDetails(user);

            assertThat(userDetails.isEnabled()).isFalse();
            assertThat(userDetails.isAccountNonLocked()).isFalse();
        }

        @Test
        @DisplayName("PENDING_VERIFICATION status: enabled=false, nonLocked=true")
        void pendingVerificationStatus_disabledButNotLocked() {
            User user = createTestUser(AccountStatus.PENDING_VERIFICATION);
            DevFlowUserDetails userDetails = new DevFlowUserDetails(user);

            assertThat(userDetails.isEnabled()).isFalse();
            assertThat(userDetails.isAccountNonLocked()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(AccountStatus.class)
        @DisplayName("isCredentialsNonExpired always returns true for all statuses")
        void isCredentialsNonExpired_alwaysTrue(AccountStatus status) {
            User user = createTestUser(status);
            DevFlowUserDetails userDetails = new DevFlowUserDetails(user);

            assertThat(userDetails.isCredentialsNonExpired()).isTrue();
        }
    }
}
