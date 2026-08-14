package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class BridgeRequestFingerprintTest {
    @Test
    void ignoresRoutingFieldsAndJsonObjectOrder() {
        JsonObject first = JsonParser.parseString("""
            {"requestId":"one","action":"minecraft.teleport_player","position":{"x":1,"z":2},"source":"maps"}
            """).getAsJsonObject();
        JsonObject second = JsonParser.parseString("""
            {"source":"maps","position":{"z":2,"x":1},"action":"minecraft.teleport_player","requestId":"two"}
            """).getAsJsonObject();

        assertEquals(
            BridgeRequestFingerprint.create("minecraft.teleport_player", BridgeJson.toMap(first)),
            BridgeRequestFingerprint.create("minecraft.teleport_player", BridgeJson.toMap(second))
        );
    }

    @Test
    void changesWhenActionPayloadChanges() {
        JsonObject first = JsonParser.parseString("{\"position\":{\"x\":1}}").getAsJsonObject();
        JsonObject second = JsonParser.parseString("{\"position\":{\"x\":2}}").getAsJsonObject();

        assertNotEquals(
            BridgeRequestFingerprint.create("minecraft.teleport_player", BridgeJson.toMap(first)),
            BridgeRequestFingerprint.create("minecraft.teleport_player", BridgeJson.toMap(second))
        );
    }
}
