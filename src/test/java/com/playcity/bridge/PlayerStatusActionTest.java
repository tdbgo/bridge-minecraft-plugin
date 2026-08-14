package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playcity.bridge.api.BridgeActionContext;
import com.playcity.bridge.api.BridgeActionRequest;
import com.playcity.bridge.api.BridgeActionResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

final class PlayerStatusActionTest {
    private static final UUID PLAYER_UUID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    void returnsOnlinePlayerSnapshotOnSyncContext() {
        AtomicReference<UUID> resolvedUuid = new AtomicReference<>();
        PlayerStatusAction action = new PlayerStatusAction(uuid -> {
            resolvedUuid.set(uuid);
            return new PlayerStatusSnapshot(uuid, "CurrentName", "block16");
        });
        RecordingContext context = new RecordingContext();

        BridgeActionResult result = action.execute(request("RequestedName"), context).join();

        assertTrue(result.ok());
        assertEquals(200, result.status());
        assertEquals(1, context.syncCalls.get());
        assertEquals(PLAYER_UUID, resolvedUuid.get());
        assertEquals(true, result.data().get("online"));
        Map<String, Object> player = BridgePayload.object(result.data(), "player");
        assertEquals(PLAYER_UUID.toString(), player.get("uuid"));
        assertEquals("CurrentName", player.get("username"));
        assertEquals("block16", player.get("world"));
    }

    @Test
    void returnsSuccessfulOfflineStateWithoutPlayerData() {
        PlayerStatusAction action = new PlayerStatusAction(uuid -> null);
        RecordingContext context = new RecordingContext();

        BridgeActionResult result = action.execute(request(null), context).join();

        assertTrue(result.ok());
        assertEquals(false, result.data().get("online"));
        assertFalse(result.data().containsKey("player"));
        assertEquals(1, context.syncCalls.get());
    }

    @Test
    void rejectsMissingOrNonCanonicalUuidBeforeScheduling() {
        AtomicInteger resolverCalls = new AtomicInteger();
        PlayerStatusAction action = new PlayerStatusAction(uuid -> {
            resolverCalls.incrementAndGet();
            return null;
        });
        RecordingContext context = new RecordingContext();

        Map<String, Object> missingTarget = basePayload();
        BridgeActionResult missing = action.execute(actionRequest(missingTarget), context).join();
        assertEquals("target_player_required", missing.code());

        Map<String, Object> malformedTarget = Map.of("uuid", "1-1-1-1-1");
        Map<String, Object> malformedPayload = basePayload();
        malformedPayload.put("targetPlayer", malformedTarget);
        BridgeActionResult malformed = action.execute(actionRequest(malformedPayload), context).join();
        assertEquals("invalid_player_uuid", malformed.code());

        assertEquals(0, context.syncCalls.get());
        assertEquals(0, resolverCalls.get());
    }

    @Test
    void rejectsUnsupportedAndInvalidOptionalFields() {
        PlayerStatusAction action = new PlayerStatusAction(uuid -> null);
        RecordingContext context = new RecordingContext();

        Map<String, Object> unsupported = new LinkedHashMap<>(request(null).payload());
        unsupported.put("position", "not-allowed");
        BridgeActionResult unsupportedResult = action.execute(actionRequest(unsupported), context).join();
        assertEquals("invalid_player_status_payload", unsupportedResult.code());

        Map<String, Object> invalidSource = new LinkedHashMap<>(request(null).payload());
        invalidSource.put("source", "contains whitespace");
        BridgeActionResult invalidSourceResult = action.execute(actionRequest(invalidSource), context).join();
        assertEquals("invalid_player_status_payload", invalidSourceResult.code());

        Map<String, Object> invalidUsername = new LinkedHashMap<>(request(null).payload());
        Map<String, Object> invalidTarget = new LinkedHashMap<>(BridgePayload.object(invalidUsername, "targetPlayer"));
        invalidTarget.put("username", " ");
        invalidUsername.put("targetPlayer", invalidTarget);
        BridgeActionResult invalidUsernameResult = action.execute(actionRequest(invalidUsername), context).join();
        assertEquals("invalid_player_status_payload", invalidUsernameResult.code());

        assertEquals(0, context.syncCalls.get());
    }

    private static BridgeActionRequest request(String username) {
        Map<String, Object> payload = basePayload();
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("uuid", PLAYER_UUID.toString());
        if (username != null) {
            target.put("username", username);
        }
        payload.put("targetPlayer", target);
        return actionRequest(payload);
    }

    private static Map<String, Object> basePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", "maps.move_here.availability.1");
        payload.put("action", "minecraft.player_status");
        payload.put("source", "maps.move_here.availability");
        return payload;
    }

    private static BridgeActionRequest actionRequest(Map<String, ?> payload) {
        return new BridgeActionRequest(
            "maps.move_here.availability.1",
            "minecraft.player_status",
            payload
        );
    }

    private static final class RecordingContext implements BridgeActionContext {
        private final AtomicInteger syncCalls = new AtomicInteger();

        @Override
        public Plugin plugin() {
            return null;
        }

        @Override
        public <T> CompletableFuture<T> callSync(Callable<T> callable) {
            syncCalls.incrementAndGet();
            try {
                return CompletableFuture.completedFuture(callable.call());
            } catch (Exception exception) {
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(exception);
                return failed;
            }
        }
    }
}
