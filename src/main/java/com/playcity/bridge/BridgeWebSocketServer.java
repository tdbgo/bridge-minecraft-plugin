package com.playcity.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.playcity.bridge.api.BridgeEvent;
import com.playcity.bridge.api.BridgeEventDescriptor;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

final class BridgeWebSocketServer extends WebSocketServer {
    private static final String EVENTS_PATH = "/v1/events";
    private static final int CLOSE_POLICY = 1008;
    private static final int CLOSE_TOO_BIG = 1009;
    private static final int CLOSE_RETRY_LATER = 1013;

    private final BridgePlugin plugin;
    private final DefaultBridgeApi api;
    private final BridgeHttpAccessController accessController;
    private final Map<WebSocket, EventSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService dispatcher = Executors.newScheduledThreadPool(2, new EventThreadFactory());
    private final AtomicBoolean started = new AtomicBoolean();

    BridgeWebSocketServer(
        BridgePlugin plugin,
        DefaultBridgeApi api,
        BridgeHttpAccessController accessController
    ) {
        super(new InetSocketAddress(plugin.bridgeConfig().eventsHost(), plugin.bridgeConfig().eventsPort()));
        this.plugin = plugin;
        this.api = api;
        this.accessController = accessController;
        setReuseAddr(true);
        setConnectionLostTimeout(plugin.bridgeConfig().eventConnectionLostSeconds());
    }

    boolean isStarted() {
        return started.get();
    }

