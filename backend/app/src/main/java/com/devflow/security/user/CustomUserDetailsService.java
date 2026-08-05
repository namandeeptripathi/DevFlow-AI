package com.devflow.security.user;

import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Production {@link UserDetailsService} implementation for the DevFlow platform.
 *
 * <p>Loads identity principal records from PostgreSQL via {@link UserRepository} during
 * authentication flows. Supports looking up users by either unique email address or username.
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Constructor injection only — no field injection.</li>
 *   <li>Throws {@link UsernameNotFoundException} when no matching user is found.</li>
 *   <li>Read-only transaction semantics for optimal performance.</li>
 * </ul>
 *
 * @see DevFlowUserDetails
 * @see UserRepository
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {
        if (usernameOrEmail == null || usernameOrEmail.trim().isEmpty()) {
            throw new UsernameNotFoundException("User identifier must not be empty");
        }

        String identifier = usernameOrEmail.trim();

        User user = userRepository.findByEmailOrUsername(identifier, identifier)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found with username or email: " + identifier
                ));

        return new DevFlowUserDetails(user);
    }
}
