package com.playcity.bridge;

import com.playcity.bridge.api.BridgeAction;
import com.playcity.bridge.api.BridgeActionDescriptor;
import com.playcity.bridge.api.BridgeActionRequest;
import com.playcity.bridge.api.BridgeActionResult;
import com.playcity.bridge.api.BridgeApi;
import com.playcity.bridge.api.BridgeEvent;
import com.playcity.bridge.api.BridgeEventDescriptor;
import com.playcity.bridge.api.BridgeEventPublisher;
import com.playcity.bridge.api.BridgeRegistration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.plugin.Plugin;

public final class DefaultBridgeApi implements BridgeApi {
    private final BridgePlugin plugin;
    private final Map<String, RegisteredAction> actions = new ConcurrentHashMap<>();
    private final Map<String, EventPublisherRegistration> events = new ConcurrentHashMap<>();
    private final IdempotencyStore idempotencyStore = new IdempotencyStore();
    private final BridgeEventHub eventHub;

    public DefaultBridgeApi(BridgePlugin plugin) {
        this.plugin = plugin;
        this.eventHub = new BridgeEventHub(plugin.getLogger());
    }

    @Override
    public BridgeEventPublisher registerEvent(Plugin owner, BridgeEventDescriptor descriptor) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(descriptor, "descriptor");
        EventPublisherRegistration registration = new EventPublisherRegistration(owner, descriptor);
        if (events.putIfAbsent(descriptor.name(), registration) != null) {
            throw new IllegalStateException("Bridge event is already registered: " + descriptor.name());
        }
        return registration;
    }

    @Override
    public BridgeRegistration registerAction(Plugin owner, BridgeActionDescriptor descriptor, BridgeAction action) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(action, "action");
        String actionName = normalizeActionName(descriptor.name());
        if (actionName.isBlank()) {
            throw new IllegalArgumentException("Invalid Bridge action name.");
        }
        Registration registration = new Registration(owner, descriptor);
        RegisteredAction registered = new RegisteredAction(
            owner,
            descriptor,
            action,
            new DefaultBridgeActionContext(plugin, owner),
            registration
        );
        if (actions.putIfAbsent(actionName, registered) != null) {
            throw new IllegalStateException("Bridge action is already registered: " + actionName);
        }
        registration.registered = registered;
        return registration;
    }

    void unregisterAll(Plugin owner) {
        actions.entrySet().removeIf(entry -> entry.getValue().owner() == owner);
        events.entrySet().removeIf(entry -> entry.getValue().owner == owner);
    }

    @Override
    public Set<String> actionNames() {
        return Set.copyOf(actions.keySet());
    }

    @Override
    public Map<String, BridgeActionDescriptor> actionDescriptors() {
        Map<String, BridgeActionDescriptor> result = new LinkedHashMap<>();
        actions.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> result.put(entry.getKey(), entry.getValue().descriptor()));
        return Map.copyOf(result);
    }

    @Override
    public Map<String, BridgeEventDescriptor> eventDescriptors() {
        Map<String, BridgeEventDescriptor> result = new LinkedHashMap<>();
        events.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> result.put(entry.getKey(), entry.getValue().descriptor));
        return Map.copyOf(result);
    }

    BridgeEventHub eventHub() {
        return eventHub;
    }

    @Override
    public CompletableFuture<BridgeActionResult> execute(BridgeActionRequest request) {
        if (request == null) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(400, "invalid_request", "Action request is required.")
            );
        }

        String requestId = normalizeRequestId(request.requestId());
        if (requestId.isBlank()) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(400, "invalid_request_id", "requestId is required.")
            );
        }

        String actionName = normalizeActionName(request.actionName());
        if (!request.caller().allows(actionName)) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(403, "action_forbidden", "Caller is not allowed to execute this Bridge action.")
            );
        }
        RegisteredAction registered = actions.get(actionName);
        if (registered == null) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(404, "unknown_action", "Unknown Bridge action.")
            );
        }

        BridgeActionRequest normalized = new BridgeActionRequest(
            requestId,
            actionName,
            request.payload(),
            request.caller()
        );
        BridgeConfig config = plugin.bridgeConfig();
        long now = System.currentTimeMillis();
        String fingerprint = BridgeRequestFingerprint.create(actionName, normalized.payload());
        String idempotencyKey = request.caller().type().name() + ":" + request.caller().id() + ":" + requestId;
        IdempotencyStore.Claim claim = idempotencyStore.claim(
            idempotencyKey,
            fingerprint,
            now,
            config.requestCacheTtlMillis(),
            config.requestCacheMaxEntries()
        );
        if (claim.state() == IdempotencyStore.ClaimState.REPLAY) {
            return claim.future();
        }
        if (claim.state() == IdempotencyStore.ClaimState.CONFLICT) {
            plugin.getLogger().warning(
                "Rejected Bridge idempotency conflict: requestId=" + requestId + " action=" + actionName
                    + " caller=" + request.caller().id()
            );
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(409, "idempotency_conflict", "requestId was already used for a different action payload.")
            );
        }
        if (claim.state() == IdempotencyStore.ClaimState.CAPACITY_EXCEEDED) {
            plugin.getLogger().warning("Rejected Bridge action because the idempotency cache is full.");
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(503, "idempotency_capacity_exceeded", "Bridge request cache is temporarily full.")
            );
        }

        long startedAtNanos = System.nanoTime();
        CompletableFuture<BridgeActionResult> actionFuture;
        try {
            if (!registered.owner().isEnabled()) {
                actionFuture = CompletableFuture.completedFuture(
                    BridgeActionResult.failure(503, "action_provider_unavailable", "Action provider plugin is disabled.")
                );
            } else {
                actionFuture = registered.action().execute(normalized, registered.context());
            }
            if (actionFuture == null) {
                actionFuture = CompletableFuture.completedFuture(
                    BridgeActionResult.failure(500, "action_failed", "Bridge action returned no result.")
                );
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Bridge action failed before scheduling: " + actionName, throwable);
            actionFuture = CompletableFuture.completedFuture(
                BridgeActionResult.failure(500, "action_failed", "Bridge action failed.")
            );
        }

        actionFuture.handle((result, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().log(Level.WARNING, "Bridge action failed unexpectedly: " + actionName, throwable);
                return BridgeActionResult.failure(500, "action_failed", "Bridge action failed.");
            }
            return result == null
                ? BridgeActionResult.failure(500, "action_failed", "Bridge action returned no result.")
                : result;
        }).thenAccept(result -> {
            idempotencyStore.complete(
                claim,
                result,
                System.currentTimeMillis(),
                plugin.bridgeConfig().requestCacheTtlMillis()
            );
            long durationNanos = System.nanoTime() - startedAtNanos;
            plugin.metricsRegistry().recordAction(actionName, result.code(), durationNanos);
            long durationMillis = durationNanos / 1_000_000L;
            plugin.getLogger().info(
                "Bridge action completed: requestId=" + requestId
                    + " action=" + actionName
                    + " caller=" + request.caller().id()
                    + " status=" + result.status()
                    + " code=" + result.code()
                    + " durationMs=" + durationMillis
            );
        });
        return claim.future();
    }

    private static String normalizeActionName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_.:-]{1,128}") ? normalized : "";
    }

    private static String normalizeRequestId(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.matches("[A-Za-z0-9_.:-]{1,160}") ? normalized : "";
    }

    private record RegisteredAction(
        Plugin owner,
        BridgeActionDescriptor descriptor,
        BridgeAction action,
        DefaultBridgeActionContext context,
        Registration registration
    ) {
    }

    private final class Registration implements BridgeRegistration {
        private final Plugin owner;
        private final BridgeActionDescriptor descriptor;
        private volatile RegisteredAction registered;

        private Registration(Plugin owner, BridgeActionDescriptor descriptor) {
            this.owner = owner;
            this.descriptor = descriptor;
        }

        @Override
        public BridgeActionDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public Plugin owner() {
            return owner;
        }

        @Override
        public boolean active() {
            RegisteredAction current = registered;
            return current != null && actions.get(descriptor.name()) == current;
        }

        @Override
        public void close() {
            RegisteredAction current = registered;
            if (current != null && actions.remove(descriptor.name(), current)) {
                registered = null;
            }
        }
    }

    private final class EventPublisherRegistration implements BridgeEventPublisher {
        private final Plugin owner;
        private final BridgeEventDescriptor descriptor;

        private EventPublisherRegistration(Plugin owner, BridgeEventDescriptor descriptor) {
            this.owner = owner;
            this.descriptor = descriptor;
        }

        @Override
        public BridgeEventDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public Plugin owner() {
            return owner;
        }

        @Override
        public boolean active() {
            return events.get(descriptor.name()) == this;
        }

        @Override
        public BridgeEvent publish(Map<String, ?> data) {
            if (!active()) {
                throw new IllegalStateException("Bridge event publisher is no longer active: " + descriptor.name());
            }
            if (!owner.isEnabled()) {
                throw new IllegalStateException("Bridge event provider plugin is disabled: " + descriptor.name());
            }
            return eventHub.publish(
                descriptor,
                data,
                plugin.bridgeConfig().eventReplayMaxEvents(),
                plugin.bridgeConfig().eventMaxMessageBytes()
            );
        }

        @Override
        public void close() {
            events.remove(descriptor.name(), this);
        }
    }
}
