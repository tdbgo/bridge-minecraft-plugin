package com.playcity.bridge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

public final class BridgeConfig {
    private final boolean httpEnabled;
    private final String httpHost;
    private final int httpPort;
    private final boolean httpAllowNonLoopback;
    private final String token;
    private final List<BridgeHttpCredential> httpCredentials;
    private final List<String> httpCredentialIssues;
    private final int maxRequestBytes;
    private final int workerThreads;
    private final int actionTimeoutMillis;
    private final boolean eventsEnabled;
    private final String eventsHost;
    private final int eventsPort;
    private final boolean eventsAllowNonLoopback;
    private final int eventMaxMessageBytes;
    private final int eventReplayMaxEvents;
    private final int eventReplayMaxBatch;
    private final int eventMaxPendingMessages;
    private final int eventConnectionLostSeconds;
    private final BridgeJwtConfig jwtConfig;
    private final long requestCacheTtlMillis;
    private final int requestCacheMaxEntries;
    private final boolean metricsEnabled;
    private final String metricsNamespace;
    private final String metricsToken;
    private final String metricsTokenSource;
    private final String metricsTokenIssue;
    private final long metricsSampleIntervalTicks;
    private final long metricsStaleAfterMillis;
    private final Set<String> metricsIncludedWorlds;
    private final boolean teleportEnabled;
    private final Set<String> teleportAllowedWorlds;
    private final int teleportMaxAbsXz;
    private final boolean noticeSyncEnabled;
    private final boolean noticeSendEnabled;
    private final int noticeMaxMessageLength;

    private BridgeConfig(
        boolean httpEnabled,
        String httpHost,
        int httpPort,
        boolean httpAllowNonLoopback,
        String token,
        List<BridgeHttpCredential> httpCredentials,
        List<String> httpCredentialIssues,
        int maxRequestBytes,
        int workerThreads,
        int actionTimeoutMillis,
        boolean eventsEnabled,
        String eventsHost,
        int eventsPort,
        boolean eventsAllowNonLoopback,
        int eventMaxMessageBytes,
        int eventReplayMaxEvents,
        int eventReplayMaxBatch,
        int eventMaxPendingMessages,
        int eventConnectionLostSeconds,
        BridgeJwtConfig jwtConfig,
        long requestCacheTtlMillis,
        int requestCacheMaxEntries,
        boolean metricsEnabled,
        String metricsNamespace,
        BridgeTokenResolver.Resolution metricsTokenResolution,
        long metricsSampleIntervalTicks,
        long metricsStaleAfterMillis,
        Set<String> metricsIncludedWorlds,
        boolean teleportEnabled,
        Set<String> teleportAllowedWorlds,
        int teleportMaxAbsXz,
        boolean noticeSyncEnabled,
        boolean noticeSendEnabled,
        int noticeMaxMessageLength
    ) {
        this.httpEnabled = httpEnabled;
        this.httpHost = httpHost;
        this.httpPort = httpPort;
        this.httpAllowNonLoopback = httpAllowNonLoopback;
        this.token = token;
        this.httpCredentials = List.copyOf(httpCredentials);
        this.httpCredentialIssues = List.copyOf(httpCredentialIssues);
        this.maxRequestBytes = maxRequestBytes;
        this.workerThreads = workerThreads;
        this.actionTimeoutMillis = actionTimeoutMillis;
        this.eventsEnabled = eventsEnabled;
        this.eventsHost = eventsHost;
        this.eventsPort = eventsPort;
        this.eventsAllowNonLoopback = eventsAllowNonLoopback;
        this.eventMaxMessageBytes = eventMaxMessageBytes;
        this.eventReplayMaxEvents = eventReplayMaxEvents;
        this.eventReplayMaxBatch = eventReplayMaxBatch;
        this.eventMaxPendingMessages = eventMaxPendingMessages;
        this.eventConnectionLostSeconds = eventConnectionLostSeconds;
        this.jwtConfig = jwtConfig;
        this.requestCacheTtlMillis = requestCacheTtlMillis;
        this.requestCacheMaxEntries = requestCacheMaxEntries;
        this.metricsEnabled = metricsEnabled;
        this.metricsNamespace = metricsNamespace;
        this.metricsToken = metricsTokenResolution.token();
        this.metricsTokenSource = metricsTokenResolution.source().id();
        this.metricsTokenIssue = metricsTokenResolution.issue();
        this.metricsSampleIntervalTicks = metricsSampleIntervalTicks;
        this.metricsStaleAfterMillis = metricsStaleAfterMillis;
        this.metricsIncludedWorlds = metricsIncludedWorlds;
        this.teleportEnabled = teleportEnabled;
        this.teleportAllowedWorlds = teleportAllowedWorlds;
        this.teleportMaxAbsXz = teleportMaxAbsXz;
        this.noticeSyncEnabled = noticeSyncEnabled;
        this.noticeSendEnabled = noticeSendEnabled;
        this.noticeMaxMessageLength = noticeMaxMessageLength;
    }

