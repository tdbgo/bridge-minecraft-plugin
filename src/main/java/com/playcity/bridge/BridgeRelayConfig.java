package com.playcity.bridge;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

public record BridgeRelayConfig(
    boolean enabled,
    URI baseUri,
    String serverName,
    Path identityFile,
    int connectTimeoutMillis,
    int pollTimeoutMillis,
    int actionTimeoutMillis,
    int minBackoffMillis,
    int maxBackoffMillis,
    int maxResponseBytes,
    Set<String> allowedActions,
    Set<String> allowedEvents,
    int eventQueueSize,
    int eventBatchSize,
    String issue
) {
    static BridgeRelayConfig from(FileConfiguration config, Path dataDirectory) {
        boolean enabled = config.getBoolean("relay.enabled", false);
        String rawBaseUrl = value(config.getString("relay.baseUrl"), "");
        boolean allowInsecureLoopback = config.getBoolean("relay.allowInsecureLoopback", false);
        URI baseUri = parseBaseUri(rawBaseUrl);
        String issue = validateBaseUri(baseUri, allowInsecureLoopback);

        String serverName = value(config.getString("relay.serverName"), "server-1");
        if (!serverName.matches("[A-Za-z0-9_.:-]{1,80}")) {
            issue = firstIssue(issue, "relay_server_name_invalid");
        }

        Path identityFile = resolveIdentityFile(
            value(config.getString("relay.identityFile"), "secrets/relay-identity.json"),
            dataDirectory
        );
        if (identityFile == null) {
            issue = firstIssue(issue, "relay_identity_path_invalid");
            identityFile = dataDirectory.resolve("secrets/relay-identity.json").normalize();
        }

        int connectTimeoutMillis = clamp(config.getInt("relay.connectTimeoutMillis", 5000), 1000, 30_000);
        int pollTimeoutMillis = clamp(config.getInt("relay.pollTimeoutMillis", 25_000), 5_000, 30_000);
        int actionTimeoutMillis = clamp(config.getInt("relay.actionTimeoutMillis", 7000), 1000, 60_000);
        int minBackoffMillis = clamp(config.getInt("relay.minBackoffMillis", 1000), 250, 30_000);
        int maxBackoffMillis = clamp(config.getInt("relay.maxBackoffMillis", 30_000), minBackoffMillis, 120_000);
        int maxResponseBytes = clamp(config.getInt("relay.maxResponseBytes", 262_144), 4096, 1_048_576);
        Set<String> allowedActions = new LinkedHashSet<>();
        if (config.isSet("relay.allowedActions")) {
            for (String rule : config.getStringList("relay.allowedActions")) {
                String normalized = rule == null ? "" : rule.trim().toLowerCase(Locale.ROOT);
                if (!normalized.matches("\\*|[a-z0-9][a-z0-9_.:-]{0,127}\\*?")) {
                    issue = firstIssue(issue, "relay_action_rule_invalid");
                } else {
                    allowedActions.add(normalized);
                }
            }
        } else {
            allowedActions.add("*");
        }
        Set<String> allowedEvents = new LinkedHashSet<>();
        if (config.isSet("relay.allowedEvents")) {
            for (String rule : config.getStringList("relay.allowedEvents")) {
                String normalized = rule == null ? "" : rule.trim().toLowerCase(Locale.ROOT);
                if (!normalized.matches("\\*|[a-z0-9][a-z0-9_.:-]{0,127}\\*?")) {
                    issue = firstIssue(issue, "relay_event_rule_invalid");
                } else {
                    allowedEvents.add(normalized);
                }
            }
        }
        int eventQueueSize = clamp(config.getInt("relay.eventQueueSize", 2048), 16, 100_000);
        int eventBatchSize = clamp(config.getInt("relay.eventBatchSize", 128), 1, eventQueueSize);

        return new BridgeRelayConfig(
            enabled,
            baseUri,
            serverName,
            identityFile,
            connectTimeoutMillis,
            pollTimeoutMillis,
            actionTimeoutMillis,
            minBackoffMillis,
            maxBackoffMillis,
            maxResponseBytes,
            Set.copyOf(allowedActions),
            Set.copyOf(allowedEvents),
            eventQueueSize,
            eventBatchSize,
            issue
        );
    }

    public boolean valid() {
        return issue.isBlank();
    }

    URI endpoint(String path) {
        String base = baseUri.toString().replaceAll("/+$", "");
        return URI.create(base + "/" + path.replaceFirst("^/+", ""));
    }

    boolean allowsEvent(String eventName) {
        return BridgeScopeRules.matches(allowedEvents, eventName);
    }

    private static URI parseBaseUri(String value) {
        try {
            URI uri = new URI(value.trim());
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), path.replaceAll("/+$", ""), null, null);
        } catch (URISyntaxException | RuntimeException exception) {
            return null;
        }
    }

    private static String validateBaseUri(URI uri, boolean allowInsecureLoopback) {
        if (uri == null || uri.getHost() == null || uri.getHost().isBlank()) {
            return "relay_url_invalid";
        }
        String scheme = value(uri.getScheme(), "").toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            return "";
        }
        if ("http".equals(scheme) && allowInsecureLoopback && BridgeSecurityPolicy.isLoopbackHost(uri.getHost())) {
            return "";
        }
        return "relay_https_required";
    }

    private static Path resolveIdentityFile(String value, Path dataDirectory) {
        try {
            Path configured = Path.of(value);
            if (configured.isAbsolute()) {
                return configured.normalize();
            }
            Path root = dataDirectory.toAbsolutePath().normalize();
            Path resolved = root.resolve(configured).normalize();
            return resolved.startsWith(root) ? resolved : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String firstIssue(String existing, String candidate) {
        return existing == null || existing.isBlank() ? candidate : existing;
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