    void shutdown() {
        for (EventSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        try {
            stop(1000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        dispatcher.shutdownNow();
        started.set(false);
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        if (!EVENTS_PATH.equals(path(handshake.getResourceDescriptor()))) {
            connection.close(CLOSE_POLICY, "not_found");
            return;
        }
        String token = bearerToken(handshake.getFieldValue("Authorization"));
        if (token.isBlank()) {
            token = normalized(handshake.getFieldValue("X-Bridge-Token"));
        }
        BridgeHttpAccessController.Authentication authentication = accessController.authenticate(token);
        if (!authentication.authenticated()) {
            int closeCode = authentication.status() >= 500 ? CLOSE_RETRY_LATER : CLOSE_POLICY;
            connection.close(closeCode, authentication.code());
            return;
        }
        BridgeHttpAccessController.EventAuthorization authorization = accessController.authorizeEventConnection(
            authentication.session(),
            System.currentTimeMillis()
        );
        if (!authorization.allowed()) {
            int closeCode = authorization.status() == 429 ? CLOSE_RETRY_LATER : CLOSE_POLICY;
            connection.close(closeCode, authorization.code());
            return;
        }
        EventSession session = new EventSession(connection, authorization.permit());
        sessions.put(connection, session);
        session.enqueue(hello(session));
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        EventSession session = sessions.get(connection);
        if (session == null) {
            connection.close(CLOSE_POLICY, "unauthorized");
            return;
        }
        if (message.getBytes(StandardCharsets.UTF_8).length > plugin.bridgeConfig().eventMaxMessageBytes()) {
            connection.close(CLOSE_TOO_BIG, "message_too_large");
            return;
        }
        if (!accessController.consumeEventMessage(session.permit.session(), System.currentTimeMillis())) {
            connection.close(CLOSE_RETRY_LATER, "rate_limited");
            return;
        }
        JsonObject request;
        try {
            JsonElement parsed = JsonParser.parseString(message);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException();
            }
            request = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            session.enqueue(error("invalid_json", "WebSocket message must be a JSON object."));
            return;
        }
        if (!"subscribe".equals(string(request, "type"))) {
            session.enqueue(error("unsupported_message", "Only subscribe messages are accepted."));
            return;
        }
        subscribe(session, request);
    }

    @Override
    public void onMessage(WebSocket connection, ByteBuffer message) {
        connection.close(CLOSE_POLICY, "binary_not_supported");
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        EventSession session = sessions.remove(connection);
        if (session != null) {
            session.close();
        }
    }

    @Override
    public void onError(WebSocket connection, Exception exception) {
        if (connection == null) {
            plugin.onWebSocketStartFailed(this);
        }
        plugin.getLogger().log(Level.FINE, "Bridge WebSocket transport error.", exception);
    }

    @Override
    public void onStart() {
        started.set(true);
        plugin.onWebSocketStarted(this);
        plugin.getLogger().info(
            "Bridge WebSocket events listening on "
                + plugin.bridgeConfig().eventsHost() + ":" + plugin.bridgeConfig().eventsPort()
        );
    }

    private void subscribe(EventSession session, JsonObject request) {
        Set<String> requestedRules = eventRules(request.get("events"));
        if (!accessController.authorizeEventSubscription(session.permit.session(), requestedRules)) {
            session.enqueue(error("event_scope_forbidden", "Requested event subscription exceeds caller scope."));
            return;
        }
        String instanceId = string(request, "instanceId");
        long afterSequence = longValue(request, "afterSequence", 0L);
        if (afterSequence < 0L) {
            session.enqueue(error("invalid_sequence", "afterSequence must not be negative."));
            return;
        }

        session.beginSubscription(requestedRules);
        BridgeEventHub.Subscription subscription = api.eventHub().subscribeFrom(
            instanceId,
            afterSequence,
            Math.min(
                plugin.bridgeConfig().eventReplayMaxBatch(),
                plugin.bridgeConfig().eventMaxPendingMessages() - 1
            ),
            event -> session.offerEvent(event)
        );
        BridgeEventHub.ReplayResult replay = subscription.replay();
        if (replay.status() != BridgeEventHub.ReplayStatus.OK) {
            closeQuietly(subscription.handle());
            session.cancelSubscription();
            String code = switch (replay.status()) {
                case INSTANCE_CHANGED -> "event_instance_changed";
                case LIMIT -> "event_replay_too_large";
                default -> "event_replay_gap";
            };
            JsonObject error = error(code, "Requested event replay cursor is unavailable.");
            error.addProperty("instanceId", api.eventHub().instanceId());
            error.addProperty("oldestSequence", replay.oldestSequence());
            error.addProperty("newestSequence", replay.newestSequence());
            session.enqueue(error);
            return;
        }
        JsonObject acknowledged = new JsonObject();
        acknowledged.addProperty("type", "subscribed");
        acknowledged.addProperty("instanceId", api.eventHub().instanceId());
        acknowledged.addProperty("newestSequence", replay.newestSequence());
        JsonArray events = new JsonArray();
        requestedRules.forEach(events::add);
        acknowledged.add("events", events);
        session.activateSubscription(subscription.handle(), acknowledged, replay.events());
    }

    private JsonObject hello(EventSession session) {
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("protocolVersion", 1);
        hello.addProperty("instanceId", api.eventHub().instanceId());
        hello.addProperty("bridgeVersion", plugin.getPluginMeta().getVersion());
        JsonArray events = new JsonArray();
        api.eventDescriptors().values().stream()
            .filter(descriptor -> session.permit.session().allowsEvent(descriptor.name()))
            .sorted(java.util.Comparator.comparing(BridgeEventDescriptor::name))
            .forEach(descriptor -> events.add(descriptor(descriptor)));
        hello.add("events", events);
        return hello;
    }

    private static JsonObject descriptor(BridgeEventDescriptor descriptor) {
        JsonObject json = new JsonObject();
        json.addProperty("name", descriptor.name());
        json.addProperty("version", descriptor.version());
        json.addProperty("description", descriptor.description());
        json.addProperty("sensitivity", descriptor.sensitivity().name().toLowerCase(Locale.ROOT));
        return json;
    }

    private static JsonObject eventJson(BridgeEvent event) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "event");
        json.addProperty("instanceId", event.instanceId());
        json.addProperty("sequence", event.sequence());
        json.addProperty("event", event.name());
        json.addProperty("version", event.version());
        json.addProperty("publishedAt", event.publishedAtEpochMillis());
        json.add("data", BridgeJson.toJsonObject(event.data()));
        return json;
    }

    private static JsonObject error(String code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("code", code);
        error.addProperty("message", message);
        return error;
    }

