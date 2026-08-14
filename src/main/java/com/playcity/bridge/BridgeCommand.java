package com.playcity.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class BridgeCommand implements CommandExecutor, TabCompleter {
    private final BridgePlugin plugin;

    public BridgeCommand(BridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String label,
        @NotNull String[] args
    ) {
        String subcommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "help" -> showHelp(sender);
            case "status" -> showStatus(sender);
            case "reload" -> reload(sender);
            default -> showHelp(sender);
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String label,
        @NotNull String[] args
    ) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        List<String> options = sender.hasPermission("bridge.admin")
            ? List.of("help", "status", "reload")
            : List.of("help", "status");
        for (String option : options) {
            if (option.startsWith(prefix)) {
                matches.add(option);
            }
        }
        return matches;
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(Component.text("Bridge commands", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/bridge status", NamedTextColor.GRAY));
        if (sender.hasPermission("bridge.admin")) {
            sender.sendMessage(Component.text("/bridge reload", NamedTextColor.GRAY));
        }
    }

    private void showStatus(CommandSender sender) {
        BridgeConfig config = plugin.bridgeConfig();
        sender.sendMessage(Component.text("Bridge status", NamedTextColor.AQUA));
        BridgeRelayClient.Status relay = plugin.relayStatus();
        sender.sendMessage(Component.text("Outbound relay: ", NamedTextColor.GRAY)
            .append(Component.text(plugin.relayStatusText(), relay.state() == BridgeRelayClient.State.CONNECTED
                ? NamedTextColor.GREEN
                : NamedTextColor.WHITE)));
        if (relay.state() == BridgeRelayClient.State.PAIRING && !relay.pairingCode().isBlank()) {
            sender.sendMessage(Component.text("Pairing code: ", NamedTextColor.GRAY)
                .append(Component.text(relay.pairingCode(), NamedTextColor.YELLOW)));
        }
        if (plugin.relayQueuedEventCount() > 0 || plugin.relayEventQueueOverflowed()) {
            sender.sendMessage(Component.text("Relay events: ", NamedTextColor.GRAY)
                .append(Component.text(
                    plugin.relayQueuedEventCount() + " queued"
                        + (plugin.relayEventQueueOverflowed() ? ", resync required" : ""),
                    plugin.relayEventQueueOverflowed() ? NamedTextColor.YELLOW : NamedTextColor.WHITE
                )));
        }
        sender.sendMessage(Component.text("HTTP: ", NamedTextColor.GRAY)
            .append(Component.text(config.httpEnabled() ? plugin.httpStatusText() : "disabled", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("HTTP clients configured: ", NamedTextColor.GRAY)
            .append(Component.text(Integer.toString(config.httpCredentials().size()), NamedTextColor.WHITE)));
        if (!config.httpCredentialIssues().isEmpty()) {
            sender.sendMessage(Component.text("HTTP client entries rejected: ", NamedTextColor.GRAY)
                .append(Component.text(
                    config.httpCredentialIssues().size() + " (see server log)",
                    NamedTextColor.YELLOW
                )));
        }
        sender.sendMessage(Component.text("HTTP access configured: ", NamedTextColor.GRAY)
            .append(Component.text(config.hasUsableToken() ? "yes" : "no", config.hasUsableToken() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        sender.sendMessage(Component.text("JWT: ", NamedTextColor.GRAY)
            .append(Component.text(
                config.jwtConfig().enabled() ? (config.jwtConfig().valid() ? "ready" : "misconfigured") : "disabled",
                config.jwtConfig().valid() ? NamedTextColor.GREEN : NamedTextColor.WHITE
            )));
        sender.sendMessage(Component.text("WebSocket events: ", NamedTextColor.GRAY)
            .append(Component.text(plugin.eventsStatusText(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Metrics: ", NamedTextColor.GRAY)
            .append(Component.text(config.metricsEnabled() ? "enabled" : "disabled", NamedTextColor.WHITE)));
        if (config.metricsEnabled()) {
            sender.sendMessage(Component.text("Metrics token configured: ", NamedTextColor.GRAY)
                .append(Component.text(config.hasUsableMetricsToken() ? "yes" : "no", config.hasUsableMetricsToken() ? NamedTextColor.GREEN : NamedTextColor.RED)));
            sender.sendMessage(Component.text("Metrics credential source: ", NamedTextColor.GRAY)
                .append(Component.text(config.metricsTokenSource(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("Metrics sample ready: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.metricsSampler().isReady() ? "yes" : "no", plugin.metricsSampler().isReady() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        }
        sender.sendMessage(Component.text("Actions: ", NamedTextColor.GRAY)
            .append(Component.text(String.join(", ", plugin.bridgeApi().actionNames()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Events: ", NamedTextColor.GRAY)
            .append(Component.text(String.join(", ", plugin.bridgeApi().eventDescriptors().keySet()), NamedTextColor.WHITE)));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("bridge.admin")) {
            sender.sendMessage(Component.text("You do not have permission to reload Bridge.", NamedTextColor.RED));
            return;
        }
        plugin.reloadBridge();
        sender.sendMessage(Component.text(
            "Bridge configuration reloaded. Run /bridge status to verify each transport.",
            NamedTextColor.GREEN
        ));
    }
}
