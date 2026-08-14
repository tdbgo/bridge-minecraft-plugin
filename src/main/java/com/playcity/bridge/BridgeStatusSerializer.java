package com.playcity.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;

public final class BridgeStatusSerializer {
    private BridgeStatusSerializer() {
    }

    public static JsonObject toJson(BridgeMetricsSnapshot snapshot, boolean stale, String bridgeVersion, long nowMillis) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("service", "Bridge");
        root.addProperty("apiVersion", "v1");
        root.addProperty("schemaVersion", 1);
        root.addProperty("bridgeVersion", bridgeVersion);
        root.addProperty("observedAt", Instant.ofEpochMilli(nowMillis).toString());
        root.addProperty("sampledAt", snapshot.sampledAt().toString());
        root.addProperty("stale", stale);
        root.addProperty("state", stale ? "degraded" : "ready");
        JsonArray degradedReasons = new JsonArray();
        if (stale) {
            degradedReasons.add("metrics_stale");
        }
        root.add("degradedReasons", degradedReasons);

        JsonObject plugin = new JsonObject();
        plugin.addProperty("name", "Bridge");
        plugin.addProperty("version", bridgeVersion);
        root.add("plugin", plugin);

        JsonObject server = new JsonObject();
        server.addProperty("version", snapshot.serverVersion());
        server.addProperty("apiVersion", snapshot.apiVersion());
        server.addProperty("startTime", Instant.ofEpochMilli(snapshot.serverStartTimeMillis()).toString());
        server.addProperty("uptimeSeconds", Math.max(0L, nowMillis - snapshot.serverStartTimeMillis()) / 1000L);
        root.add("server", server);

        JsonObject tps = new JsonObject();
        tps.addProperty("1m", snapshot.tps1m());
        tps.addProperty("5m", snapshot.tps5m());
        tps.addProperty("15m", snapshot.tps15m());
        JsonObject tick = new JsonObject();
        tick.addProperty("average", snapshot.tickDuration().averageSeconds());
        tick.addProperty("p50", snapshot.tickDuration().p50Seconds());
        tick.addProperty("p95", snapshot.tickDuration().p95Seconds());
        tick.addProperty("p99", snapshot.tickDuration().p99Seconds());
        tick.addProperty("max", snapshot.tickDuration().maxSeconds());
        JsonObject performance = new JsonObject();
        performance.add("tps", tps);
        performance.add("tickDurationSeconds", tick);
        root.add("performance", performance);

        JsonObject players = new JsonObject();
        players.addProperty("online", snapshot.playersOnline());
        players.addProperty("max", snapshot.maxPlayers());
        root.add("players", players);

        JsonArray worlds = new JsonArray();
        for (BridgeMetricsSnapshot.WorldMetrics world : snapshot.worlds()) {
            JsonObject value = new JsonObject();
            value.addProperty("name", world.name());
            value.addProperty("loadedChunks", world.loadedChunks());
            value.addProperty("entities", world.entities());
            value.addProperty("players", world.players());
            worlds.add(value);
        }
        root.add("worlds", worlds);
        return root;
    }
}