    private static Set<String> eventRules(JsonElement value) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > 64) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                return Set.of();
            }
            String rule = item.getAsString().trim().toLowerCase(Locale.ROOT);
            if (!BridgeScopeRules.validRule(rule)) {
                return Set.of();
            }
            result.add(rule);
        }
        return Set.copyOf(result);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return "";
        }
        return value.getAsString().trim();
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        try {
            return value == null ? fallback : value.getAsLong();
        } catch (RuntimeException exception) {
            return -1L;
        }
    }

    private static String bearerToken(String authorization) {
        String value = normalized(authorization);
        return value.toLowerCase(Locale.ROOT).startsWith("bearer ")
            ? value.substring("bearer ".length()).trim()
            : "";
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String path(String resource) {
        int query = resource == null ? -1 : resource.indexOf('?');
        return query < 0 ? normalized(resource) : resource.substring(0, query);
    }

    private static void closeQuietly(AutoCloseable handle) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (Exception ignored) {
        }
    }

    private final class EventSession {
        private final WebSocket connection;
        private final BridgeHttpAccessController.EventPermit permit;
        private final ArrayDeque<String> pending = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean closeRequested = new AtomicBoolean();
        private AutoCloseable subscription;
        private Set<String> requestedRules = Set.of();
        private final ArrayDeque<BridgeEvent> deferredEvents = new ArrayDeque<>();
        private boolean replaying;
        private boolean draining;

        private EventSession(WebSocket connection, BridgeHttpAccessController.EventPermit permit) {
            this.connection = connection;
            this.permit = permit;
        }

        private synchronized void beginSubscription(Set<String> rules) {
            closeQuietly(subscription);
            subscription = null;
            requestedRules = Set.copyOf(rules);
            deferredEvents.clear();
            replaying = true;
        }

        private synchronized void cancelSubscription() {
            requestedRules = Set.of();
            deferredEvents.clear();
            replaying = false;
        }

        private synchronized void activateSubscription(
            AutoCloseable replacement,
            JsonObject acknowledged,
            java.util.List<BridgeEvent> replayEvents
        ) {
            subscription = replacement;
            enqueueLocked(acknowledged.toString());
            replayEvents.forEach(event -> enqueueLocked(eventJson(event).toString()));
            deferredEvents.stream()
                .sorted(java.util.Comparator.comparingLong(BridgeEvent::sequence))
                .forEach(event -> enqueueLocked(eventJson(event).toString()));
            deferredEvents.clear();
            replaying = false;
        }

        private void offerEvent(BridgeEvent event) {
            Set<String> filters;
            synchronized (this) {
                filters = requestedRules;
                if (permit.session().allowsEvent(event.name()) && BridgeScopeRules.matches(filters, event.name()) && replaying) {
                    if (deferredEvents.size() >= plugin.bridgeConfig().eventMaxPendingMessages()) {
                        requestClose(CLOSE_RETRY_LATER, "event_backpressure");
                    } else {
                        deferredEvents.addLast(event);
                    }
                    return;
                }
            }
            if (permit.session().allowsEvent(event.name()) && BridgeScopeRules.matches(filters, event.name())) {
                enqueue(eventJson(event));
            }
        }

        private void enqueue(JsonObject message) {
            enqueue(message.toString());
        }

        private synchronized void enqueue(String message) {
            enqueueLocked(message);
        }

        private void enqueueLocked(String message) {
            if (closed.get()) {
                return;
            }
            if (message.getBytes(StandardCharsets.UTF_8).length > plugin.bridgeConfig().eventMaxMessageBytes()) {
                requestClose(CLOSE_TOO_BIG, "event_message_too_large");
                return;
            }
            if (pending.size() >= plugin.bridgeConfig().eventMaxPendingMessages()) {
                requestClose(CLOSE_RETRY_LATER, "event_backpressure");
                return;
            }
            pending.addLast(message);
            if (!draining) {
                draining = true;
                dispatcher.execute(this::drain);
            }
        }

        private void drain() {
            while (true) {
                String message;
                synchronized (this) {
                    message = pending.pollFirst();
                    if (message == null) {
                        draining = false;
                        return;
                    }
                }
                if (closed.get()) {
                    return;
                }
                if (connection.hasBufferedData()) {
                    synchronized (this) {
                        pending.addFirst(message);
                    }
                    dispatcher.schedule(this::drain, 10L, TimeUnit.MILLISECONDS);
                    return;
                }
                try {
                    connection.send(message);
                } catch (RuntimeException exception) {
                    requestClose(CLOSE_RETRY_LATER, "event_send_failed");
                    return;
                }
            }
        }

        private void requestClose(int code, String reason) {
            if (!closeRequested.compareAndSet(false, true)) {
                return;
            }
            try {
                dispatcher.execute(() -> connection.close(code, reason));
            } catch (RejectedExecutionException ignored) {
                // The transport is already shutting down.
            }
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (this) {
                pending.clear();
                deferredEvents.clear();
                closeQuietly(subscription);
                subscription = null;
            }
            permit.close();
        }
    }

    private static final class EventThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "bridge-events-dispatch-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
