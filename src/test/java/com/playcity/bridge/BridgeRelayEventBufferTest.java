package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playcity.bridge.api.BridgeEvent;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BridgeRelayEventBufferTest {
    @Test
    void boundsScopedEventsAndRequiresExplicitGapAcceptance() {
        BridgeRelayEventBuffer buffer = new BridgeRelayEventBuffer(Set.of("minecraft.*"), 2, 2);

        assertFalse(buffer.offer(event("instance-1", 1L, "example.hidden")));
        assertFalse(buffer.offer(event("instance-1", 1L, "minecraft.player_joined")));
        assertFalse(buffer.offer(event("instance-1", 2L, "minecraft.player_left")));
        assertTrue(buffer.offer(event("instance-1", 3L, "minecraft.player_joined")));

        BridgeRelayEventBuffer.Batch first = buffer.batch();
        assertTrue(first.overflowed());
        assertEquals(1L, first.droppedThroughSequence());
        assertEquals(Set.of(2L, 3L), first.events().stream().map(BridgeEvent::sequence).collect(java.util.stream.Collectors.toSet()));
        assertFalse(buffer.acknowledge(first, "instance-1", 4L, true));
        assertTrue(buffer.acknowledge(first, "instance-1", 2L, false));
        assertEquals(1, buffer.size());
        assertTrue(buffer.overflowed());

        BridgeRelayEventBuffer.Batch second = buffer.batch();
        assertTrue(buffer.acknowledge(second, "instance-1", 3L, true));
        assertEquals(0, buffer.size());
        assertFalse(buffer.overflowed());
    }

    @Test
    void resetsBufferedStateWhenTheBridgeInstanceChanges() {
        BridgeRelayEventBuffer buffer = new BridgeRelayEventBuffer(Set.of("*"), 2, 1);
        buffer.offer(event("instance-1", 10L, "minecraft.player_joined"));
        buffer.offer(event("instance-2", 1L, "minecraft.player_left"));

        BridgeRelayEventBuffer.Batch batch = buffer.batch();
        assertEquals("instance-2", batch.instanceId());
        assertEquals(1, batch.events().size());
        assertEquals(1L, batch.events().getFirst().sequence());
        assertFalse(batch.overflowed());
    }

    @Test
    void reportsAStateGapAfterTransportReload() {
        BridgeRelayEventBuffer buffer = new BridgeRelayEventBuffer(Set.of("*"), 8, 8);
        buffer.requireResyncOnNextEvent();
        buffer.offer(event("instance-1", 12L, "minecraft.player_joined"));

        BridgeRelayEventBuffer.Batch batch = buffer.batch();
        assertTrue(batch.overflowed());
        assertEquals(11L, batch.droppedThroughSequence());
        assertTrue(buffer.acknowledge(batch, "instance-1", 12L, true));
        assertFalse(buffer.overflowed());
    }

    private static BridgeEvent event(String instanceId, long sequence, String name) {
        return new BridgeEvent(instanceId, sequence, name, "1", 1_700_000_000_000L + sequence, Map.of("value", sequence));
    }
}
