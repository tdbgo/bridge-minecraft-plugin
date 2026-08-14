package com.playcity.bridge;

import java.util.Arrays;

public final class BridgeMetricsMath {
    private BridgeMetricsMath() {
    }

    public static BridgeMetricsSnapshot.TickDurationStats tickDurationStats(
        double averageMillis,
        long[] tickTimesNanos
    ) {
        long[] sorted = tickTimesNanos == null ? new long[0] : Arrays.copyOf(tickTimesNanos, tickTimesNanos.length);
        Arrays.sort(sorted);
        return new BridgeMetricsSnapshot.TickDurationStats(
            finiteNonNegative(averageMillis) / 1000.0,
            nanosToSeconds(percentile(sorted, 0.50)),
            nanosToSeconds(percentile(sorted, 0.95)),
            nanosToSeconds(percentile(sorted, 0.99)),
            sorted.length == 0 ? 0.0 : nanosToSeconds(sorted[sorted.length - 1])
        );
    }

    static long percentile(long[] sorted, double quantile) {
        if (sorted == null || sorted.length == 0) {
            return 0L;
        }
        int index = (int) Math.ceil(quantile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    public static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }

    private static double nanosToSeconds(long nanos) {
        return Math.max(0L, nanos) / 1_000_000_000.0;
    }
}