    public static BridgeConfig from(FileConfiguration config, Path dataDirectory) {
        Set<String> allowedWorlds = new HashSet<>();
        for (String world : config.getStringList("actions.teleportPlayer.allowedWorlds")) {
            String normalized = normalizeWorld(world);
            if (!normalized.isBlank()) {
                allowedWorlds.add(normalized);
            }
        }
        Set<String> includedWorlds = new HashSet<>();
        for (String world : config.getStringList("metrics.includedWorlds")) {
            String normalized = normalizeWorld(world);
            if (!normalized.isBlank()) {
                includedWorlds.add(normalized);
            }
        }
        String legacyToken = trimmed(config.getString("http.token", ""));
        HttpCredentials httpCredentials = loadHttpCredentials(config, dataDirectory, legacyToken);
        BridgeJwtConfig jwtConfig = BridgeJwtConfig.from(config, dataDirectory);
        BridgeTokenResolver.Resolution metricsTokenResolution = BridgeTokenResolver.resolve(
            config.getString("metrics.token", ""),
            config.isSet("metrics.tokenFile") ? config.getString("metrics.tokenFile", "") : "",
            dataDirectory
        );
        return new BridgeConfig(
            config.getBoolean("http.enabled", true),
            nonBlank(config.getString("http.host"), "127.0.0.1"),
            clamp(config.getInt("http.port", 25585), 1, 65535),
            config.getBoolean("http.allowNonLoopback", false),
            legacyToken,
            httpCredentials.credentials(),
            httpCredentials.issues(),
            clamp(config.getInt("http.maxRequestBytes", 32768), 1024, 1024 * 1024),
            clamp(config.getInt("http.workerThreads", 2), 1, 16),
            clamp(config.getInt("http.actionTimeoutMillis", 5000), 1000, 60_000),
            config.getBoolean("events.enabled", false),
            nonBlank(config.getString("events.host"), "127.0.0.1"),
            clamp(config.getInt("events.port", 25586), 1, 65535),
            config.getBoolean("events.allowNonLoopback", false),
            clamp(config.getInt("events.maxMessageBytes", 16_384), 1024, 262_144),
            clamp(config.getInt("events.replayMaxEvents", 2048), 16, 100_000),
            clamp(config.getInt("events.replayMaxBatch", 512), 1, 10_000),
            clamp(config.getInt("events.maxPendingMessages", 256), 8, 4096),
            clamp(config.getInt("events.connectionLostSeconds", 30), 10, 300),
            jwtConfig,
            clamp(config.getLong("requests.cacheTtlSeconds", 300L), 1L, 86400L) * 1000L,
            clamp(config.getInt("requests.cacheMaxEntries", 4096), 128, 100_000),
            config.getBoolean("metrics.enabled", true),
            metricNamespace(config.getString("metrics.namespace", "playcity")),
            metricsTokenResolution,
            clamp(config.getLong("metrics.sampleIntervalTicks", 100L), 20L, 1200L),
            clamp(config.getLong("metrics.staleAfterSeconds", 30L), 5L, 3600L) * 1000L,
            Collections.unmodifiableSet(includedWorlds),
            config.getBoolean("actions.teleportPlayer.enabled", true),
            Collections.unmodifiableSet(allowedWorlds),
            clamp(config.getInt("actions.teleportPlayer.maxAbsXz", 30_000_000), 1, 30_000_000),
            config.getBoolean("actions.notice.syncEnabled", false),
            config.getBoolean("actions.notice.sendEnabled", false),
            clamp(config.getInt("actions.notice.maxMessageLength", 4096), 1, 4096)
        );
    }

