package com.devflow.user.repository;

import com.devflow.user.domain.UserPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for the {@link UserPreferences} entity.
 *
 * <p>Provides data access operations for retrieving and checking the existence
 * of user preferences records within the User domain boundary.
 *
 * @see UserPreferences
 */
@Repository
public interface UserPreferencesRepository extends JpaRepository<UserPreferences, UUID> {

    /**
     * Finds the preferences associated with the specified user ID.
     *
     * @param userId the UUID of the owning {@link com.devflow.user.domain.User}
     * @return an {@link Optional} containing the preferences if found, or empty if not
     */
    Optional<UserPreferences> findByUserId(UUID userId);

    /**
     * Checks if a preferences record exists for the specified user ID.
     *
     * @param userId the UUID of the owning {@link com.devflow.user.domain.User}
     * @return {@code true} if preferences exist for the user; {@code false} otherwise
     */
    boolean existsByUserId(UUID userId);
}
