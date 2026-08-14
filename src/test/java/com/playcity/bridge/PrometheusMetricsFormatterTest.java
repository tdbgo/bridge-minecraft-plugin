package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrometheusMetricsFormatterTest {
    @Test
    void emitsPrometheusContractWithoutPlayerIdentityLabels() {
        BridgeMetricsRegistry registry = new BridgeMetricsRegistry();
        registry.recordAction("minecraft.teleport_player", "ok", 250_000_000L);
        BridgeMetricsSnapshot snapshot = new BridgeMetricsSnapshot(
            Instant.parse("2026-08-13T00:00:00Z"),
            1_700_000_000_000L,
            "Paper 26.2 \"stable\"",
            "26.2-R0.1\\test",
            19.9,
            19.8,
            19.7,
            new BridgeMetricsSnapshot.TickDurationStats(0.05, 0.04, 0.06, 0.08, 0.1),
            2,
            100,
            List.of(new BridgeMetricsSnapshot.WorldMetrics("world\nmain", 10, 20, 2))
        );

        String output = PrometheusMetricsFormatter.format(snapshot, false, "0.2.0-SNAPSHOT", registry.snapshot());

        assertTrue(output.contains("playcity_minecraft_tps{window=\"1m\"} 19.9"));
        assertTrue(output.contains("playcity_bridge_info{version=\"0.2.0-SNAPSHOT\"} 1.0"));
        assertTrue(output.contains("playcity_bridge_up 1.0"));
        assertTrue(output.contains("playcity_bridge_metrics_ready 1.0"));
        assertTrue(output.contains("playcity_minecraft_tick_duration_seconds{stat=\"p99\"} 0.08"));
        assertTrue(output.contains("world=\"world\\nmain\""));
        assertTrue(output.contains("api_version=\"26.2-R0.1\\\\test\""));
        assertTrue(output.contains("playcity_bridge_actions_total{action=\"minecraft.teleport_player\",code=\"ok\"} 1"));
        assertTrue(output.contains("playcity_bridge_action_duration_seconds_sum{action=\"minecraft.teleport_player\"} 0.25"));
        assertFalse(output.contains("requestId"));
        assertFalse(output.contains("username"));
        assertFalse(output.contains("uuid"));
    }
}