    public boolean httpEnabled() {
        return httpEnabled;
    }

    public String httpHost() {
        return httpHost;
    }

    public int httpPort() {
        return httpPort;
    }

    public boolean isHttpBindAllowed() {
        return httpAllowNonLoopback || BridgeSecurityPolicy.isLoopbackHost(httpHost);
    }

    public String token() {
        return token;
    }

    public boolean hasUsableToken() {
        return !httpCredentials.isEmpty() || jwtConfig.valid();
    }

    public boolean hasStaticHttpCredential() {
        return !httpCredentials.isEmpty();
    }

    public List<BridgeHttpCredential> httpCredentials() {
        return httpCredentials;
    }

    public List<String> httpCredentialIssues() {
        return httpCredentialIssues;
    }

    public int maxRequestBytes() {
        return maxRequestBytes;
    }

    public int workerThreads() {
        return workerThreads;
    }

    public int actionTimeoutMillis() {
        return actionTimeoutMillis;
    }

    public boolean eventsEnabled() {
        return eventsEnabled;
    }

    public String eventsHost() {
        return eventsHost;
    }

    public int eventsPort() {
        return eventsPort;
    }

    public boolean isEventsBindAllowed() {
        return eventsAllowNonLoopback || BridgeSecurityPolicy.isLoopbackHost(eventsHost);
    }

    public int eventMaxMessageBytes() {
        return eventMaxMessageBytes;
    }

    public int eventReplayMaxEvents() {
        return eventReplayMaxEvents;
    }

    public int eventReplayMaxBatch() {
        return eventReplayMaxBatch;
    }

    public int eventMaxPendingMessages() {
        return eventMaxPendingMessages;
    }

    public int eventConnectionLostSeconds() {
        return eventConnectionLostSeconds;
    }

    public BridgeJwtConfig jwtConfig() {
        return jwtConfig;
    }

    public long requestCacheTtlMillis() {
        return requestCacheTtlMillis;
    }

    public int requestCacheMaxEntries() {
        return requestCacheMaxEntries;
    }

    public boolean metricsEnabled() {
        return metricsEnabled;
    }

    public String metricsNamespace() {
        return metricsNamespace;
    }

    public String metricsToken() {
        return metricsToken;
    }

    public boolean hasUsableMetricsToken() {
        return metricsTokenIssue().isBlank();
    }

    public String metricsTokenSource() {
        return metricsTokenSource;
    }

    public String metricsTokenIssue() {
        if (!metricsTokenIssue.isBlank()) {
            return metricsTokenIssue;
        }
        if (!BridgeSecurityPolicy.isUsableToken(metricsToken)) {
            return "metrics_token_not_configured";
        }
        if (httpCredentials.stream().anyMatch(credential -> metricsToken.equals(credential.token()))) {
            return "metrics_token_reuses_action_token";
        }
        return "";
    }

    public long metricsSampleIntervalTicks() {
        return metricsSampleIntervalTicks;
    }

    public long metricsStaleAfterMillis() {
        return metricsStaleAfterMillis;
    }

    public Set<String> metricsIncludedWorlds() {
        return metricsIncludedWorlds;
    }

    public boolean isMetricsWorldIncluded(String worldName) {
        return metricsIncludedWorlds.isEmpty() || metricsIncludedWorlds.contains(normalizeWorld(worldName));
    }

    public boolean teleportEnabled() {
        return teleportEnabled;
    }

