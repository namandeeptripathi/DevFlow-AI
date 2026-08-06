package com.devflow.user.service;

import com.devflow.user.domain.User;
import com.devflow.user.domain.UserProfile;
import com.devflow.user.dto.UserSearchResponse;
import com.devflow.user.exception.InvalidSearchQueryException;
import com.devflow.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserSearchService")
class UserSearchServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserSearchService searchService;
    private User testUser1;
    private User testUser2;

    @BeforeEach
    void setUp() {
        searchService = new UserSearchService(userRepository);

        UUID user1Id = UUID.randomUUID();
        UserProfile profile1 = UserProfile.builder().displayName("Aman Sharma").avatarUrl("http://localhost/a1.png").build();
        testUser1 = User.builder().id(user1Id).username("amansh").profile(profile1).build();

        UUID user2Id = UUID.randomUUID();
        UserProfile profile2 = UserProfile.builder().displayName("Amanda Waller").avatarUrl("http://localhost/a2.png").build();
        testUser2 = User.builder().id(user2Id).username("amanda").profile(profile2).build();
    }

    @Nested
    @DisplayName("Search Execution & Pagination")
    class SearchExecution {

        @Test
        @DisplayName("searchUsers performs partial search and returns paged response with public fields")
        void searchUsers_returnsPagedResponse() {
            Pageable pageable = PageRequest.of(0, 20, Sort.by("username"));
            Page<User> mockPage = new PageImpl<>(List.of(testUser1, testUser2), pageable, 2);

            when(userRepository.searchUsers(eq("aman"), eq(false), eq(pageable))).thenReturn(mockPage);

            UserSearchResponse response = searchService.searchUsers("aman", pageable);

            assertThat(response.getContent()).hasSize(2);
            assertThat(response.getContent().get(0).getUsername()).isEqualTo("amansh");
            assertThat(response.getContent().get(0).getDisplayName()).isEqualTo("Aman Sharma");
            assertThat(response.getContent().get(1).getUsername()).isEqualTo("amanda");
            assertThat(response.getTotalElements()).isEqualTo(2);

            verify(userRepository).searchUsers("aman", false, pageable);
        }

        @Test
        @DisplayName("searchUsers handles users without a profile safely (null displayName and avatarUrl)")
        void searchUsers_handlesUserWithoutProfile() {
            User userNoProfile = User.builder().id(UUID.randomUUID()).username("noprofile").profile(null).build();
            Pageable pageable = PageRequest.of(0, 10);
            Page<User> mockPage = new PageImpl<>(List.of(userNoProfile), pageable, 1);

            when(userRepository.searchUsers(eq("noprofile"), anyBoolean(), eq(pageable))).thenReturn(mockPage);

            UserSearchResponse response = searchService.searchUsers("noprofile", pageable);

            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getContent().get(0).getUsername()).isEqualTo("noprofile");
            assertThat(response.getContent().get(0).getDisplayName()).isNull();
            assertThat(response.getContent().get(0).getAvatarUrl()).isNull();
        }
    }

    @Nested
    @DisplayName("Search Validation")
    class SearchValidation {

        @Test
        @DisplayName("searchUsers throws InvalidSearchQueryException when query is blank")
        void searchUsers_blankQuery_throwsException() {
            Pageable pageable = PageRequest.of(0, 20);

            assertThatThrownBy(() -> searchService.searchUsers("   ", pageable))
                    .isInstanceOf(InvalidSearchQueryException.class)
                    .hasMessageContaining("query must not be blank");
        }

        @Test
        @DisplayName("searchUsers throws InvalidSearchQueryException when query length exceeds 100")
        void searchUsers_longQuery_throwsException() {
            Pageable pageable = PageRequest.of(0, 20);
            String longQuery = "a".repeat(101);

            assertThatThrownBy(() -> searchService.searchUsers(longQuery, pageable))
                    .isInstanceOf(InvalidSearchQueryException.class)
                    .hasMessageContaining("cannot exceed 100 characters");
        }

        @Test
        @DisplayName("searchUsers throws InvalidSearchQueryException when page size exceeds 100")
        void searchUsers_oversizedPageSize_throwsException() {
            Pageable pageable = PageRequest.of(0, 101);

            assertThatThrownBy(() -> searchService.searchUsers("aman", pageable))
                    .isInstanceOf(InvalidSearchQueryException.class)
                    .hasMessageContaining("exceeds maximum allowed limit of 100");
        }

        @Test
        @DisplayName("searchUsers throws InvalidSearchQueryException when sorting by disallowed field")
        void searchUsers_disallowedSortProperty_throwsException() {
            Pageable pageable = PageRequest.of(0, 20, Sort.by("passwordHash"));

            assertThatThrownBy(() -> searchService.searchUsers("aman", pageable))
                    .isInstanceOf(InvalidSearchQueryException.class)
                    .hasMessageContaining("Invalid sort property");
        }
    }
}
