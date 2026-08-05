package com.devflow.security;

import com.devflow.common.ApiPaths;
import com.devflow.config.SecurityProperties;
import com.devflow.security.jwt.JwtAuthenticationEntryPoint;
import com.devflow.security.jwt.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Production Spring Security 6 configuration for the DevFlow backend.
 *
 * <p>This class establishes the Security Filter Chain infrastructure without
 * implementing authentication. It prepares the application for JWT-based RS256
 * authentication (Authentication Strategy §5) which will be wired in the next phase.
 *
 * <h2>Design decisions</h2>
 * <dl>
 *   <dt>Stateless sessions</dt>
 *   <dd>No server-side session state. JWT carries all identity claims per request.
 *       Aligns with Authentication Strategy §5.1 and §9 (Session Management).</dd>
 *
 *   <dt>CSRF disabled</dt>
 *   <dd>CSRF attacks require a browser session cookie. Stateless JWT APIs do not
 *       use cookies for authentication; CSRF protection is therefore inapplicable
 *       and would add unnecessary friction to all API consumers (web, CLI, extension).</dd>
 *
 *   <dt>HTTP Basic and Form Login disabled</dt>
 *   <dd>DevFlow is a REST API. It never redirects to a login page and never accepts
 *       HTTP Basic credentials. The only permitted authentication mechanism is
 *       Bearer JWT (implemented in the next phase).</dd>
 *
 *   <dt>401 on unauthenticated access</dt>
 *   <dd>The custom {@link HttpStatusEntryPoint} returns 401 Unauthorized (not a redirect)
 *       for unauthenticated requests. REST API clients must handle 401 — never a redirect.</dd>
 *
 *   <dt>Method security enabled</dt>
 *   <dd>{@code @EnableMethodSecurity(prePostEnabled = true)} activates {@code @PreAuthorize}
 *       and {@code @PostAuthorize} annotations used for domain-level RBAC checks as
 *       defined in Authorization Model §2.</dd>
 *
 *   <dt>Configuration-driven CORS</dt>
 *   <dd>Allowed origins are never hardcoded. They are loaded from
 *       {@link SecurityProperties.Cors} per Configuration Strategy §2.1.</dd>
 * </dl>
 *
 * @see SecurityProperties
 * @see PasswordEncoderConfiguration
 * @see ApiPaths
 * @see <a href="../../../../docs/security/AUTHENTICATION_STRATEGY.md">Authentication Strategy</a>
 * @see <a href="../../../../docs/security/AUTHORIZATION_MODEL.md">Authorization Model §2</a>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfiguration {

    private final SecurityProperties securityProperties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    public SecurityConfiguration(
            SecurityProperties securityProperties,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint
    ) {
        this.securityProperties = securityProperties;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
    }

    /**
     * The primary Security Filter Chain.
     *
     * <p>Defines the authorization rules for all incoming HTTP requests:
     * <ul>
     *   <li>Paths in {@link ApiPaths#PUBLIC_PATHS} are accessible without credentials.</li>
     *   <li>Every other path requires a valid authenticated principal.</li>
     * </ul>
     *
     * <p>Validates JWT bearer tokens via {@link JwtAuthenticationFilter} and handles
     * unauthenticated request errors using {@link JwtAuthenticationEntryPoint}.
     *
     * @param http the Spring Security {@link HttpSecurity} builder
     * @return the fully configured {@link SecurityFilterChain}
     * @throws Exception if the configuration fails (propagated from Spring Security internals)
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // ── Session management ────────────────────────────────────────
                // STATELESS: no HttpSession is created or consulted.
                // All authentication state is carried in the JWT bearer token.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                // ── CSRF ──────────────────────────────────────────────────────
                // Disabled for stateless REST APIs. See class-level Javadoc.
                .csrf(AbstractHttpConfigurer::disable)
                // ── CORS ──────────────────────────────────────────────────────
                // Delegates to corsConfigurationSource() bean.
                // Origins, methods, and headers are configuration-driven.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // ── Disable legacy authentication mechanisms ──────────────────
                // DevFlow never uses HTTP Basic or server-rendered form login.
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // ── Authorization rules ───────────────────────────────────────
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ApiPaths.PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated()
                )
                // ── Exception handling ─────────────────────────────────────────
                // Return 401 Unauthorized JSON response via JwtAuthenticationEntryPoint
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                )
                // ── JWT Filter ────────────────────────────────────────────────
                // Inspects Bearer token before UsernamePasswordAuthenticationFilter
                .addFilterBefore(jwtAuthenticationFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * CORS configuration source bean.
     *
     * <p>Reads all CORS settings from {@link SecurityProperties.Cors} —
     * no origins, methods, or headers are hardcoded here.
     *
     * <p>Applies to all URL patterns ({@code /**}). Individual controllers may
     * further restrict CORS behaviour via {@code @CrossOrigin} if needed.
     *
     * @return the configured {@link CorsConfigurationSource}
     * @see SecurityProperties.Cors
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        SecurityProperties.Cors corsProps = securityProperties.getCors();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProps.getAllowedOrigins());
        config.setAllowedMethods(corsProps.getAllowedMethods());
        config.setAllowedHeaders(corsProps.getAllowedHeaders());
        config.setExposedHeaders(corsProps.getExposedHeaders());
        config.setAllowCredentials(corsProps.isAllowCredentials());
        config.setMaxAge(corsProps.getMaxAgeSecs());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Exposes the {@link AuthenticationManager} as a Spring-managed bean.
     *
     * <p>Serves two purposes:
     * <ol>
     *   <li><strong>JWT filter readiness:</strong> The JWT authentication filter and OAuth
     *       grant handlers require an {@link AuthenticationManager} as a constructor
     *       dependency. Exposing it now avoids a structural change to this configuration
     *       class when authentication is wired in the next phase.</li>
     *   <li><strong>Auto-configuration suppression:</strong> By providing an
     *       {@link AuthenticationManager} bean, Spring Boot's
     *       {@code UserDetailsServiceAutoConfiguration} is suppressed. This prevents the
     *       default in-memory user with a random password from being created, eliminating
     *       the security warning at startup.</li>
     * </ol>
     *
     * @param configuration Spring Security's {@link AuthenticationConfiguration}
     * @return the application-wide {@link AuthenticationManager}
     * @throws Exception if the manager cannot be created
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }
}
