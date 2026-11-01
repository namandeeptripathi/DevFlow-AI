package com.devflow.modules.auth.domain;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable value object representing a fine-grained authorization permission.
 *
 * <p>All permissions follow the canonical {@code {resource}.{action}} naming pattern
 * established in the DevFlow Authorization Model specification (§6.1).
 *
 * <h2>Documented Permission Grammar</h2>
 * <pre>{@code
 * permission  ::= resource '.' action
 * resource    ::= segment
 * action      ::= segment ('.' segment)*
 * segment     ::= [a-z0-9][a-z0-9_-]*
 * }</pre>
 *
 * <p>Identifiers are normalized using {@link Locale#ROOT} to prevent locale-sensitive casing anomalies.
 * Composite action identifiers (e.g. {@code review.approve} in {@code ai.review.approve}) are fully preserved.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code project.read}</li>
 *   <li>{@code project.write}</li>
 *   <li>{@code task.create}</li>
 *   <li>{@code repo.connect}</li>
 *   <li>{@code ai.review.approve}</li>
 * </ul>
 *
 * @param resource the domain resource governed by this permission (e.g., "project", "task", "repo")
 * @param action   the specific operation permitted on the resource (e.g., "read", "create", "review.approve")
 *
 * @see Role
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §6</a>
 */
public record Permission(String resource, String action) implements Serializable {

    private static final Pattern RESOURCE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");
    private static final Pattern ACTION_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]*(\\.[a-z0-9][a-z0-9_-]*)*$");

    public Permission {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(action, "action must not be null");

        resource = resource.trim().toLowerCase(Locale.ROOT);
        action = action.trim().toLowerCase(Locale.ROOT);

        if (resource.isEmpty()) {
            throw new IllegalArgumentException("resource must not be blank");
        }
        if (action.isEmpty()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        if (!RESOURCE_PATTERN.matcher(resource).matches()) {
            throw new IllegalArgumentException(
                    "Resource identifier '" + resource + "' does not match grammar: [a-z0-9][a-z0-9_-]*");
        }
        if (!ACTION_PATTERN.matcher(action).matches()) {
            throw new IllegalArgumentException(
                    "Action identifier '" + action + "' does not match grammar: [a-z0-9][a-z0-9_-]*(\\.[a-z0-9][a-z0-9_-]*)*");
        }
    }

    /**
     * Creates a {@link Permission} from explicit resource and action identifiers.
     *
     * @param resource the target resource name (e.g., "project")
     * @param action   the permitted action (e.g., "read", "review.approve")
     * @return an immutable permission instance
     */
    public static Permission of(String resource, String action) {
        return new Permission(resource, action);
    }

    /**
     * Parses a permission from its canonical string format ({@code {resource}.{action}}).
     *
     * <p>The first dot separator splits the string into the {@code resource} identifier
     * and the {@code action} identifier, supporting composite action segments.
     *
     * @param permissionString the string to parse (e.g., "project.read", "ai.review.approve")
     * @return an immutable permission instance
     * @throws IllegalArgumentException if the string does not conform to the documented grammar
     */
    public static Permission of(String permissionString) {
        Objects.requireNonNull(permissionString, "permissionString must not be null");
        String trimmed = permissionString.trim();
        int dotIndex = trimmed.indexOf('.');
        if (dotIndex <= 0 || dotIndex == trimmed.length() - 1) {
            throw new IllegalArgumentException(
                    "Permission string must follow '{resource}.{action}' format, got: " + permissionString);
        }
        return new Permission(trimmed.substring(0, dotIndex), trimmed.substring(dotIndex + 1));
    }

    /**
     * Returns the canonical string representation of this permission ({@code {resource}.{action}}).
     *
     * @return the permission string
     */
    public String value() {
        return resource + "." + action;
    }

    @Override
    public String toString() {
        return value();
    }
}
