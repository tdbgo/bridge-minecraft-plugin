package com.playcity.bridge;

import com.google.gson.JsonObject;
import com.playcity.bridge.api.BridgeActionRequest;
import com.playcity.bridge.api.BridgeActionResult;
import com.playcity.bridge.api.BridgeEvent;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class DefaultBridgeRelayRuntime implements BridgeRelayRuntime {
    private final BridgePlugin plugin;

    DefaultBridgeRelayRuntime(BridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String bridgeVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public String serverVersion() {
        return plugin.getServer().getVersion();
    }

    @Override
    public Set<String> capabilities() {
        return plugin.bridgeApi().actionNames();
    }

    @Override
    public Set<String> eventCapabilities() {
        return plugin.bridgeApi().eventDescriptors().keySet();
    }

    @Override
    public AutoCloseable subscribeEvents(Consumer<BridgeEvent> listener) {
        return plugin.bridgeApi().eventHub().subscribe(listener);
    }

    @Override
    public JsonObject status() {
        BridgeMetricsSnapshot snapshot = plugin.metricsSampler().latest();
        if (snapshot == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        return BridgeStatusSerializer.toJson(
            snapshot,
            plugin.metricsSampler().isStale(snapshot, now),
            bridgeVersion(),
            now
        );
    }

    @Override
    public String metrics() {
        BridgeMetricsSnapshot snapshot = plugin.metricsSampler().latest();
        if (snapshot == null) {
            return "";
        }
        return PrometheusMetricsFormatter.format(
            snapshot,
            plugin.metricsSampler().isStale(snapshot, System.currentTimeMillis()),
            bridgeVersion(),
            plugin.metricsRegistry().snapshot(),
            plugin.bridgeConfig().metricsNamespace()
        );
    }

    @Override
    public CompletableFuture<BridgeActionResult> execute(BridgeActionRequest request) {
        return plugin.bridgeApi().execute(request);
    }
}
