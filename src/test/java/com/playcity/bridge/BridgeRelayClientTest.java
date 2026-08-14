package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.playcity.bridge.api.BridgeActionRequest;
import com.playcity.bridge.api.BridgeActionResult;
import com.playcity.bridge.api.BridgeEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeRelayClientTest {
    private static final String CREDENTIAL = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDirectory;

    @Test
    void reportsPairingCodeWithoutSendingCredentialInJson() throws Exception {
        RecordingTransport transport = new RecordingTransport(Mode.PAIRING);
        BridgeRelayClient client = client(transport, new FakeRuntime());

        client.start();
        try {
            assertTrue(await(() -> client.status().state() == BridgeRelayClient.State.PAIRING));
            assertEquals("AB12CD34", client.status().pairingCode());
            Request enroll = transport.requests.get(0);
            assertEquals("Bearer " + CREDENTIAL, enroll.headers().get("Authorization"));
            assertFalse(enroll.body().contains(CREDENTIAL));
            assertTrue(enroll.body().contains("\"protocolVersion\":1"));
        } finally {
            client.stop();
        }
    }

    @Test
    void executesTypedLeaseAndPostsCorrelatedResult() throws Exception {
        RecordingTransport transport = new RecordingTransport(Mode.ACTION);
        FakeRuntime runtime = new FakeRuntime();
        BridgeRelayClient client = client(transport, runtime);

        client.start();
        try {
            assertTrue(transport.resultPosted.await(2, TimeUnit.SECONDS));
            assertNotNull(runtime.executed);
            assertEquals("maps.move.1", runtime.executed.requestId());
            assertEquals("minecraft.teleport_player", runtime.executed.actionName());
            assertEquals("world", BridgePayload.object(runtime.executed.payload(), "position").get("world"));
            assertFalse(runtime.executed.payload().containsKey("requestId"));
            assertFalse(runtime.executed.payload().containsKey("action"));

            Request resultRequest = transport.requests.stream()
                .filter(request -> request.uri().getPath().endsWith("/result"))
                .findFirst()
                .orElseThrow();
            JsonObject result = JsonParser.parseString(resultRequest.body()).getAsJsonObject();
            assertEquals("lease-1", result.get("leaseId").getAsString());
            assertEquals("maps.move.1", result.get("requestId").getAsString());
            assertEquals(200, result.get("httpStatus").getAsInt());
            assertEquals("ok", result.getAsJsonObject("result").get("code").getAsString());
        } finally {
            client.stop();
        }
    }

    @Test
    void sendsScopedEventsThroughPollUntilTheReceiverAcknowledgesThem() throws Exception {
        RecordingTransport transport = new RecordingTransport(Mode.EVENT);
        FakeRuntime runtime = new FakeRuntime();
        BridgeRelayClient client = client(transport, runtime);

        client.start();
        try {
            assertTrue(await(() -> runtime.eventConsumer != null));
            runtime.publish(new BridgeEvent(
                "instance-1",
                41L,
                "minecraft.player_joined",
                "1",
                1_700_000_000_041L,
                Map.of("playerUuid", "00000000-0000-0000-0000-000000000002")
            ));

            assertTrue(transport.eventPosted.await(2, TimeUnit.SECONDS));
            Request poll = transport.requests.stream()
                .filter(request -> request.uri().getPath().endsWith("/poll") && request.body().contains("\"events\""))
                .findFirst()
                .orElseThrow();
            JsonObject events = JsonParser.parseString(poll.body()).getAsJsonObject().getAsJsonObject("events");
            assertEquals("instance-1", events.get("instanceId").getAsString());
            assertEquals(41L, events.getAsJsonArray("items").get(0).getAsJsonObject().get("sequence").getAsLong());
            assertEquals("minecraft.player_joined", events.getAsJsonArray("items").get(0).getAsJsonObject().get("event").getAsString());
            assertTrue(await(() -> client.queuedEventCount() == 0));
            assertFalse(client.eventQueueOverflowed());
        } finally {
            client.stop();
        }
    }

    private BridgeRelayClient client(RecordingTransport transport, FakeRuntime runtime) throws IOException {
        BridgeRelayConfig config = new BridgeRelayConfig(
            true,
            URI.create("https://block.example/api/bridge-agent/v1"),
            "playcity-block",
            tempDirectory.resolve("identity.json"),
            1000,
            5000,
            1000,
            250,
            1000,
            262_144,
            Set.of("*"),
            Set.of("minecraft.*"),
            16,
            8,
            ""
        );
        return new BridgeRelayClient(
            config,
            runtime,
            transport,
            new BridgeRelayIdentityStore.Identity(UUID.fromString("00000000-0000-0000-0000-000000000001"), CREDENTIAL),
            BridgeRelayExecutionJournal.open(
                tempDirectory.resolve("relay-executions.json"),
                TimeUnit.MINUTES.toMillis(5),
                128
            ),
            Logger.getLogger("BridgeRelayClientTest")
        );
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }

    private enum Mode {
        PAIRING,
        ACTION,
        EVENT
    }

    private record Request(URI uri, Map<String, String> headers, String body) {
    }

    private static final class RecordingTransport implements BridgeRelayHttpTransport {
        private final Mode mode;
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private final CountDownLatch resultPosted = new CountDownLatch(1);
        private final CountDownLatch eventPosted = new CountDownLatch(1);
        private int polls;

        private RecordingTransport(Mode mode) {
            this.mode = mode;
        }

        @Override
        public synchronized Response post(
            URI uri,
            Map<String, String> headers,
            String body,
            int timeoutMillis,
            int maxResponseBytes
        ) throws IOException, InterruptedException {
            requests.add(new Request(uri, Map.copyOf(headers), body));
            if (uri.getPath().endsWith("/enroll")) {
                if (mode == Mode.PAIRING) {
                    return new Response(200, "{\"state\":\"pending\",\"pairingCode\":\"AB12CD34\","
                        + "\"pairingExpiresAt\":\"2026-08-13T12:30:00Z\"}");
                }
                return new Response(200, "{\"state\":\"approved\"}");
            }
            if (uri.getPath().endsWith("/poll")) {
                polls++;
                if (mode == Mode.EVENT) {
                    JsonObject poll = JsonParser.parseString(body).getAsJsonObject();
                    if (poll.has("events")) {
                        JsonObject events = poll.getAsJsonObject("events");
                        JsonObject last = events.getAsJsonArray("items").get(events.getAsJsonArray("items").size() - 1)
                            .getAsJsonObject();
                        eventPosted.countDown();
                        return new Response(200, "{\"action\":null,\"eventAck\":{\"instanceId\":\""
                            + events.get("instanceId").getAsString()
                            + "\",\"throughSequence\":" + last.get("sequence").getAsLong()
                            + ",\"acceptGap\":true}}");
                    }
                    Thread.sleep(10L);
                    return new Response(200, "{\"action\":null}");
                }
                if (polls == 1 && mode == Mode.ACTION) {
                    return new Response(200, "{\"action\":{\"leaseId\":\"lease-1\",\"request\":{"
                        + "\"requestId\":\"maps.move.1\",\"action\":\"minecraft.teleport_player\","
                        + "\"targetPlayer\":{\"uuid\":\"00000000-0000-0000-0000-000000000002\"},"
                        + "\"position\":{\"world\":\"world\",\"x\":1,\"y\":2,\"z\":3}}}}");
                }
                Thread.sleep(30_000L);
                return new Response(200, "{\"action\":null}");
            }
            if (uri.getPath().endsWith("/result")) {
                resultPosted.countDown();
                return new Response(200, "{\"accepted\":true}");
            }
            throw new IOException("unexpected_endpoint");
        }
    }

    private static final class FakeRuntime implements BridgeRelayRuntime {
        private volatile BridgeActionRequest executed;
        private volatile Consumer<BridgeEvent> eventConsumer;

        @Override
        public String bridgeVersion() {
            return "0.3.0-SNAPSHOT";
        }

        @Override
        public String serverVersion() {
            return "Paper 26.2";
        }

        @Override
        public Set<String> capabilities() {
            return Set.of("minecraft.teleport_player");
        }

        @Override
        public Set<String> eventCapabilities() {
            return Set.of("minecraft.player_joined", "minecraft.player_left");
        }

        @Override
        public AutoCloseable subscribeEvents(Consumer<BridgeEvent> listener) {
            eventConsumer = listener;
            return () -> eventConsumer = null;
        }

        private void publish(BridgeEvent event) {
            Consumer<BridgeEvent> listener = eventConsumer;
            if (listener != null) {
                listener.accept(event);
            }
        }

        @Override
        public JsonObject status() {
            JsonObject status = new JsonObject();
            status.addProperty("observedAt", Instant.parse("2026-08-13T12:00:00Z").toString());
            return status;
        }

        @Override
        public String metrics() {
            return "playcity_bridge_up 1\n";
        }

        @Override
        public CompletableFuture<BridgeActionResult> execute(BridgeActionRequest request) {
            executed = request;
            return CompletableFuture.completedFuture(BridgeActionResult.success());
        }
    }
}
