package com.playcity.bridge;

import com.playcity.bridge.api.BridgeEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class BridgeRelayEventBuffer {
    private final Set<String> allowedEvents;
    private final int maxQueuedEvents;
    private final int maxBatchEvents;
    private final Deque<BridgeEvent> queued = new ArrayDeque<>();
    private String instanceId = "";
    private boolean overflowed;
    private long droppedThroughSequence;
    private boolean gapBeforeNextEvent;

    BridgeRelayEventBuffer(Set<String> allowedEvents, int maxQueuedEvents, int maxBatchEvents) {
        this.allowedEvents = Set.copyOf(allowedEvents);
        this.maxQueuedEvents = Math.max(1, maxQueuedEvents);
        this.maxBatchEvents = Math.max(1, Math.min(maxBatchEvents, this.maxQueuedEvents));
    }

    synchronized boolean offer(BridgeEvent event) {
        Objects.requireNonNull(event, "event");
        if (!BridgeScopeRules.matches(allowedEvents, event.name())) {
            return false;
        }
        if (!instanceId.isBlank() && !instanceId.equals(event.instanceId())) {
            queued.clear();
            overflowed = false;
            droppedThroughSequence = 0L;
        }
        instanceId = event.instanceId();
        if (gapBeforeNextEvent) {
            overflowed = true;
            droppedThroughSequence = Math.max(droppedThroughSequence, Math.max(0L, event.sequence() - 1L));
            gapBeforeNextEvent = false;
        }
        boolean dropped = false;
        if (queued.size() >= maxQueuedEvents) {
            BridgeEvent removed = queued.removeFirst();
            droppedThroughSequence = Math.max(droppedThroughSequence, removed.sequence());
            overflowed = true;
            dropped = true;
        }
        queued.addLast(event);
        return dropped;
    }

    synchronized Batch batch() {
        List<BridgeEvent> events = new ArrayList<>(Math.min(maxBatchEvents, queued.size()));
        for (BridgeEvent event : queued) {
            events.add(event);
            if (events.size() >= maxBatchEvents) {
                break;
            }
        }
        return new Batch(instanceId, List.copyOf(events), overflowed, droppedThroughSequence);
    }

    synchronized void requireResyncOnNextEvent() {
        gapBeforeNextEvent = true;
    }

    synchronized boolean acknowledge(Batch sent, String acknowledgedInstanceId, long throughSequence, boolean acceptGap) {
        Objects.requireNonNull(sent, "sent");
        if (acknowledgedInstanceId == null
            || !acknowledgedInstanceId.equals(sent.instanceId())
            || !acknowledgedInstanceId.equals(instanceId)
            || throughSequence < 0L
            || throughSequence > sent.highestAcknowledgableSequence()) {
            return false;
        }
        while (!queued.isEmpty() && queued.getFirst().sequence() <= throughSequence) {
            queued.removeFirst();
        }
        if (acceptGap && sent.overflowed() && throughSequence >= sent.droppedThroughSequence()) {
            overflowed = false;
            droppedThroughSequence = 0L;
        }
        return true;
    }

    synchronized int size() {
        return queued.size();
    }

    synchronized boolean overflowed() {
        return overflowed;
    }

    record Batch(
        String instanceId,
        List<BridgeEvent> events,
        boolean overflowed,
        long droppedThroughSequence
    ) {
        Batch {
            instanceId = instanceId == null ? "" : instanceId;
            events = List.copyOf(events);
        }

        boolean present() {
            return !instanceId.isBlank() && (overflowed || !events.isEmpty());
        }

        long highestAcknowledgableSequence() {
            long eventSequence = events.isEmpty() ? 0L : events.getLast().sequence();
            return Math.max(eventSequence, droppedThroughSequence);
        }
    }
}
