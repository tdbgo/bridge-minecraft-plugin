package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BridgeMetricsSamplerTest {
    @Test
    void calculatesTickDurationStatisticsInSeconds() {
        long[] tickTimes = {
            5_000_000L,
            1_000_000L,
            4_000_000L,
            2_000_000L,
            3_000_000L
        };

        BridgeMetricsSnapshot.TickDurationStats stats = BridgeMetricsMath.tickDurationStats(12.5, tickTimes);

        assertEquals(0.0125, stats.averageSeconds());
        assertEquals(0.003, stats.p50Seconds());
        assertEquals(0.005, stats.p95Seconds());
        assertEquals(0.005, stats.p99Seconds());
        assertEquals(0.005, stats.maxSeconds());
    }

    @Test
    void handlesMissingAndInvalidTickData() {
        BridgeMetricsSnapshot.TickDurationStats stats = BridgeMetricsMath.tickDurationStats(Double.NaN, null);

        assertEquals(0.0, stats.averageSeconds());
        assertEquals(0.0, stats.p50Seconds());
        assertEquals(0.0, stats.maxSeconds());
    }
}
