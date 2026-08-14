package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playcity.bridge.api.BridgeActionDescriptor;
import com.playcity.bridge.api.BridgeActionRequest;
import com.playcity.bridge.api.BridgeActionRisk;
import com.playcity.bridge.api.BridgeCaller;
import com.playcity.bridge.api.BridgeEvent;
import com.playcity.bridge.api.BridgeEventDescriptor;
import com.playcity.bridge.api.BridgeEventSensitivity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BridgeApiValueTest {
    @Test
    void requestPayloadIsDeeplyImmutableAndDetachedFromCallerData() {
        List<Object> coordinates = new ArrayList<>(List.of(1, 2, 3));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("coordinates", coordinates);

        BridgeActionRequest request = new BridgeActionRequest("request-1", "example:action", source);
        coordinates.add(4);
        source.put("other", true);

        assertEquals(List.of(1, 2, 3), request.payload().get("coordinates"));
        assertEquals(false, request.payload().containsKey("other"));
        assertThrows(UnsupportedOperationException.class, () -> request.payload().put("x", 1));
    }

    @Test
    void callerSupportsExactGlobalAndPrefixScopes() {
        BridgeCaller caller = new BridgeCaller(
            "web-1",
            BridgeCaller.Type.HTTP,
            Set.of("minecraft.player_status", "world.*")
        );

        assertTrue(caller.allows("minecraft.player_status"));
        assertTrue(caller.allows("world.fill_region"));
        assertEquals(false, caller.allows("minecraft.teleport_player"));
    }

    @Test
    void descriptorRejectsUnstableNames() {
        assertThrows(IllegalArgumentException.class, () -> new BridgeActionDescriptor(
            "Contains Spaces",
            "1",
            "Invalid action",
            BridgeActionRisk.READ_ONLY
        ));
    }

    @Test
    void eventValuesValidateContractsAndDetachNestedData() {
        BridgeEventDescriptor descriptor = new BridgeEventDescriptor(
            "example.changed",
            "1",
            "A stable event contract.",
            BridgeEventSensitivity.GENERAL
        );
        List<Object> nested = new ArrayList<>(List.of("first"));
        BridgeEvent event = new BridgeEvent("instance", 1L, descriptor.name(), descriptor.version(), 1L, Map.of("nested", nested));
        nested.add("late");

        assertEquals(List.of("first"), event.data().get("nested"));
        assertThrows(IllegalArgumentException.class, () -> new BridgeEventDescriptor(
            "Contains Spaces",
            "1",
            "Invalid event",
            BridgeEventSensitivity.GENERAL
        ));
    }
}
