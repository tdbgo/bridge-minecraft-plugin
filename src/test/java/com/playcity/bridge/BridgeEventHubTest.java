package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.playcity.bridge.api.BridgeEvent;
import com.playcity.bridge.api.BridgeEventDescriptor;
import com.playcity.bridge.api.BridgeEventSensitivity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class BridgeEventHubTest {
    private static final BridgeEventDescriptor DESCRIPTOR = new BridgeEventDescriptor(
        "example.changed",
        "1",
        "A deterministic test event.",
        BridgeEventSensitivity.GENERAL
    );

    @Test
    void publishesImmutableEventsAndReplaysInSequence() throws Exception {
        BridgeEventHub hub = new BridgeEventHub(Logger.getAnonymousLogger());
        List<BridgeEvent> delivered = new ArrayList<>();
        BridgeEventHub.Subscription subscription = hub.subscribeFrom("", 0L, 10, delivered::add);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("value", List.of(1, 2));
        BridgeEvent first = hub.publish(DESCRIPTOR, data, 10, 4096);
        data.put("late", true);
        BridgeEvent second = hub.publish(DESCRIPTOR, Map.of("value", 3), 10, 4096);

        assertEquals(List.of(first, second), delivered);
        assertEquals(false, first.data().containsKey("late"));
        assertThrows(UnsupportedOperationException.class, () -> first.data().put("x", 1));

        BridgeEventHub.ReplayResult replay = hub.replay(hub.instanceId(), first.sequence(), 10);
        assertEquals(BridgeEventHub.ReplayStatus.OK, replay.status());
        assertEquals(List.of(second), replay.events());
        subscription.handle().close();
    }

    @Test
    void detectsRestartedInstancesAndExpiredReplayCursors() {
        BridgeEventHub hub = new BridgeEventHub(Logger.getAnonymousLogger());
        BridgeEvent first = hub.publish(DESCRIPTOR, Map.of("value", 1), 1, 4096);
        hub.publish(DESCRIPTOR, Map.of("value", 2), 1, 4096);

        assertEquals(
            BridgeEventHub.ReplayStatus.INSTANCE_CHANGED,
            hub.replay("different-instance", first.sequence(), 10).status()
        );
        assertEquals(
            BridgeEventHub.ReplayStatus.GAP,
            hub.replay(hub.instanceId(), 0L, 10).status()
        );
    }

    @Test
    void refusesToSilentlyTruncateReplayLargerThanOneBatch() {
        BridgeEventHub hub = new BridgeEventHub(Logger.getAnonymousLogger());
        hub.publish(DESCRIPTOR, Map.of("value", 1), 10, 4096);
        hub.publish(DESCRIPTOR, Map.of("value", 2), 10, 4096);

        assertEquals(
            BridgeEventHub.ReplayStatus.LIMIT,
            hub.replay(hub.instanceId(), 0L, 1).status()
        );
    }

    @Test
    void rejectsOversizedEventDataBeforeItEntersReplay() {
        BridgeEventHub hub = new BridgeEventHub(Logger.getAnonymousLogger());

        assertThrows(IllegalArgumentException.class, () ->
            hub.publish(DESCRIPTOR, Map.of("value", "x".repeat(400)), 10, 128)
        );
        assertEquals(0L, hub.replay(hub.instanceId(), 0L, 10).newestSequence());
    }
}
