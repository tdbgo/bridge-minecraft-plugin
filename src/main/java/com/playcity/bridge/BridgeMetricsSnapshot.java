package com.playcity.bridge;

import java.time.Instant;
import java.util.List;

public record BridgeMetricsSnapshot(
    Instant sampledAt,
    long serverStartTimeMillis,
    String serverVersion,
    String apiVersion,
    double tps1m,
    double tps5m,
    double tps15m,
    TickDurationStats tickDuration,
    int playersOnline,
    int maxPlayers,
    List<WorldMetrics> worlds
) {
    public BridgeMetricsSnapshot {
        worlds = List.copyOf(worlds);
    }

    public record TickDurationStats(
        double averageSeconds,
        double p50Seconds,
        double p95Seconds,
        double p99Seconds,
        double maxSeconds
    ) {
    }

    public record WorldMetrics(String name, int loadedChunks, int entities, int players) {
    }
}
