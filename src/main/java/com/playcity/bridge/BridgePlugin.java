package com.playcity.bridge;

import com.playcity.bridge.api.BridgeAction;
import com.playcity.bridge.api.BridgeActionDescriptor;
import com.playcity.bridge.api.BridgeActionRisk;
import com.playcity.bridge.api.BridgeApi;
import com.playcity.bridge.api.BridgeEventDescriptor;
import com.playcity.bridge.api.BridgeEventPublisher;
import com.playcity.bridge.api.BridgeEventSensitivity;
import java.io.IOException;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class BridgePlugin extends JavaPlugin implements Listener {
    private BridgeConfig bridgeConfig;
    private DefaultBridgeApi bridgeApi;
    private BridgeMetricsRegistry metricsRegistry;
    private BridgeMetricsSampler metricsSampler;
    private BridgeHttpServer httpServer;
    private BridgeHttpAccessController accessController;
    private BridgeWebSocketServer webSocketServer;
    private BridgeEventPublisher playerJoinedEvents;
    private BridgeEventPublisher playerLeftEvents;
    private BridgeRelayConfig relayConfig;
    private BridgeRelayClient relayClient;
    private String relayIssue = "";
    private boolean httpRunning;
    private volatile boolean eventsRunning;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bridgeConfig = loadBridgeConfig();
        relayConfig = loadRelayConfig();
        metricsRegistry = new BridgeMetricsRegistry();
        metricsSampler = new BridgeMetricsSampler(this);
        bridgeApi = new DefaultBridgeApi(this);
        playerJoinedEvents = registerBuiltInEvent(
            "minecraft.player_joined",
            "Published after one player joins this Paper server.",
            BridgeEventSensitivity.PLAYER
        );
        playerLeftEvents = registerBuiltInEvent(
            "minecraft.player_left",
            "Published after one player leaves this Paper server.",
            BridgeEventSensitivity.PLAYER
        );
        registerBuiltIn(
            "minecraft.player_status",
            "Returns authoritative online status for one player UUID.",
            BridgeActionRisk.READ_ONLY,
            new PlayerStatusAction(new BukkitPlayerStatusResolver())
        );
        registerBuiltIn(
            "minecraft.teleport_player",
            "Teleports one online player to a validated Paper location.",
            BridgeActionRisk.MUTATING,
            new TeleportPlayerAction()
        );
        NoticePluginAdapter noticeAdapter = new NoticePluginAdapter(
            getLogger(),
            new BukkitNoticeTargetResolver()
        );
        registerBuiltIn(
            "minecraft.notice_sync",
            "Requests synchronization through the optional Notice plugin API.",
            BridgeActionRisk.MUTATING,
            new NoticeSyncAction(noticeAdapter)
        );
        registerBuiltIn(
            "minecraft.notice_send",
            "Broadcasts a validated message through the optional Notice plugin API.",
            BridgeActionRisk.MUTATING,
            new NoticeSendAction(noticeAdapter)
        );
        getServer().getServicesManager().register(BridgeApi.class, bridgeApi, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);

        BridgeCommand bridgeCommand = new BridgeCommand(this);
        PluginCommand command = getCommand("bridge");
        if (command != null) {
            command.setExecutor(bridgeCommand);
            command.setTabCompleter(bridgeCommand);
        }

        metricsSampler.start();
        rebuildAccessController();
        startHttpServer();
        startWebSocketServer();
        startRelayClient(false);
        warnAboutTokens();
        getServer().getConsoleSender().sendMessage(
            Component.text("Bridge enabled. ", NamedTextColor.GREEN)
                .append(Component.text(
                    "HTTP " + httpStatusText() + ", events " + eventsStatusText() + ", relay " + relayStatusText(),
                    NamedTextColor.GRAY
                ))
        );
    }

    @Override
    public void onDisable() {
        stopWebSocketServer();
        stopRelayClient();
        stopHttpServer();
        if (metricsSampler != null) {
            metricsSampler.stop();
        }
        if (bridgeApi != null) {
            bridgeApi.unregisterAll(this);
        }
        getServer().getServicesManager().unregisterAll(this);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (playerJoinedEvents != null && playerJoinedEvents.active()) {
            playerJoinedEvents.publish(Map.of("playerUuid", event.getPlayer().getUniqueId().toString()));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (playerLeftEvents != null && playerLeftEvents.active()) {
            playerLeftEvents.publish(Map.of("playerUuid", event.getPlayer().getUniqueId().toString()));
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (bridgeApi != null && event.getPlugin() != this) {
            bridgeApi.unregisterAll(event.getPlugin());
        }
    }

    public void reloadBridge() {
        stopWebSocketServer();
        stopRelayClient();
        stopHttpServer();
        metricsSampler.stop();
        reloadConfig();
        bridgeConfig = loadBridgeConfig();
        relayConfig = loadRelayConfig();
        metricsSampler.start();
        rebuildAccessController();
        startHttpServer();
        startWebSocketServer();
        startRelayClient(true);
        warnAboutTokens();
    }

    public BridgeConfig bridgeConfig() {
        return bridgeConfig;
    }

    public DefaultBridgeApi bridgeApi() {
        return bridgeApi;
    }

    public BridgeMetricsRegistry metricsRegistry() {
        return metricsRegistry;
    }

    public BridgeMetricsSampler metricsSampler() {
        return metricsSampler;
    }

    public BridgeRelayConfig relayConfig() {
        return relayConfig;
    }

    public BridgeRelayClient.Status relayStatus() {
        if (relayClient != null) {
            return relayClient.status();
        }
        if (!relayConfig.enabled()) {
            return new BridgeRelayClient.Status(BridgeRelayClient.State.DISABLED, "", "", null);
        }
        String issue = relayIssue.isBlank() ? relayConfig.issue() : relayIssue;
        return new BridgeRelayClient.Status(BridgeRelayClient.State.MISCONFIGURED, issue, "", null);
    }

    public String relayStatusText() {
        BridgeRelayClient.Status status = relayStatus();
        String state = status.state().name().toLowerCase(java.util.Locale.ROOT);
        return status.code().isBlank() ? state : state + " (" + status.code() + ")";
    }

    public int relayQueuedEventCount() {
        return relayClient == null ? 0 : relayClient.queuedEventCount();
    }

    public boolean relayEventQueueOverflowed() {
        return relayClient != null && relayClient.eventQueueOverflowed();
    }

    public String httpStatusText() {
        if (!bridgeConfig.httpEnabled()) {
            return "disabled";
        }
        return httpRunning
            ? "listening on " + bridgeConfig.httpHost() + ":" + bridgeConfig.httpPort()
            : "not running";
    }

    public String eventsStatusText() {
        if (!bridgeConfig.eventsEnabled()) {
            return "disabled";
        }
        return eventsRunning
            ? "listening on " + bridgeConfig.eventsHost() + ":" + bridgeConfig.eventsPort()
            : "not running";
    }

    public String eventsState() {
        if (!bridgeConfig.eventsEnabled()) {
            return "disabled";
        }
        return eventsRunning ? "running" : "not_running";
    }

    void onWebSocketStarted(BridgeWebSocketServer server) {
        if (webSocketServer == server) {
            eventsRunning = true;
        }
    }

    void onWebSocketStartFailed(BridgeWebSocketServer server) {
        if (webSocketServer == server) {
            eventsRunning = false;
        }
    }

    private void rebuildAccessController() {
        accessController = new BridgeHttpAccessController(
            bridgeConfig.httpCredentials(),
            bridgeConfig.jwtConfig(),
            getLogger()
        );
    }

    private void startHttpServer() {
        if (!bridgeConfig.httpEnabled()) {
            httpRunning = false;
            return;
        }
        if (!bridgeConfig.isHttpBindAllowed()) {
            httpRunning = false;
            getLogger().severe(
                "Refusing non-loopback Bridge HTTP bind. Use a private tunnel, or explicitly set http.allowNonLoopback=true."
            );
            return;
        }
        httpServer = new BridgeHttpServer(this, bridgeApi, accessController);
        try {
            httpServer.start();
            httpRunning = true;
            getLogger().info("Bridge HTTP listening on " + bridgeConfig.httpHost() + ":" + bridgeConfig.httpPort());
        } catch (IOException exception) {
            httpRunning = false;
            httpServer = null;
            getLogger().severe("Failed to start Bridge HTTP server: " + exception.getMessage());
        }
    }

    private void startWebSocketServer() {
        eventsRunning = false;
        if (!bridgeConfig.eventsEnabled()) {
            return;
        }
        if (!bridgeConfig.isEventsBindAllowed()) {
            getLogger().severe(
                "Refusing non-loopback Bridge WebSocket bind. Use a private tunnel, or explicitly set events.allowNonLoopback=true."
            );
            return;
        }
        if (bridgeConfig.httpEnabled()
            && bridgeConfig.httpPort() == bridgeConfig.eventsPort()
            && bridgeConfig.httpHost().equalsIgnoreCase(bridgeConfig.eventsHost())) {
            getLogger().severe("Bridge WebSocket events must use a different listener port from HTTP.");
            return;
        }
        if (!bridgeConfig.hasUsableToken()) {
            getLogger().severe("Bridge WebSocket events require a valid static client or JWT configuration.");
            return;
        }
        webSocketServer = new BridgeWebSocketServer(this, bridgeApi, accessController);
        webSocketServer.start();
    }

    private void stopWebSocketServer() {
        if (webSocketServer != null) {
            webSocketServer.shutdown();
            webSocketServer = null;
        }
        eventsRunning = false;
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        httpRunning = false;
    }

    private void startRelayClient(boolean requireEventResync) {
        relayIssue = "";
        if (!relayConfig.enabled() || !relayConfig.valid()) {
            return;
        }
        try {
            relayClient = BridgeRelayClient.create(this, requireEventResync);
            relayClient.start();
        } catch (IOException exception) {
            relayClient = null;
            relayIssue = exception.getMessage() == null ? "relay_identity_failed" : exception.getMessage();
            getLogger().severe("Failed to start Bridge outbound relay: " + relayIssue);
        }
    }

    private void stopRelayClient() {
        if (relayClient != null) {
            relayClient.stop();
            relayClient = null;
        }
    }

    private void warnAboutTokens() {
        if (!bridgeConfig.hasUsableToken()) {
            getLogger().warning("Bridge HTTP actions require at least one valid static client or JWT configuration.");
        }
        if (bridgeConfig.jwtConfig().enabled() && !bridgeConfig.jwtConfig().valid()) {
            getLogger().warning("Bridge JWT configuration was ignored: " + bridgeConfig.jwtConfig().issue());
        } else if (accessController != null && !accessController.jwtIssue().isBlank()) {
            getLogger().warning("Bridge JWT verification is unavailable: " + accessController.jwtIssue());
        }
        for (String issue : bridgeConfig.httpCredentialIssues()) {
            getLogger().warning("Bridge HTTP client configuration was ignored: " + issue);
        }
        if (bridgeConfig.metricsEnabled() && !bridgeConfig.hasUsableMetricsToken()) {
            getLogger().warning(
                "Bridge status and metrics credential is unavailable: " + bridgeConfig.metricsTokenIssue()
            );
        }
    }

    private BridgeConfig loadBridgeConfig() {
        return BridgeConfig.from(getConfig(), getDataFolder().toPath());
    }

    private BridgeRelayConfig loadRelayConfig() {
        return BridgeRelayConfig.from(getConfig(), getDataFolder().toPath());
    }

    private void registerBuiltIn(String name, String description, BridgeActionRisk risk, BridgeAction action) {
        bridgeApi.registerAction(this, new BridgeActionDescriptor(name, "1", description, risk), action);
    }

    private BridgeEventPublisher registerBuiltInEvent(
        String name,
        String description,
        BridgeEventSensitivity sensitivity
    ) {
        return bridgeApi.registerEvent(this, new BridgeEventDescriptor(name, "1", description, sensitivity));
    }
}