    public Set<String> teleportAllowedWorlds() {
        return teleportAllowedWorlds;
    }

    public int teleportMaxAbsXz() {
        return teleportMaxAbsXz;
    }

    public boolean noticeSyncEnabled() {
        return noticeSyncEnabled;
    }

    public boolean noticeSendEnabled() {
        return noticeSendEnabled;
    }

    public int noticeMaxMessageLength() {
        return noticeMaxMessageLength;
    }

    public boolean isTeleportWorldAllowed(String worldName) {
        return teleportAllowedWorlds.isEmpty() || teleportAllowedWorlds.contains(normalizeWorld(worldName));
    }

    public static String normalizeWorld(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String nonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static HttpCredentials loadHttpCredentials(
        FileConfiguration config,
        Path dataDirectory,
        String legacyToken
    ) {
        List<BridgeHttpCredential> credentials = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> tokens = new HashSet<>();

        for (Map<?, ?> entry : config.getMapList("http.clients")) {
            String id = stringValue(entry.get("id"));
            if (!id.matches("[A-Za-z0-9_.:-]{1,80}") || !ids.add(id)) {
                issues.add("http_client_id_invalid_or_duplicate");
                continue;
            }
            BridgeTokenResolver.Resolution tokenResolution = BridgeTokenResolver.resolve(
                stringValue(entry.get("token")),
                stringValue(entry.get("tokenFile")),
                dataDirectory
            );
            if (!tokenResolution.valid()) {
                issues.add(id + ":" + tokenResolution.issue().replace("metrics_token", "http_client_token"));
                continue;
            }
            if (!tokens.add(tokenResolution.token())) {
                issues.add(id + ":http_client_token_duplicate");
                continue;
            }
            Set<String> allowedActions = actionRules(entry.get("allowedActions"), id, issues);
            Set<String> allowedEvents = eventRules(entry.get("allowedEvents"), id, issues);
            credentials.add(new BridgeHttpCredential(
                id,
                tokenResolution.token(),
                allowedActions,
                allowedEvents,
                clamp(intValue(entry.get("requestsPerMinute"), 120), 1, 60_000),
                clamp(intValue(entry.get("maxConcurrent"), 4), 1, 64),
                clamp(intValue(entry.get("maxEventConnections"), 0), 0, 16)
            ));
        }

        if (BridgeSecurityPolicy.isUsableToken(legacyToken)) {
            if (tokens.add(legacyToken)) {
                credentials.add(new BridgeHttpCredential(
                    "legacy-http",
                    legacyToken,
                    Set.of("*"),
                    Set.of(),
                    clamp(config.getInt("http.legacyRequestsPerMinute", 600), 1, 60_000),
                    clamp(config.getInt("http.legacyMaxConcurrent", 4), 1, 64),
                    0
                ));
            } else {
                issues.add("legacy-http:http_client_token_duplicate");
            }
        }
        return new HttpCredentials(credentials, issues);
    }

    private static Set<String> actionRules(Object value, String id, List<String> issues) {
        return rules(value, id, "http_client_action_rule_invalid", issues);
    }

    private static Set<String> eventRules(Object value, String id, List<String> issues) {
        return rules(value, id, "http_client_event_rule_invalid", issues);
    }

    private static Set<String> rules(Object value, String id, String issue, List<String> issues) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (!(value instanceof Iterable<?> iterable)) {
            return Set.of();
        }
        for (Object item : iterable) {
            String rule = stringValue(item).toLowerCase(Locale.ROOT);
            if (!BridgeScopeRules.validRule(rule)) {
                issues.add(id + ":" + issue);
                continue;
            }
            result.add(rule);
        }
        return Collections.unmodifiableSet(result);
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string.trim() : "";
    }

    private static String metricNamespace(String value) {
        String normalized = value == null ? "playcity" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z][a-z0-9_]{0,31}") ? normalized : "bridge";
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private record HttpCredentials(List<BridgeHttpCredential> credentials, List<String> issues) {
    }
}
