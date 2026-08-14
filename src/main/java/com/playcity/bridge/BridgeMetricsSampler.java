package com.playcity.bridge;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

public final class BridgeMetricsSampler {
    private final BridgePlugin plugin;
    private final AtomicReference<BridgeMetricsSnapshot> latest = new AtomicReference<>();
    private BukkitTask task;
    private int consecutiveFailures;

    public BridgeMetricsSampler(BridgePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        latest.set(null);
        if (!plugin.bridgeConfig().metricsEnabled()) {
            return;
        }
        sampleSafely();
        long interval = plugin.bridgeConfig().metricsSampleIntervalTicks();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sampleSafely, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public BridgeMetricsSnapshot latest() {
        return latest.get();
    }

    public boolean isReady() {
        return latest.get() != null;
    }

    public boolean isStale(BridgeMetricsSnapshot snapshot, long nowMillis) {
        return snapshot == null
            || nowMillis - snapshot.sampledAt().toEpochMilli() > plugin.bridgeConfig().metricsStaleAfterMillis();
    }

    private void sampleSafely() {
        try {
            latest.set(sample(plugin.getServer(), plugin.bridgeConfig()));
            consecutiveFailures = 0;
        } catch (Throwable throwable) {
            consecutiveFailures++;
            if (consecutiveFailures == 1 || consecutiveFailures % 12 == 0) {
                plugin.getLogger().log(Level.WARNING, "Bridge metrics sampling failed.", throwable);
            }
        }
    }

    static BridgeMetricsSnapshot sample(Server server, BridgeConfig config) {
        double[] tps = server.getTPS();
        long[] tickTimes = server.getTickTimes();
        List<BridgeMetricsSnapshot.WorldMetrics> worlds = new ArrayList<>();
        for (World world : server.getWorlds()) {
            if (config.isMetricsWorldIncluded(world.getName())) {
                worlds.add(new BridgeMetricsSnapshot.WorldMetrics(
                    world.getName(),
                    world.getChunkCount(),
                    world.getEntityCount(),
                    world.getPlayerCount()
                ));
            }
        }
        worlds.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));

        return new BridgeMetricsSnapshot(
            Instant.now(),
            ManagementFactory.getRuntimeMXBean().getStartTime(),
            server.getVersion(),
            server.getBukkitVersion(),
            tpsAt(tps, 0),
            tpsAt(tps, 1),
            tpsAt(tps, 2),
            BridgeMetricsMath.tickDurationStats(server.getAverageTickTime(), tickTimes),
            server.getOnlinePlayers().size(),
            server.getMaxPlayers(),
            worlds
        );
    }

    private static double tpsAt(double[] values, int index) {
        return values != null && index < values.length ? BridgeMetricsMath.finiteNonNegative(values[index]) : 0.0;
    }
}
