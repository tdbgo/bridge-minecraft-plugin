package com.playcity.bridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class BridgeMetricsRegistry {
    private final Map<ActionCodeKey, LongAdder> actionCounts = new ConcurrentHashMap<>();
    private final Map<String, ActionDurationAccumulator> actionDurations = new ConcurrentHashMap<>();

    public void recordAction(String action, String code, long durationNanos) {
        String safeAction = boundedLabel(action, "unknown");
        String safeCode = boundedLabel(code, "unknown");
        actionCounts.computeIfAbsent(new ActionCodeKey(safeAction, safeCode), ignored -> new LongAdder()).increment();
        actionDurations.computeIfAbsent(safeAction, ignored -> new ActionDurationAccumulator())
            .add(Math.max(0L, durationNanos));
    }

    public Snapshot snapshot() {
        List<ActionCount> counts = new ArrayList<>();
        actionCounts.forEach((key, value) -> counts.add(new ActionCount(key.action(), key.code(), value.sum())));
        counts.sort(Comparator.comparing(ActionCount::action).thenComparing(ActionCount::code));

        List<ActionDuration> durations = new ArrayList<>();
        actionDurations.forEach((action, value) -> durations.add(
            new ActionDuration(action, value.count.sum(), value.totalNanos.sum())
        ));
        durations.sort(Comparator.comparing(ActionDuration::action));
        return new Snapshot(counts, durations);
    }

    private static String boundedLabel(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 128 ? trimmed : trimmed.substring(0, 128);
    }

    private record ActionCodeKey(String action, String code) {
    }

    private static final class ActionDurationAccumulator {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();

        private void add(long nanos) {
            count.increment();
            totalNanos.add(nanos);
        }
    }

    public record Snapshot(List<ActionCount> actionCounts, List<ActionDuration> actionDurations) {
        public Snapshot {
            actionCounts = List.copyOf(actionCounts);
            actionDurations = List.copyOf(actionDurations);
        }
    }

    public record ActionCount(String action, String code, long count) {
    }

    public record ActionDuration(String action, long count, long totalNanos) {
    }
}
