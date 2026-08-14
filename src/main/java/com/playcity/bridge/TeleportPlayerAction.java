package com.playcity.bridge;

import com.playcity.bridge.api.BridgeAction;
import com.playcity.bridge.api.BridgeActionContext;
import com.playcity.bridge.api.BridgeActionRequest;
import com.playcity.bridge.api.BridgeActionResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class TeleportPlayerAction implements BridgeAction {
    @Override
    public CompletableFuture<BridgeActionResult> execute(BridgeActionRequest request, BridgeActionContext context) {
        if (!(context instanceof DefaultBridgeActionContext bridgeContext)) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(500, "invalid_context", "Bridge action context is unavailable.")
            );
        }
        BridgeConfig config = bridgeContext.config();
        if (!config.teleportEnabled()) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(503, "teleport_disabled", "Teleport action is disabled.")
            );
        }

        Map<String, Object> payload = request.payload();
        Map<String, Object> targetPlayer = BridgePayload.object(payload, "targetPlayer");
        Map<String, Object> position = BridgePayload.object(payload, "position");
        String uuidText = BridgePayload.string(targetPlayer, "uuid");
        String username = BridgePayload.string(targetPlayer, "username");
        String worldName = BridgePayload.string(position, "world");
        Double x = BridgePayload.number(position, "x");
        Double y = BridgePayload.number(position, "y");
        Double z = BridgePayload.number(position, "z");
        Float yaw = BridgePayload.floatNumber(position, "yaw");
        Float pitch = BridgePayload.floatNumber(position, "pitch");

        if (uuidText.isBlank() && username.isBlank()) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(400, "target_player_required", "targetPlayer.uuid or targetPlayer.username is required.")
            );
        }
        if (x == null || y == null || z == null) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(400, "invalid_target", "position.x, position.y, and position.z are required.")
            );
        }
        if ((BridgePayload.hasValue(position, "yaw") && yaw == null)
            || (BridgePayload.hasValue(position, "pitch") && pitch == null)) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(400, "invalid_target", "position.yaw and position.pitch must be finite numbers when provided.")
            );
        }
        if (pitch != null && (pitch < -90.0F || pitch > 90.0F)) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(400, "invalid_target", "position.pitch must be between -90 and 90 degrees.")
            );
        }
        if (Math.abs(x) > config.teleportMaxAbsXz() || Math.abs(z) > config.teleportMaxAbsXz()) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(400, "target_out_of_bounds", "Teleport target is outside the allowed X/Z bounds.")
            );
        }

        UUID uuid = parseUuid(uuidText);
        if (!uuidText.isBlank() && uuid == null) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(400, "invalid_player_uuid", "targetPlayer.uuid is invalid.")
            );
        }

        return bridgeContext.callSync(() -> teleport(config, uuid, username, worldName, x, y, z, yaw, pitch));
    }

    private BridgeActionResult teleport(
        BridgeConfig config,
        UUID uuid,
        String username,
        String worldName,
        double x,
        double y,
        double z,
        Float yaw,
        Float pitch
    ) {
        Player player = uuid == null ? Bukkit.getPlayerExact(username) : Bukkit.getPlayer(uuid);
        if (player == null) {
            return BridgeActionResult.failure(409, "minecraft_player_offline", "Linked Minecraft player is not online.");
        }

        World world = worldName.isBlank() ? player.getWorld() : Bukkit.getWorld(worldName);
        if (world == null) {
            return BridgeActionResult.failure(400, "world_not_found", "Target world was not found.");
        }
        if (!config.isTeleportWorldAllowed(world.getName())) {
            return BridgeActionResult.failure(403, "world_not_allowed", "Target world is not allowed.");
        }
        if (y < world.getMinHeight() || y > world.getMaxHeight()) {
            return BridgeActionResult.failure(400, "target_out_of_bounds", "Teleport target is outside the world height bounds.");
        }

        float effectiveYaw = yaw == null ? player.getLocation().getYaw() : yaw;
        float effectivePitch = pitch == null ? player.getLocation().getPitch() : pitch;
        Location location = new Location(world, x, y, z, effectiveYaw, effectivePitch);
        boolean teleported = player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (!teleported) {
            return BridgeActionResult.failure(500, "teleport_failed", "Paper rejected the teleport request.");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("player", Map.of(
            "uuid", player.getUniqueId().toString(),
            "username", player.getName()
        ));
        data.put("position", Map.of(
            "world", world.getName(),
            "x", location.getX(),
            "y", location.getY(),
            "z", location.getZ(),
            "yaw", location.getYaw(),
            "pitch", location.getPitch()
        ));
        data.put("executedAt", Instant.now().toString());
        return BridgeActionResult.success(data);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
