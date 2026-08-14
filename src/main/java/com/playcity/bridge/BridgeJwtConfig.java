package com.playcity.bridge;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

record BridgeJwtConfig(
    boolean enabled,
    String issue,
    String issuer,
    String audience,
    String jwksUrl,
    Path jwksFile,
    String algorithm,
    String requiredType,
    String scopeClaim,
    String clientIdClaim,
    String actionScopePrefix,
    String eventScopePrefix,
    Set<String> allowedActions,
    Set<String> allowedEvents,
    int requestsPerMinute,
    int maxConcurrent,
    int maxEventConnections,
    int clockSkewSeconds,
    int maxLifetimeSeconds,
    int maxTokenChars,
    int maxIdentities,
    long identityTtlMillis
) {
    static BridgeJwtConfig from(FileConfiguration config, Path dataDirectory) {
        boolean enabled = config.getBoolean("jwt.enabled", false);
        if (!enabled) {
            return disabled();
        }
        String issue = "";
        String issuer = trimmed(config.getString("jwt.issuer"));
        String audience = trimmed(config.getString("jwt.audience"));
        String jwksUrl = trimmed(config.getString("jwt.jwksUrl"));
        String rawJwksFile = trimmed(config.getString("jwt.jwksFile"));
        boolean allowInsecureLoopback = config.getBoolean("jwt.allowInsecureLoopbackJwks", false);
        Path jwksFile = null;

        if (issuer.isBlank() || issuer.length() > 512) {
            issue = firstIssue(issue, "jwt_issuer_invalid");
        }
        if (audience.isBlank() || audience.length() > 256) {
            issue = firstIssue(issue, "jwt_audience_invalid");
        }
        if (jwksUrl.isBlank() == rawJwksFile.isBlank()) {
            issue = firstIssue(issue, "jwt_jwks_source_required");
        } else if (!jwksUrl.isBlank() && !validJwksUrl(jwksUrl, allowInsecureLoopback)) {
            issue = firstIssue(issue, "jwt_jwks_url_invalid");
        } else if (!rawJwksFile.isBlank()) {
            try {
                Path configured = Path.of(rawJwksFile);
                Path dataRoot = dataDirectory.toAbsolutePath().normalize();
                jwksFile = configured.isAbsolute()
                    ? configured.toAbsolutePath().normalize()
                    : dataRoot.resolve(configured).normalize();
                if (!jwksFile.startsWith(dataRoot)
                    || containsSymbolicLink(jwksFile)
                    || !Files.isRegularFile(jwksFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(jwksFile) <= 0L
                    || Files.size(jwksFile) > 262_144L) {
                    issue = firstIssue(issue, "jwt_jwks_file_invalid");
                }
            } catch (Exception exception) {
                issue = firstIssue(issue, "jwt_jwks_file_invalid");
            }
        }

        String algorithm = nonBlank(config.getString("jwt.algorithm"), "RS256");
        if (!Set.of("RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512", "EdDSA")
            .contains(algorithm)) {
            issue = firstIssue(issue, "jwt_algorithm_invalid");
        }
        String requiredType = nonBlank(config.getString("jwt.requiredType"), "at+jwt");
        if (!requiredType.matches("[A-Za-z0-9+._-]{1,32}")) {
            issue = firstIssue(issue, "jwt_type_invalid");
        }
        String scopeClaim = nonBlank(config.getString("jwt.scopeClaim"), "scope");
        String clientIdClaim = nonBlank(config.getString("jwt.clientIdClaim"), "client_id");
        if (!claimName(scopeClaim) || !claimName(clientIdClaim)) {
            issue = firstIssue(issue, "jwt_claim_name_invalid");
        }
        String actionPrefix = nonBlank(config.getString("jwt.actionScopePrefix"), "bridge:action:");
        String eventPrefix = nonBlank(config.getString("jwt.eventScopePrefix"), "bridge:event:");
        if (!scopePrefix(actionPrefix) || !scopePrefix(eventPrefix) || actionPrefix.equals(eventPrefix)) {
            issue = firstIssue(issue, "jwt_scope_prefix_invalid");
        }

        Set<String> allowedActions = rules(config.getList("jwt.allowedActions"));
        Set<String> allowedEvents = rules(config.getList("jwt.allowedEvents"));
        if (containsInvalidRule(config.getList("jwt.allowedActions"))
            || containsInvalidRule(config.getList("jwt.allowedEvents"))) {
            issue = firstIssue(issue, "jwt_local_scope_invalid");
        }

        return new BridgeJwtConfig(
            true,
            issue,
            issuer,
            audience,
            jwksUrl,
            jwksFile,
            algorithm,
            requiredType,
            scopeClaim,
            clientIdClaim,
            actionPrefix,
            eventPrefix,
            allowedActions,
            allowedEvents,
            clamp(config.getInt("jwt.requestsPerMinute", 120), 1, 60_000),
            clamp(config.getInt("jwt.maxConcurrent", 4), 1, 64),
            clamp(config.getInt("jwt.maxEventConnections", 2), 0, 16),
            clamp(config.getInt("jwt.clockSkewSeconds", 30), 0, 300),
            clamp(config.getInt("jwt.maxLifetimeSeconds", 3600), 60, 86_400),
            clamp(config.getInt("jwt.maxTokenChars", 8192), 512, 32_768),
            clamp(config.getInt("jwt.maxIdentities", 4096), 16, 100_000),
            clamp(config.getLong("jwt.identityTtlSeconds", 900L), 60L, 86_400L) * 1000L
        );
    }

    boolean valid() {
        return enabled && issue.isBlank();
    }

    private static BridgeJwtConfig disabled() {
        return new BridgeJwtConfig(
            false, "", "", "", "", null, "RS256", "at+jwt", "scope", "client_id",
            "bridge:action:", "bridge:event:", Set.of(), Set.of(), 120, 4, 2, 30, 3600, 8192, 4096, 900_000L
        );
    }

    private static boolean validJwksUrl(String value, boolean allowInsecureLoopback) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                return false;
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return true;
            }
            return allowInsecureLoopback
                && "http".equalsIgnoreCase(uri.getScheme())
                && BridgeSecurityPolicy.isLoopbackHost(uri.getHost());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean containsSymbolicLink(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path segment : absolute) {
            current = current == null ? segment : current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> rules(java.util.List<?> values) {
        if (values == null) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof String string) {
                String normalized = string.trim().toLowerCase(Locale.ROOT);
                if (BridgeScopeRules.validRule(normalized)) {
                    result.add(normalized);
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static boolean containsInvalidRule(java.util.List<?> values) {
        if (values == null) {
            return false;
        }
        for (Object value : values) {
            if (!(value instanceof String string)
                || !BridgeScopeRules.validRule(string.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean claimName(String value) {
        return value.matches("[A-Za-z0-9_.:-]{1,64}");
    }

    private static boolean scopePrefix(String value) {
        return value.matches("[A-Za-z0-9_.:-]{1,64}");
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nonBlank(String value, String fallback) {
        String normalized = trimmed(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String firstIssue(String current, String candidate) {
        return current.isBlank() ? candidate : current;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
