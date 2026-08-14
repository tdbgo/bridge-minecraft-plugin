package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BridgeJsonTest {
    @Test
    void separatesTransportEnvelopeFromProviderPayload() {
        Map<String, Object> payload = BridgeJson.toMapExcluding(
            JsonParser.parseString("{\"requestId\":\"r1\",\"action\":\"example:run\",\"value\":42}")
                .getAsJsonObject(),
            "requestId",
            "action"
        );

        assertEquals(42, ((Number) payload.get("value")).intValue());
        assertFalse(payload.containsKey("requestId"));
        assertFalse(payload.containsKey("action"));
    }
}
