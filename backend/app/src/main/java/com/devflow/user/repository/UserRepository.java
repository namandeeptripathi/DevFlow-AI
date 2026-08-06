package com.devflow.user.repository;

import com.devflow.user.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for the {@link User} entity.
 *
 * <p>Provides data access operations for user identity lookups, uniqueness checks,
 * and persistence within the authentication boundary.
 *
 * @see User
 * @see com.devflow.security.user.CustomUserDetailsService
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Finds a user by their unique email address.
     *
     * @param email the email address to query
     * @return an {@link Optional} containing the user if found, or empty if not
     */
    Optional<User> findByEmail(String email);

    /**
     * Finds a user by their unique username.
     *
     * @param username the username to query
     * @return an {@link Optional} containing the user if found, or empty if not
     */
    Optional<User> findByUsername(String username);

    /**
     * Checks if a user exists with the specified email address.
     *
     * @param email the email address to check
     * @return {@code true} if a user exists with the email; {@code false} otherwise
     */
    boolean existsByEmail(String email);

    /**
     * Checks if a user exists with the specified username.
     *
     * @param username the username to check
     * @return {@code true} if a user exists with the username; {@code false} otherwise
     */
    boolean existsByUsername(String username);

    /**
     * Finds a user matching either an email address or a username.
     * Useful for login resolvers that accept either identifier in a single field.
     *
     * @param email the email address to match
     * @param username the username to match
     * @return an {@link Optional} containing the user if found, or empty if not
     */
    Optional<User> findByEmailOrUsername(String email, String username);

    /**
     * Searches users by username or profile display name using case-insensitive partial matching.
     * Optionally includes email matching when {@code includeEmail} is {@code true} (prepared for future RBAC).
     *
     * @param query the partial search term
     * @param includeEmail whether email matching should be evaluated (false for public search)
     * @param pageable pagination and sorting parameters
     * @return a paged result set of matching {@link User} identities
     */
    @Query("""
        SELECT DISTINCT u FROM User u
        LEFT JOIN u.profile p
        WHERE (LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%'))
           OR LOWER(p.displayName) LIKE LOWER(CONCAT('%', :query, '%'))
           OR (:includeEmail = true AND LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%'))))
        """)
    Page<User> searchUsers(
            @Param("query") String query,
            @Param("includeEmail") boolean includeEmail,
            Pageable pageable
    );
}
