package com.playcity.bridge;

import com.google.gson.JsonObject;
import com.playcity.bridge.api.BridgeActionRequest;
import com.playcity.bridge.api.BridgeActionResult;
import com.playcity.bridge.api.BridgeEvent;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

interface BridgeRelayRuntime {
    String bridgeVersion();

    String serverVersion();

    Set<String> capabilities();

    default Set<String> eventCapabilities() {
        return Set.of();
    }

    default AutoCloseable subscribeEvents(Consumer<BridgeEvent> listener) {
        return () -> { };
    }

    JsonObject status();

    String metrics();

    CompletableFuture<BridgeActionResult> execute(BridgeActionRequest request);
}
