package com.playcity.bridge.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Authenticated origin of an action request.
 *
 * @param id privacy-safe caller identifier
 * @param type authentication or transport origin
 * @param allowedActions local action-scope rules
 */
public record BridgeCaller(String id, Type type, Set<String> allowedActions) {
    /** Authentication or transport origin. */
    public enum Type {
        /** Static HTTP service credential. */
        HTTP,
        /** Signed JWT access token. */
        JWT,
        /** Configured outbound relay identity. */
        RELAY,
        /** Another plugin invoking the API locally. */
        LOCAL_PLUGIN,
        /** Bridge-internal trusted call. */
        INTERNAL
    }

    /** Validates the caller identifier and normalizes action scopes. */
    public BridgeCaller {
        id = Objects.requireNonNull(id, "id").trim();
        type = Objects.requireNonNull(type, "type");
        if (!id.matches("[A-Za-z0-9_.:-]{1,80}")) {
            throw new IllegalArgumentException("Invalid Bridge caller id.");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String action : Objects.requireNonNull(allowedActions, "allowedActions")) {
            if (action != null && !action.isBlank()) {
                normalized.add(action.trim().toLowerCase(Locale.ROOT));
            }
        }
        allowedActions = Collections.unmodifiableSet(normalized);
    }

    /**
     * Creates a trusted internal caller with unrestricted action scope.
     *
     * @param id privacy-safe internal caller identifier
     * @return internal caller
     */
    public static BridgeCaller internal(String id) {
        return new BridgeCaller(id, Type.INTERNAL, Set.of("*"));
    }

    /**
     * Tests an action name against this caller's exact and prefix rules.
     *
     * @param actionName action name to test
     * @return whether the action is permitted
     */
    public boolean allows(String actionName) {
        if (actionName == null) {
            return false;
        }
        String normalized = actionName.trim().toLowerCase(Locale.ROOT);
        for (String rule : allowedActions) {
            if ("*".equals(rule) || rule.equals(normalized)) {
                return true;
            }
            if (rule.endsWith("*") && normalized.startsWith(rule.substring(0, rule.length() - 1))) {
                return true;
            }
        }
        return false;
    }
}
