package com.playcity.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.playcity.bridge.api.BridgeActionRequest;
import com.playcity.bridge.api.BridgeActionResult;
import com.playcity.bridge.api.BridgeCaller;
import com.playcity.bridge.api.BridgeEvent;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BridgeRelayClient {
    static final int PROTOCOL_VERSION = 1;
    private static final int METRICS_MAX_BYTES = 128 * 1024;
    private static final int PAIRING_RETRY_MILLIS = 10_000;

    private final BridgeRelayConfig config;
    private final BridgeRelayRuntime runtime;
    private final BridgeRelayHttpTransport transport;
    private final BridgeRelayIdentityStore.Identity identity;
    private final BridgeRelayExecutionJournal journal;
    private final BridgeRelayEventBuffer eventBuffer;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean eventOverflowWarningActive = new AtomicBoolean();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.stopped());
    private final ExecutorService executor;
    private Future<?> worker;
    private AutoCloseable eventSubscription;

    BridgeRelayClient(
        BridgeRelayConfig config,
        BridgeRelayRuntime runtime,
        BridgeRelayHttpTransport transport,
        BridgeRelayIdentityStore.Identity identity,
        BridgeRelayExecutionJournal journal,
        Logger logger
    ) {
        this.config = Objects.requireNonNull(config);
        this.runtime = Objects.requireNonNull(runtime);
        this.transport = Objects.requireNonNull(transport);
        this.identity = Objects.requireNonNull(identity);
        this.journal = Objects.requireNonNull(journal);
        this.logger = Objects.requireNonNull(logger);
        this.eventBuffer = new BridgeRelayEventBuffer(
            config.allowedEvents(),
            config.eventQueueSize(),
            config.eventBatchSize()
        );
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bridge-relay");
            thread.setDaemon(true);
            return thread;
        });
    }

    static BridgeRelayClient create(BridgePlugin plugin, boolean requireEventResync) throws IOException {
        BridgeRelayConfig config = plugin.relayConfig();
        BridgeRelayIdentityStore.Identity identity = BridgeRelayIdentityStore.loadOrCreate(config.identityFile());
        BridgeRelayExecutionJournal journal = BridgeRelayExecutionJournal.open(
            config.identityFile().resolveSibling("relay-executions.json"),
            plugin.bridgeConfig().requestCacheTtlMillis(),
            plugin.bridgeConfig().requestCacheMaxEntries()
        );
        BridgeRelayClient client = new BridgeRelayClient(
            config,
            new DefaultBridgeRelayRuntime(plugin),
            new BridgeRelayHttpTransport.JavaNet(config.connectTimeoutMillis()),
            identity,
            journal,
            plugin.getLogger()
        );
        if (requireEventResync) {
            client.eventBuffer.requireResyncOnNextEvent();
        }
        return client;
    }

    public void start() {
        if (!config.enabled()) {
            status.set(new Status(State.DISABLED, "", "", null));
            return;
        }
        if (!config.valid()) {
            status.set(new Status(State.MISCONFIGURED, config.issue(), "", null));
            return;
        }
        if (running.compareAndSet(false, true)) {
            if (!config.allowedEvents().isEmpty()) {
                try {
                    eventSubscription = runtime.subscribeEvents(event -> {
                        boolean dropped = eventBuffer.offer(event);
                        if (!eventBuffer.overflowed()) {
                            eventOverflowWarningActive.set(false);
                        }
                        if (dropped && eventOverflowWarningActive.compareAndSet(false, true)) {
                            logger.warning("Bridge relay event queue overflowed; the receiver must rebuild event state.");
                        }
                    });
                } catch (RuntimeException exception) {
                    logger.log(Level.WARNING, "Bridge relay event subscription failed; action polling will continue.", exception);
                }
            }
            worker = executor.submit(this::run);
        }
    }

    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.cancel(true);
            worker = null;
        }
        closeEventSubscription();
        executor.shutdownNow();
        status.set(Status.stopped());
    }

    public Status status() {
        return status.get();
    }

    int queuedEventCount() {
        return eventBuffer.size();
    }

    boolean eventQueueOverflowed() {
        return eventBuffer.overflowed();
    }

    private void run() {
        int backoffMillis = config.minBackoffMillis();
        while (running.get()) {
            try {
                Enrollment enrollment = enroll();
                if (!enrollment.approved()) {
                    updatePairing(enrollment.pairingCode(), enrollment.expiresAt());
                    sleep(PAIRING_RETRY_MILLIS);
                    continue;
                }
                status.set(new Status(State.CONNECTED, "", "", Instant.now()));
                backoffMillis = config.minBackoffMillis();
                pollOnce();
            } catch (PairingRequired exception) {
                status.set(new Status(State.PAIRING, exception.code, status.get().pairingCode(), Instant.now()));
                pause(PAIRING_RETRY_MILLIS);
            } catch (RelayFault exception) {
                status.set(new Status(exception.state, exception.code, status.get().pairingCode(), status.get().lastContact()));
                if (exception.state == State.AUTH_FAILED || exception.state == State.REVOKED) {
                    pause(config.maxBackoffMillis());
                } else {
                    pause(jitter(backoffMillis));
                    backoffMillis = Math.min(config.maxBackoffMillis(), backoffMillis * 2);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception exception) {
                if (running.get()) {
                    status.set(new Status(State.BACKOFF, "relay_connection_failed", status.get().pairingCode(), status.get().lastContact()));
                    logger.log(Level.FINE, "Bridge relay connection failed.", exception);
                    pause(jitter(backoffMillis));
                    backoffMillis = Math.min(config.maxBackoffMillis(), backoffMillis * 2);
                }
            }
        }
        status.set(Status.stopped());
    }

    private Enrollment enroll() throws IOException, InterruptedException, RelayFault {
        status.set(new Status(State.ENROLLING, "", status.get().pairingCode(), status.get().lastContact()));
        JsonObject body = baseEnvelope();
        body.addProperty("serverName", config.serverName());
        BridgeRelayHttpTransport.Response response = post("enroll", body, config.connectTimeoutMillis() + 5000);
        JsonObject json = parseObject(response.body());
        if (response.status() == 401) {
            throw new RelayFault(State.AUTH_FAILED, code(json, "relay_auth_failed"));
        }
        if (response.status() == 403 && "agent_revoked".equals(code(json, ""))) {
            throw new RelayFault(State.REVOKED, "agent_revoked");
        }
        if (response.status() != 200) {
            throw new RelayFault(State.BACKOFF, code(json, "relay_enroll_failed"));
        }
        String state = string(json, "state");
        return new Enrollment(
            "approved".equals(state),
            string(json, "pairingCode"),
            instant(json, "pairingExpiresAt")
        );
    }

    private void pollOnce() throws IOException, InterruptedException, RelayFault, PairingRequired {
        JsonObject body = baseEnvelope();
        JsonObject currentStatus = runtime.status();
        if (currentStatus != null) {
            body.add("status", currentStatus);
        }
        String metrics = runtime.metrics();
        if (!metrics.isBlank() && metrics.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= METRICS_MAX_BYTES) {
            body.addProperty("metrics", metrics);
        }
        BridgeRelayEventBuffer.Batch eventBatch = eventBuffer.batch();
        if (eventBatch.present()) {
            body.add("events", relayEvents(eventBatch));
        }

        BridgeRelayHttpTransport.Response response;
        try {
            response = post("poll", body, config.pollTimeoutMillis() + 5000);
        } catch (HttpTimeoutException exception) {
            return;
        }
        JsonObject json = parseObject(response.body());
        if (response.status() == 401) {
            throw new RelayFault(State.AUTH_FAILED, code(json, "relay_auth_failed"));
        }
        if (response.status() == 403) {
            String code = code(json, "pairing_pending");
            if ("agent_revoked".equals(code)) {
                throw new RelayFault(State.REVOKED, code);
            }
            throw new PairingRequired(code);
        }
        if (response.status() != 200) {
            throw new RelayFault(State.BACKOFF, code(json, "relay_poll_failed"));
        }
        acknowledgeEvents(json, eventBatch);
        status.set(new Status(State.CONNECTED, "", "", Instant.now()));
        JsonElement actionElement = json.get("action");
        if (actionElement == null || actionElement.isJsonNull()) {
            return;
        }
        if (!actionElement.isJsonObject()) {
            throw new RelayFault(State.BACKOFF, "relay_action_invalid");
        }
        executeLeasedAction(actionElement.getAsJsonObject());
    }

    private void executeLeasedAction(JsonObject action) throws InterruptedException, RelayFault, IOException {
        String leaseId = string(action, "leaseId");
        JsonObject requestJson = object(action, "request");
        String requestId = string(requestJson, "requestId");
        String actionName = string(requestJson, "action");
        if (leaseId.isBlank() || requestId.isBlank() || actionName.isBlank()) {
            throw new RelayFault(State.BACKOFF, "relay_action_invalid");
        }

        Map<String, Object> requestPayload = BridgeJson.toMapExcluding(requestJson, "requestId", "action");

        BridgeActionRequest request = new BridgeActionRequest(
            requestId,
            actionName,
            requestPayload,
            new BridgeCaller(config.serverName(), BridgeCaller.Type.RELAY, config.allowedActions())
        );
        BridgeRelayExecutionJournal.Claim claim;
        try {
            claim = journal.claim(
                requestId,
                BridgeRequestFingerprint.create(actionName, requestPayload),
                System.currentTimeMillis()
            );
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Bridge relay execution journal is unavailable; action was not executed.", exception);
            postResult(
                leaseId,
                requestId,
                BridgeActionResult.failure(503, "relay_journal_unavailable", "Bridge could not durably claim the action.")
            );
            return;
        }

        BridgeActionResult result = BridgeActionResult.failure(500, "action_failed", "Bridge action failed.");
        boolean shouldCompleteJournal = false;
        switch (claim.state()) {
            case REPLAY -> result = claim.result();
            case CONFLICT -> result = BridgeActionResult.failure(
                409,
                "idempotency_conflict",
                "requestId was already used for a different action payload."
            );
            case OUTCOME_UNKNOWN -> result = BridgeActionResult.failure(
                503,
                "action_outcome_unknown",
                "A previous execution was interrupted after its durable claim; the action was not repeated."
            );
            case CAPACITY_EXCEEDED -> result = BridgeActionResult.failure(
                503,
                "idempotency_capacity_exceeded",
                "Bridge execution journal is temporarily full."
            );
            case OWNER -> {
                try {
                    result = runtime.execute(request).get(config.actionTimeoutMillis(), TimeUnit.MILLISECONDS);
                    shouldCompleteJournal = true;
                } catch (TimeoutException exception) {
                    result = BridgeActionResult.failure(
                        504,
                        "action_timeout",
                        "Bridge action result timed out; retry with the same requestId."
                    );
                } catch (ExecutionException exception) {
                    result = BridgeActionResult.failure(500, "action_failed", "Bridge action failed.");
                    shouldCompleteJournal = true;
                }
            }
        }

        if (shouldCompleteJournal) {
            try {
                journal.complete(requestId, result, System.currentTimeMillis());
            } catch (IOException exception) {
                logger.log(
                    Level.WARNING,
                    "Bridge action completed but its relay result could not be journaled: requestId=" + requestId,
                    exception
                );
            }
        }
        postResult(leaseId, requestId, result);
    }

    private void postResult(String leaseId, String requestId, BridgeActionResult result)
        throws IOException, InterruptedException, RelayFault {
        JsonObject resultBody = new JsonObject();
        resultBody.addProperty("protocolVersion", PROTOCOL_VERSION);
        resultBody.addProperty("leaseId", leaseId);
        resultBody.addProperty("requestId", requestId);
        resultBody.addProperty("httpStatus", result.status());
        resultBody.add("result", BridgeJson.resultToJson(result, requestId));
        BridgeRelayHttpTransport.Response response = post("result", resultBody, config.connectTimeoutMillis() + 5000);
        JsonObject responseJson = parseObject(response.body());
        if (response.status() == 401) {
            throw new RelayFault(State.AUTH_FAILED, code(responseJson, "relay_auth_failed"));
        }
        if (response.status() == 403 && "agent_revoked".equals(code(responseJson, ""))) {
            throw new RelayFault(State.REVOKED, "agent_revoked");
        }
        if (response.status() != 200) {
            throw new RelayFault(State.BACKOFF, code(responseJson, "relay_result_failed"));
        }
    }

    private BridgeRelayHttpTransport.Response post(String endpoint, JsonObject body, int timeoutMillis)
        throws IOException, InterruptedException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + identity.credential());
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "Bridge/" + runtime.bridgeVersion());
        headers.put("X-Bridge-Agent-Id", identity.agentId().toString());
        return transport.post(config.endpoint(endpoint), headers, body.toString(), timeoutMillis, config.maxResponseBytes());
    }

    private JsonObject baseEnvelope() {
        JsonObject body = new JsonObject();
        body.addProperty("protocolVersion", PROTOCOL_VERSION);
        body.addProperty("agentId", identity.agentId().toString());
        body.addProperty("bridgeVersion", runtime.bridgeVersion());
        body.addProperty("serverVersion", runtime.serverVersion());
        JsonArray capabilities = new JsonArray();
        runtime.capabilities().stream().sorted().forEach(capabilities::add);
        body.add("capabilities", capabilities);
        JsonArray eventCapabilities = new JsonArray();
        runtime.eventCapabilities().stream()
            .filter(config::allowsEvent)
            .sorted()
            .forEach(eventCapabilities::add);
        body.add("eventCapabilities", eventCapabilities);
        return body;
    }

    private static JsonObject relayEvents(BridgeRelayEventBuffer.Batch batch) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("instanceId", batch.instanceId());
        envelope.addProperty("overflowed", batch.overflowed());
        envelope.addProperty("droppedThroughSequence", batch.droppedThroughSequence());
        JsonArray items = new JsonArray();
        for (BridgeEvent event : batch.events()) {
            JsonObject item = new JsonObject();
            item.addProperty("sequence", event.sequence());
            item.addProperty("event", event.name());
            item.addProperty("version", event.version());
            item.addProperty("publishedAt", event.publishedAtEpochMillis());
            item.add("data", BridgeJson.toJsonObject(event.data()));
            items.add(item);
        }
        envelope.add("items", items);
        return envelope;
    }

    private void acknowledgeEvents(JsonObject response, BridgeRelayEventBuffer.Batch sent) throws RelayFault {
        JsonElement acknowledgementElement = response.get("eventAck");
        if (acknowledgementElement == null || acknowledgementElement.isJsonNull()) {
            return;
        }
        if (!sent.present() || !acknowledgementElement.isJsonObject()) {
            throw new RelayFault(State.BACKOFF, "relay_event_ack_invalid");
        }
        JsonObject acknowledgement = acknowledgementElement.getAsJsonObject();
        if (!eventBuffer.acknowledge(
            sent,
            string(acknowledgement, "instanceId"),
            longValue(acknowledgement, "throughSequence"),
            booleanValue(acknowledgement, "acceptGap")
        )) {
            throw new RelayFault(State.BACKOFF, "relay_event_ack_invalid");
        }
        if (!eventBuffer.overflowed()) {
            eventOverflowWarningActive.set(false);
        }
    }

    private void closeEventSubscription() {
        AutoCloseable subscription = eventSubscription;
        eventSubscription = null;
        if (subscription == null) {
            return;
        }
        try {
            subscription.close();
        } catch (Exception exception) {
            logger.log(Level.FINE, "Bridge relay event subscription did not close cleanly.", exception);
        }
    }

    private void updatePairing(String pairingCode, Instant expiresAt) {
        Status previous = status.get();
        status.set(new Status(State.PAIRING, "pairing_pending", pairingCode, Instant.now()));
        if (!pairingCode.isBlank() && !pairingCode.equals(previous.pairingCode())) {
            logger.info("Bridge relay pairing required: code=" + pairingCode + " expiresAt=" + expiresAt);
        }
    }

    private void sleep(int millis) throws InterruptedException {
        if (running.get()) {
            Thread.sleep(millis);
        }
    }

    private void pause(int millis) {
        try {
            sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    private static int jitter(int millis) {
        int range = Math.max(1, millis / 5);
        return Math.max(250, millis + ThreadLocalRandom.current().nextInt(-range, range + 1));
    }

    private static JsonObject parseObject(String body) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(body == null || body.isBlank() ? "{}" : body);
            if (!parsed.isJsonObject()) {
                throw new IOException("relay_response_invalid");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("relay_response_invalid", exception);
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        if (value == null || value.isJsonNull()) {
            return "";
        }
        try {
            return value.getAsString().trim();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String code(JsonObject body, String fallback) {
        String code = string(body, "code");
        return code.isBlank() ? fallback : code;
    }

    private static long longValue(JsonObject body, String key) {
        JsonElement value = body == null ? null : body.get(key);
        try {
            return value == null || !value.isJsonPrimitive() ? -1L : value.getAsLong();
        } catch (RuntimeException exception) {
            return -1L;
        }
    }

    private static boolean booleanValue(JsonObject body, String key) {
        JsonElement value = body == null ? null : body.get(key);
        try {
            return value != null && value.isJsonPrimitive() && value.getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static Instant instant(JsonObject body, String key) {
        try {
            return Instant.parse(string(body, key));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public enum State {
        DISABLED,
        ENROLLING,
        PAIRING,
        CONNECTED,
        BACKOFF,
        AUTH_FAILED,
        REVOKED,
        MISCONFIGURED,
        STOPPED
    }

    public record Status(State state, String code, String pairingCode, Instant lastContact) {
        static Status stopped() {
            return new Status(State.STOPPED, "", "", null);
        }
    }

    private record Enrollment(boolean approved, String pairingCode, Instant expiresAt) {
    }

    private static final class RelayFault extends Exception {
        private final State state;
        private final String code;

        private RelayFault(State state, String code) {
            this.state = state;
            this.code = code;
        }
    }

    private static final class PairingRequired extends Exception {
        private final String code;

        private PairingRequired(String code) {
            this.code = code;
        }
    }
}
