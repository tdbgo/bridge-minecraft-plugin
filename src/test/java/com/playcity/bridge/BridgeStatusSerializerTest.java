package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BridgeStatusSerializerTest {
    @Test
    void returnsAggregateStatusWithoutPlayerIdentities() {
        BridgeMetricsSnapshot snapshot = new BridgeMetricsSnapshot(
            Instant.parse("2026-08-13T00:00:00Z"),
            1_700_000_000_000L,
            "Paper 26.2",
            "26.2-R0.1",
            20.0,
            19.9,
            19.8,
            new BridgeMetricsSnapshot.TickDurationStats(0.01, 0.01, 0.02, 0.03, 0.04),
            3,
            100,
            List.of(new BridgeMetricsSnapshot.WorldMetrics("world", 12, 30, 3))
        );

        JsonObject json = BridgeStatusSerializer.toJson(snapshot, false, "0.2.0-SNAPSHOT", 1_700_000_100_000L);

        assertEquals(3, json.getAsJsonObject("players").get("online").getAsInt());
        assertEquals(1, json.get("schemaVersion").getAsInt());
        assertEquals("ready", json.get("state").getAsString());
        assertEquals(0, json.getAsJsonArray("degradedReasons").size());
        assertEquals("Bridge", json.getAsJsonObject("plugin").get("name").getAsString());
        assertEquals(100L, json.getAsJsonObject("server").get("uptimeSeconds").getAsLong());
        assertEquals(12, json.getAsJsonArray("worlds").get(0).getAsJsonObject().get("loadedChunks").getAsInt());
        assertFalse(json.toString().contains("username"));
        assertFalse(json.toString().contains("uuid"));
    }

    @Test
    void marksStaleSnapshotAsDegraded() {
        BridgeMetricsSnapshot snapshot = new BridgeMetricsSnapshot(
            Instant.parse("2026-08-13T00:00:00Z"),
            1_700_000_000_000L,
            "Paper 26.2",
            "26.2-R0.1",
            20.0,
            20.0,
            20.0,
            new BridgeMetricsSnapshot.TickDurationStats(0.01, 0.01, 0.01, 0.01, 0.01),
            0,
            100,
            List.of()
        );

        JsonObject json = BridgeStatusSerializer.toJson(snapshot, true, "0.2.0-SNAPSHOT", 1_700_000_100_000L);

        assertEquals("degraded", json.get("state").getAsString());
        assertEquals("metrics_stale", json.getAsJsonArray("degradedReasons").get(0).getAsString());
    }
}
