package com.devflow.user.repository;

import com.devflow.user.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for the {@link UserProfile} entity.
 *
 * <p>Provides data access operations for retrieving and checking the existence
 * of user profile records within the User domain boundary.
 *
 * @see UserProfile
 */
@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    /**
     * Finds the profile associated with the specified user ID.
     *
     * @param userId the UUID of the owning {@link com.devflow.user.domain.User}
     * @return an {@link Optional} containing the profile if found, or empty if not
     */
    Optional<UserProfile> findByUserId(UUID userId);

    /**
     * Checks if a profile record exists for the specified user ID.
     *
     * @param userId the UUID of the owning {@link com.devflow.user.domain.User}
     * @return {@code true} if a profile exists for the user; {@code false} otherwise
     */
    boolean existsByUserId(UUID userId);
}
