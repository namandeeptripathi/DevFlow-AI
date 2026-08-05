package com.devflow.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password encoding configuration.
 *
 * <p>Isolated in its own {@link Configuration} class to avoid circular dependency:
 * when the {@code devflow-auth} domain module is scaffolded, it will import
 * {@link PasswordEncoder} for password hashing without depending on the entire
 * {@link SecurityConfiguration} (which owns the Security Filter Chain).
 *
 * <p>BCrypt is selected per Authentication Strategy §7.1:
 * <ul>
 *   <li>Adaptive cost factor — increasing {@code strength} slows brute-force
 *       proportionally to hardware improvements without invalidating existing hashes.</li>
 *   <li>Per-hash random salt — prevents rainbow-table attacks and ensures two identical
 *       passwords produce different stored hashes.</li>
 *   <li>Industry-standard — auditable, widely tested, supported by Spring Security.</li>
 * </ul>
 *
 * <p><strong>Cost factor choice:</strong> Strength 12 produces ≈250–400 ms per hash on
 * modern hardware. This is the production-safe minimum recommended by OWASP for BCrypt.
 * It renders online brute-force infeasible while remaining acceptable UX on login.
 *
 * @see <a href="../../../../docs/security/AUTHENTICATION_STRATEGY.md">Authentication Strategy §7</a>
 */
@Configuration
public class PasswordEncoderConfiguration {

    /**
     * BCrypt strength (cost factor).
     *
     * <p>Each increment doubles the computation time. Adjust upward as hardware
     * improves and the application's acceptable login latency allows.
     */
    private static final int BCRYPT_STRENGTH = 12;

    /**
     * Provides the application-wide {@link PasswordEncoder} bean.
     *
     * <p>All password hashing in DevFlow routes through this single bean.
     * Never instantiate {@link BCryptPasswordEncoder} directly in domain code.
     *
     * @return a {@link BCryptPasswordEncoder} configured with strength {@value #BCRYPT_STRENGTH}
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }
}
