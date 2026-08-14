package com.playcity.bridge;

import com.playcity.bridge.api.BridgeEvent;
import com.playcity.bridge.api.BridgeEventDescriptor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class BridgeEventHub {
    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicLong sequence = new AtomicLong();
    private final Deque<BridgeEvent> replay = new ArrayDeque<>();
    private final CopyOnWriteArrayList<Consumer<BridgeEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Logger logger;

    BridgeEventHub(Logger logger) {
        this.logger = logger;
    }

    String instanceId() {
        return instanceId;
    }

    synchronized BridgeEvent publish(
        BridgeEventDescriptor descriptor,
        Map<String, ?> data,
        int replayMaxEvents,
        int maxEventBytes
    ) {
        BridgeEvent event = new BridgeEvent(
            instanceId,
            sequence.get() + 1L,
            descriptor.name(),
            descriptor.version(),
            System.currentTimeMillis(),
            data == null ? Map.of() : new LinkedHashMap<>(data)
        );
        int maxDataBytes = Math.max(256, maxEventBytes - 512);
        if (BridgeJson.toJsonObject(event.data()).toString().getBytes(StandardCharsets.UTF_8).length > maxDataBytes) {
            throw new IllegalArgumentException("Bridge event data exceeds the configured size limit.");
        }
        sequence.incrementAndGet();
        replay.addLast(event);
        while (replay.size() > replayMaxEvents) {
            replay.removeFirst();
        }
        for (Consumer<BridgeEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Bridge event listener failed for " + event.name(), exception);
            }
        }
        return event;
    }

    AutoCloseable subscribe(Consumer<BridgeEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    synchronized Subscription subscribeFrom(
        String requestedInstanceId,
        long afterSequence,
        int limit,
        Consumer<BridgeEvent> listener
    ) {
        ReplayResult result;
        if (requestedInstanceId == null || requestedInstanceId.isBlank()) {
            long newest = replay.isEmpty() ? sequence.get() : replay.getLast().sequence();
            long oldest = replay.isEmpty() ? newest + 1L : replay.getFirst().sequence();
            result = new ReplayResult(ReplayStatus.OK, List.of(), oldest, newest);
        } else {
            result = replay(requestedInstanceId, afterSequence, limit);
        }
        if (result.status() != ReplayStatus.OK) {
            return new Subscription(result, () -> { });
        }
        listeners.add(listener);
        return new Subscription(result, () -> listeners.remove(listener));
    }

    synchronized ReplayResult replay(String requestedInstanceId, long afterSequence, int limit) {
        long newest = replay.isEmpty() ? sequence.get() : replay.getLast().sequence();
        long oldest = replay.isEmpty() ? newest + 1L : replay.getFirst().sequence();
        if (!instanceId.equals(requestedInstanceId)) {
            return new ReplayResult(ReplayStatus.INSTANCE_CHANGED, List.of(), oldest, newest);
        }
        if (!replay.isEmpty() && afterSequence < oldest - 1L) {
            return new ReplayResult(ReplayStatus.GAP, List.of(), oldest, newest);
        }
        long available = replay.stream().filter(event -> event.sequence() > afterSequence).count();
        if (available > limit) {
            return new ReplayResult(ReplayStatus.LIMIT, List.of(), oldest, newest);
        }
        List<BridgeEvent> events = new ArrayList<>();
        for (BridgeEvent event : replay) {
            if (event.sequence() > afterSequence) {
                events.add(event);
                if (events.size() >= limit) {
                    break;
                }
            }
        }
        return new ReplayResult(ReplayStatus.OK, List.copyOf(events), oldest, newest);
    }

    enum ReplayStatus {
        OK,
        INSTANCE_CHANGED,
        GAP,
        LIMIT
    }

    record ReplayResult(ReplayStatus status, List<BridgeEvent> events, long oldestSequence, long newestSequence) {
    }

    record Subscription(ReplayResult replay, AutoCloseable handle) {
    }
}
