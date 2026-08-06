package com.devflow.user.service;

import com.devflow.user.domain.User;
import com.devflow.user.domain.UserProfile;
import com.devflow.user.dto.UserSearchResponse;
import com.devflow.user.dto.UserSearchResult;
import com.devflow.user.exception.InvalidSearchQueryException;
import com.devflow.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Domain service managing user search operations across the platform.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Validate search query string length and pagination limits.</li>
 *   <li>Execute case-insensitive partial match queries against username and display name.</li>
 *   <li>Map domain identity entities to public-safe {@link UserSearchResult} objects.</li>
 * </ul>
 *
 * @see UserRepository
 * @see UserSearchResult
 * @see UserSearchResponse
 */
@Service
public class UserSearchService {

    private static final Logger log = LoggerFactory.getLogger(UserSearchService.class);

    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;

    public UserSearchService(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
    }

    /**
     * Executes a case-insensitive partial search by username or profile display name.
     *
     * @param query    the partial search string
     * @param pageable pagination and sorting parameters
     * @return {@link UserSearchResponse} wrapping the paged search results
     * @throws InvalidSearchQueryException if query is blank, exceeds 100 characters, or page size exceeds 100
     */
    @Transactional(readOnly = true)
    public UserSearchResponse searchUsers(String query, Pageable pageable) {
        return searchUsers(query, false, pageable);
    }

    /**
     * Executes a user search with optional email matching flag (prepared for future admin RBAC).
     *
     * @param query        the partial search string
     * @param includeEmail whether email address matching is enabled
     * @param pageable     pagination and sorting parameters
     * @return {@link UserSearchResponse} wrapping the paged search results
     * @throws InvalidSearchQueryException if query or pagination validation fails
     */
    @Transactional(readOnly = true)
    public UserSearchResponse searchUsers(String query, boolean includeEmail, Pageable pageable) {
        Objects.requireNonNull(pageable, "Pageable parameters must not be null");

        String trimmedQuery = validateAndNormaliseQuery(query);
        validatePageSize(pageable.getPageSize());
        validateSortProperties(pageable);

        log.debug("Executing user search for query [{}] (includeEmail={}) page [{}] size [{}]",
                trimmedQuery, includeEmail, pageable.getPageNumber(), pageable.getPageSize());

        Page<User> userPage = userRepository.searchUsers(trimmedQuery, includeEmail, pageable);
        Page<UserSearchResult> dtoPage = userPage.map(this::toSearchResult);

        log.info("User search for query [{}] yielded {} total matches across {} pages",
                trimmedQuery, dtoPage.getTotalElements(), dtoPage.getTotalPages());

        return UserSearchResponse.fromPage(dtoPage);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String validateAndNormaliseQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            throw new InvalidSearchQueryException("Search query must not be blank");
        }

        String trimmed = query.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw new InvalidSearchQueryException(
                    "Search query length cannot exceed " + MAX_QUERY_LENGTH + " characters");
        }

        return trimmed;
    }

    private void validatePageSize(int pageSize) {
        if (pageSize > MAX_PAGE_SIZE) {
            throw new InvalidSearchQueryException(
                    "Requested page size " + pageSize + " exceeds maximum allowed limit of " + MAX_PAGE_SIZE);
        }
    }

    private static final java.util.Set<String> ALLOWED_SORT_PROPERTIES = java.util.Set.of("username", "createdAt", "id");

    private void validateSortProperties(Pageable pageable) {
        if (pageable.getSort().isSorted()) {
            for (org.springframework.data.domain.Sort.Order order : pageable.getSort()) {
                if (!ALLOWED_SORT_PROPERTIES.contains(order.getProperty())) {
                    throw new InvalidSearchQueryException(
                            "Invalid sort property [" + order.getProperty() + "]. Allowed sort fields: " + ALLOWED_SORT_PROPERTIES);
                }
            }
        }
    }

    private UserSearchResult toSearchResult(User user) {
        UserProfile profile = user.getProfile();

        return UserSearchResult.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(profile != null ? profile.getDisplayName() : null)
                .avatarUrl(profile != null ? profile.getAvatarUrl() : null)
                .build();
    }
}
