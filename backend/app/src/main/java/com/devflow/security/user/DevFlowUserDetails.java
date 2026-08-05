package com.devflow.security.user;

import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

/**
 * Custom {@link UserDetails} implementation wrapping the DevFlow {@link User} entity.
 *
 * <p>Bridges the domain user model with Spring Security's authentication engine:
 * <ul>
 *   <li>Exposes credentials ({@code passwordHash}) and identifier ({@code email} / {@code username}).</li>
 *   <li>Maps {@link AccountStatus} to Spring Security lifecycle flags.</li>
 *   <li>Returns an empty authority collection for this phase (roles and permissions are added in RBAC phase).</li>
 * </ul>
 *
 * @see User
 * @see AccountStatus
 * @see CustomUserDetailsService
 */
public class DevFlowUserDetails implements UserDetails {

    private final User user;

    public DevFlowUserDetails(User user) {
        this.user = Objects.requireNonNull(user, "User entity must not be null");
    }

    /**
     * Gets the wrapped domain {@link User} entity.
     *
     * @return the underlying user entity
     */
    public User getUser() {
        return user;
    }

    /**
     * Gets the unique identifier of the domain user.
     *
     * @return the user's UUID
     */
    public UUID getId() {
        return user.getId();
    }

    /**
     * Gets the verified email address of the user.
     *
     * @return the email address string
     */
    public String getEmail() {
        return user.getEmail();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Roles and permissions will be implemented in subsequent RBAC authorization phase.
        return Collections.emptyList();
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        // Return username (or email if username is blank)
        return user.getUsername() != null ? user.getUsername() : user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return user.getAccountStatus() != null && user.getAccountStatus().isAccountNonExpired();
    }

    @Override
    public boolean isAccountNonLocked() {
        return user.getAccountStatus() != null && user.getAccountStatus().isAccountNonLocked();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.getAccountStatus() != null && user.getAccountStatus().isEnabled();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DevFlowUserDetails that = (DevFlowUserDetails) o;
        return Objects.equals(user.getId(), that.user.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(user.getId());
    }
}
