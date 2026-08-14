package com.playcity.bridge;

public final class PrometheusMetricsFormatter {
    private PrometheusMetricsFormatter() {
    }

    public static String format(
        BridgeMetricsSnapshot snapshot,
        boolean stale,
        String bridgeVersion,
        BridgeMetricsRegistry.Snapshot actionMetrics
    ) {
        return format(snapshot, stale, bridgeVersion, actionMetrics, "playcity");
    }

    public static String format(
        BridgeMetricsSnapshot snapshot,
        boolean stale,
        String bridgeVersion,
        BridgeMetricsRegistry.Snapshot actionMetrics,
        String namespace
    ) {
        String prefix = namespace + "_";
        StringBuilder output = new StringBuilder(4096);
        helpType(output, prefix + "bridge_info", "Bridge plugin build information.", "gauge");
        sample(output, prefix + "bridge_info", labels("version", bridgeVersion), 1.0);
        helpType(output, prefix + "bridge_up", "Whether the Bridge metrics handler is serving a snapshot.", "gauge");
        sample(output, prefix + "bridge_up", "", 1.0);
        helpType(output, prefix + "bridge_metrics_ready", "Whether Bridge has completed a Paper metrics sample.", "gauge");
        sample(output, prefix + "bridge_metrics_ready", "", 1.0);
        helpType(output, prefix + "minecraft_info", "Minecraft and Bridge build information.", "gauge");
        sample(output, prefix + "minecraft_info", labels(
            "server_version", snapshot.serverVersion(),
            "api_version", snapshot.apiVersion(),
            "bridge_version", bridgeVersion
        ), 1.0);

        helpType(output, prefix + "minecraft_start_time_seconds", "Minecraft JVM start time in Unix seconds.", "gauge");
        sample(output, prefix + "minecraft_start_time_seconds", "", snapshot.serverStartTimeMillis() / 1000.0);
        helpType(output, prefix + "minecraft_metrics_last_sample_timestamp_seconds", "Last successful Paper metrics sample in Unix seconds.", "gauge");
        sample(output, prefix + "minecraft_metrics_last_sample_timestamp_seconds", "", snapshot.sampledAt().toEpochMilli() / 1000.0);
        helpType(output, prefix + "minecraft_metrics_stale", "Whether the latest Paper metrics sample is stale.", "gauge");
        sample(output, prefix + "minecraft_metrics_stale", "", stale ? 1.0 : 0.0);

        helpType(output, prefix + "minecraft_tps", "Paper ticks per second by rolling window.", "gauge");
        sample(output, prefix + "minecraft_tps", labels("window", "1m"), snapshot.tps1m());
        sample(output, prefix + "minecraft_tps", labels("window", "5m"), snapshot.tps5m());
        sample(output, prefix + "minecraft_tps", labels("window", "15m"), snapshot.tps15m());

        BridgeMetricsSnapshot.TickDurationStats tick = snapshot.tickDuration();
        helpType(output, prefix + "minecraft_tick_duration_seconds", "Paper tick duration statistics in seconds.", "gauge");
        sample(output, prefix + "minecraft_tick_duration_seconds", labels("stat", "average"), tick.averageSeconds());
        sample(output, prefix + "minecraft_tick_duration_seconds", labels("stat", "p50"), tick.p50Seconds());
        sample(output, prefix + "minecraft_tick_duration_seconds", labels("stat", "p95"), tick.p95Seconds());
        sample(output, prefix + "minecraft_tick_duration_seconds", labels("stat", "p99"), tick.p99Seconds());
        sample(output, prefix + "minecraft_tick_duration_seconds", labels("stat", "max"), tick.maxSeconds());

        helpType(output, prefix + "minecraft_players_online", "Current online player count.", "gauge");
        sample(output, prefix + "minecraft_players_online", "", snapshot.playersOnline());
        helpType(output, prefix + "minecraft_players_max", "Configured maximum player count.", "gauge");
        sample(output, prefix + "minecraft_players_max", "", snapshot.maxPlayers());
        helpType(output, prefix + "minecraft_worlds_loaded", "Number of included loaded worlds.", "gauge");
        sample(output, prefix + "minecraft_worlds_loaded", "", snapshot.worlds().size());

        helpType(output, prefix + "minecraft_world_loaded_chunks", "Loaded chunk count by world.", "gauge");
        helpType(output, prefix + "minecraft_world_entities", "Loaded entity count by world.", "gauge");
        helpType(output, prefix + "minecraft_world_players", "Online player count by world.", "gauge");
        for (BridgeMetricsSnapshot.WorldMetrics world : snapshot.worlds()) {
            String worldLabel = labels("world", world.name());
            sample(output, prefix + "minecraft_world_loaded_chunks", worldLabel, world.loadedChunks());
            sample(output, prefix + "minecraft_world_entities", worldLabel, world.entities());
            sample(output, prefix + "minecraft_world_players", worldLabel, world.players());
        }

        helpType(output, prefix + "bridge_actions_total", "Completed typed Bridge actions by action and result code.", "counter");
        for (BridgeMetricsRegistry.ActionCount count : actionMetrics.actionCounts()) {
            sample(output, prefix + "bridge_actions_total", labels(
                "action", count.action(),
                "code", count.code()
            ), count.count());
        }

        helpType(output, prefix + "bridge_action_duration_seconds", "Completed typed Bridge action duration in seconds.", "summary");
        for (BridgeMetricsRegistry.ActionDuration duration : actionMetrics.actionDurations()) {
            String actionLabel = labels("action", duration.action());
            sample(output, prefix + "bridge_action_duration_seconds_count", actionLabel, duration.count());
            sample(output, prefix + "bridge_action_duration_seconds_sum", actionLabel, duration.totalNanos() / 1_000_000_000.0);
        }
        return output.toString();
    }

    private static void helpType(StringBuilder output, String name, String help, String type) {
        output.append("# HELP ").append(name).append(' ').append(help).append('\n');
        output.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static void sample(StringBuilder output, String name, String labels, double value) {
        output.append(name).append(labels).append(' ').append(Double.toString(value)).append('\n');
    }

    private static void sample(StringBuilder output, String name, String labels, long value) {
        output.append(name).append(labels).append(' ').append(value).append('\n');
    }

    private static String labels(String... pairs) {
        if (pairs.length == 0) {
            return "";
        }
        StringBuilder labels = new StringBuilder("{");
        for (int index = 0; index < pairs.length; index += 2) {
            if (index > 0) {
                labels.append(',');
            }
            labels.append(pairs[index]).append("=\"").append(escapeLabel(pairs[index + 1])).append('"');
        }
        return labels.append('}').toString();
    }

    static String escapeLabel(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }
}
