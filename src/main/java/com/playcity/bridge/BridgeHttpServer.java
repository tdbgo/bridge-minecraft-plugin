package com.playcity.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.playcity.bridge.api.BridgeActionRequest;
import com.playcity.bridge.api.BridgeActionResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class BridgeHttpServer {
    private final BridgePlugin plugin;
    private final DefaultBridgeApi api;
    private final BridgeHttpAccessController accessController;
    private final Gson gson = new Gson();
    private HttpServer server;
    private ExecutorService executor;

    public BridgeHttpServer(
        BridgePlugin plugin,
        DefaultBridgeApi api,
        BridgeHttpAccessController accessController
    ) {
        this.plugin = plugin;
        this.api = api;
        this.accessController = accessController;
    }

    public void start() throws IOException {
        BridgeConfig config = plugin.bridgeConfig();
        server = HttpServer.create(new InetSocketAddress(config.httpHost(), config.httpPort()), 0);
        server.createContext("/v1/health", this::handleHealth);
        server.createContext("/v1/actions", this::handleActions);
        server.createContext("/v1/status", this::handleStatus);
        server.createContext("/metrics", this::handleMetrics);
        executor = Executors.newFixedThreadPool(config.workerThreads(), new BridgeThreadFactory());
        server.setExecutor(executor);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!hasExactPath(exchange, "/v1/health")) {
            writeError(exchange, 404, "not_found", "HTTP endpoint was not found.");
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            writeMethodNotAllowed(exchange, "GET");
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.addProperty("service", "Bridge");
        json.addProperty("apiVersion", "v1");
        json.addProperty("version", plugin.getPluginMeta().getVersion());
        json.addProperty("time", Instant.now().toString());
        json.addProperty("tokenConfigured", plugin.bridgeConfig().hasUsableToken());
        json.addProperty("httpClientsConfigured", plugin.bridgeConfig().httpCredentials().size());
        json.addProperty("jwtEnabled", plugin.bridgeConfig().jwtConfig().enabled());
        json.addProperty("jwtReady", plugin.bridgeConfig().jwtConfig().valid() && accessController.jwtIssue().isBlank());
        json.addProperty("eventsEnabled", plugin.bridgeConfig().eventsEnabled());
        json.addProperty("eventsState", plugin.eventsState());
        json.addProperty("metricsEnabled", plugin.bridgeConfig().metricsEnabled());
        json.addProperty("metricsTokenConfigured", plugin.bridgeConfig().hasUsableMetricsToken());
        json.addProperty("metricsReady", plugin.metricsSampler().isReady());
        json.addProperty("relayEnabled", plugin.relayConfig().enabled());
        json.addProperty("relayState", plugin.relayStatus().state().name().toLowerCase(Locale.ROOT));
        json.addProperty("relayQueuedEvents", plugin.relayQueuedEventCount());
        json.addProperty("relayEventGap", plugin.relayEventQueueOverflowed());
        writeJson(exchange, 200, json);
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!prepareMetricsRequest(exchange, "/v1/status")) {
            return;
        }
        BridgeMetricsSnapshot snapshot = plugin.metricsSampler().latest();
        if (snapshot == null) {
            writeError(exchange, 503, "metrics_not_ready", "Bridge has not completed a metrics sample.");
            return;
        }
        long now = System.currentTimeMillis();
        writeJson(exchange, 200, BridgeStatusSerializer.toJson(
            snapshot,
            plugin.metricsSampler().isStale(snapshot, now),
            plugin.getPluginMeta().getVersion(),
            now
        ));
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        if (!prepareMetricsRequest(exchange, "/metrics")) {
            return;
        }
        BridgeMetricsSnapshot snapshot = plugin.metricsSampler().latest();
        if (snapshot == null) {
            writeError(exchange, 503, "metrics_not_ready", "Bridge has not completed a metrics sample.");
            return;
        }
        String body = PrometheusMetricsFormatter.format(
            snapshot,
            plugin.metricsSampler().isStale(snapshot, System.currentTimeMillis()),
            plugin.getPluginMeta().getVersion(),
            plugin.metricsRegistry().snapshot(),
            plugin.bridgeConfig().metricsNamespace()
        );
        writeText(exchange, 200, "text/plain; version=0.0.4; charset=utf-8", body);
    }

    private boolean prepareMetricsRequest(HttpExchange exchange, String path) throws IOException {
        if (!hasExactPath(exchange, path)) {
            writeError(exchange, 404, "not_found", "HTTP endpoint was not found.");
            return false;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            writeMethodNotAllowed(exchange, "GET");
            return false;
        }
        if (!plugin.bridgeConfig().metricsEnabled()) {
            writeError(exchange, 503, "metrics_disabled", "Bridge metrics are disabled.");
            return false;
        }
        return requireMetricsToken(exchange);
    }

    private void handleActions(HttpExchange exchange) throws IOException {
        if (!hasExactPath(exchange, "/v1/actions")) {
            writeError(exchange, 404, "not_found", "HTTP endpoint was not found.");
            return;
        }
        if ("GET".equals(exchange.getRequestMethod())) {
            handleActionDiscovery(exchange);
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            writeMethodNotAllowed(exchange, "GET, POST");
            return;
        }
        BridgeConfig config = plugin.bridgeConfig();
        if (!config.hasUsableToken()) {
            writeError(exchange, 503, "bridge_token_not_configured", "Bridge HTTP clients are not configured.");
            return;
        }
        BridgeHttpAccessController.Authentication authentication = accessController.authenticate(providedActionToken(exchange));
        if (!authentication.authenticated()) {
            writeError(exchange, authentication.status(), authentication.code(), authentication.message());
            return;
        }
        if (!isJsonContentType(exchange)) {
            writeError(exchange, 415, "unsupported_media_type", "Content-Type must be application/json.");
            return;
        }

        JsonObject body;
        try {
            String raw = readBody(exchange, plugin.bridgeConfig().maxRequestBytes());
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                writeError(exchange, 400, "json_object_required", "JSON object body is required.");
                return;
            }
            body = parsed.getAsJsonObject();
        } catch (PayloadTooLargeException exception) {
            writeError(exchange, 413, "payload_too_large", "Request body is too large.");
            return;
        } catch (RuntimeException exception) {
            writeError(exchange, 400, "invalid_json", "Request body is not valid JSON.");
            return;
        }

        String requestId = string(body, "requestId");
        String action = string(body, "action");
        BridgeHttpAccessController.Authorization authorization = accessController.authorize(
            authentication.session(),
            action,
            System.currentTimeMillis()
        );
        if (!authorization.allowed()) {
            writeError(exchange, authorization.status(), authorization.code(), authorization.message(), requestId);
            return;
        }
        try (BridgeHttpAccessController.Permit permit = authorization.permit()) {
            BridgeActionRequest request = new BridgeActionRequest(
                requestId,
                action,
                BridgeJson.toMapExcluding(body, "requestId", "action"),
                permit.caller()
            );
            CompletableFuture<BridgeActionResult> future = api.execute(request);
            BridgeActionResult result = future.get(plugin.bridgeConfig().actionTimeoutMillis(), TimeUnit.MILLISECONDS);
            writeJson(exchange, result.status(), BridgeJson.resultToJson(result, requestId));
        } catch (TimeoutException exception) {
            writeError(exchange, 504, "action_timeout", "Bridge action result timed out; retry with the same requestId.", requestId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writeError(exchange, 503, "server_stopping", "Bridge request was interrupted.", requestId);
        } catch (ExecutionException exception) {
            plugin.getLogger().warning("Bridge action future failed: requestId=" + requestId);
            writeError(exchange, 500, "action_failed", "Bridge action failed.", requestId);
        }
    }

    private void handleActionDiscovery(HttpExchange exchange) throws IOException {
        BridgeConfig config = plugin.bridgeConfig();
        if (!config.hasUsableToken()) {
            writeError(exchange, 503, "bridge_token_not_configured", "Bridge HTTP clients are not configured.");
            return;
        }
        BridgeHttpAccessController.Authentication authentication = accessController.authenticate(providedActionToken(exchange));
        if (!authentication.authenticated()) {
            writeError(exchange, authentication.status(), authentication.code(), authentication.message());
            return;
        }
        BridgeHttpAccessController.Authorization authorization = accessController.authorizeDiscovery(
            authentication.session(),
            System.currentTimeMillis()
        );
        if (!authorization.allowed()) {
            writeError(exchange, authorization.status(), authorization.code(), authorization.message());
            return;
        }
        try (BridgeHttpAccessController.Permit ignored = authorization.permit()) {
            JsonArray actions = new JsonArray();
            api.actionDescriptors().values().stream()
                .filter(descriptor -> authentication.session().caller().allows(descriptor.name()))
                .sorted(java.util.Comparator.comparing(com.playcity.bridge.api.BridgeActionDescriptor::name))
                .forEach(descriptor -> {
                    JsonObject action = new JsonObject();
                    action.addProperty("name", descriptor.name());
                    action.addProperty("version", descriptor.version());
                    action.addProperty("description", descriptor.description());
                    action.addProperty("risk", descriptor.risk().name().toLowerCase(Locale.ROOT));
                    actions.add(action);
                });
            JsonObject result = new JsonObject();
            result.add("actions", actions);
            writeJson(exchange, 200, result);
        }
    }

    private static String providedActionToken(HttpExchange exchange) {
        String provided = bearerToken(exchange);
        if (provided.isBlank()) {
            provided = header(exchange, "X-Bridge-Token");
        }
        return provided;
    }

    private boolean requireMetricsToken(HttpExchange exchange) throws IOException {
        BridgeConfig config = plugin.bridgeConfig();
        if (!config.hasUsableMetricsToken()) {
            writeError(exchange, 503, "metrics_token_not_configured", "Bridge metrics token is not configured.");
            return false;
        }
        String provided = bearerToken(exchange);
        if (provided.isBlank()) {
            provided = header(exchange, "X-Bridge-Metrics-Token");
        }
        if (!constantTimeEquals(config.metricsToken(), provided)) {
            writeError(exchange, 401, "unauthorized", "Bridge metrics token is invalid.");
            return false;
        }
        return true;
    }

    private static String bearerToken(HttpExchange exchange) {
        String authorization = header(exchange, "Authorization");
        if (authorization.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            return authorization.substring("bearer ".length()).trim();
        }
        return "";
    }

    private static String header(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "" : value.trim();
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] left = expected == null ? new byte[0] : expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }

    private static String readBody(HttpExchange exchange, int maxBytes) throws IOException {
        try (InputStream input = exchange.getRequestBody(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new PayloadTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private void writeMethodNotAllowed(HttpExchange exchange, String allowedMethod) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowedMethod);
        writeError(exchange, 405, "method_not_allowed", "HTTP method is not allowed.");
    }

    private void writeError(HttpExchange exchange, int status, String code, String message) throws IOException {
        writeError(exchange, status, code, message, "");
    }

    private void writeError(HttpExchange exchange, int status, String code, String message, String requestId) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("ok", false);
        json.addProperty("status", "failed");
        json.addProperty("code", code);
        json.addProperty("message", message);
        json.add("data", new JsonObject());
        if (requestId != null && !requestId.isBlank()) {
            json.addProperty("requestId", requestId);
        }
        writeJson(exchange, status, json);
    }

    private void writeJson(HttpExchange exchange, int status, JsonObject json) throws IOException {
        writeText(exchange, status, "application/json; charset=utf-8", gson.toJson(json));
    }

    private void writeText(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        if (value == null || value.isJsonNull()) {
            return "";
        }
        try {
            return value.getAsString().trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean hasExactPath(HttpExchange exchange, String expectedPath) {
        return expectedPath.equals(exchange.getRequestURI().getPath());
    }

    private static boolean isJsonContentType(HttpExchange exchange) {
        String contentType = header(exchange, "Content-Type").toLowerCase(Locale.ROOT);
        return contentType.equals("application/json") || contentType.startsWith("application/json;");
    }

    private static final class PayloadTooLargeException extends IOException {
    }

    private static final class BridgeThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "bridge-http-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
