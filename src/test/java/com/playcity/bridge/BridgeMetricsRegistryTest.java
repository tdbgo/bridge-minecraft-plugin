package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BridgeMetricsRegistryTest {
    @Test
    void aggregatesActionsByBoundedActionAndCodeLabels() {
        BridgeMetricsRegistry registry = new BridgeMetricsRegistry();
        registry.recordAction("minecraft.teleport_player", "ok", 100_000_000L);
        registry.recordAction("minecraft.teleport_player", "ok", 200_000_000L);
        registry.recordAction("minecraft.teleport_player", "minecraft_player_offline", 50_000_000L);

        BridgeMetricsRegistry.Snapshot snapshot = registry.snapshot();

        assertEquals(2, snapshot.actionCounts().size());
        assertEquals(2L, snapshot.actionCounts().get(1).count());
        assertEquals(1, snapshot.actionDurations().size());
        assertEquals(3L, snapshot.actionDurations().getFirst().count());
        assertEquals(350_000_000L, snapshot.actionDurations().getFirst().totalNanos());
    }
}
