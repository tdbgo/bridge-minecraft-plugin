package com.playcity.bridge;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class BridgeScopeRules {
    private BridgeScopeRules() {
    }

    static boolean matches(Set<String> rules, String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return rules.stream().anyMatch(rule -> matches(rule, normalized));
    }

    static boolean validRule(String value) {
        return value != null && value.matches("\\*|[a-z0-9][a-z0-9_.:-]{0,127}\\*?");
    }

    static boolean requestedRulesAllowed(Set<String> requested, Set<String> allowed) {
        return requested.stream().allMatch(rule -> allowed.stream().anyMatch(candidate -> contains(candidate, rule)));
    }

    static Set<String> intersect(Set<String> left, Set<String> right) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String first : left) {
            for (String second : right) {
                String overlap = overlap(first, second);
                if (overlap != null) {
                    result.add(overlap);
                }
            }
        }
        return Set.copyOf(result);
    }

    private static boolean matches(String rule, String name) {
        return "*".equals(rule)
            || rule.equals(name)
            || (rule.endsWith("*") && name.startsWith(rule.substring(0, rule.length() - 1)));
    }

    private static boolean contains(String allowed, String requested) {
        if ("*".equals(allowed)) {
            return true;
        }
        if (!requested.endsWith("*")) {
            return matches(allowed, requested);
        }
        if (!allowed.endsWith("*")) {
            return false;
        }
        return requested.substring(0, requested.length() - 1)
            .startsWith(allowed.substring(0, allowed.length() - 1));
    }

    private static String overlap(String first, String second) {
        if (contains(first, second)) {
            return second;
        }
        if (contains(second, first)) {
            return first;
        }
        return null;
    }
}
