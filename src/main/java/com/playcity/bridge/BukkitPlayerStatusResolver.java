package com.playcity.bridge;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

final class BukkitPlayerStatusResolver implements PlayerStatusResolver {
    @Override
    public PlayerStatusSnapshot findOnlinePlayer(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return null;
        }
        return new PlayerStatusSnapshot(
            player.getUniqueId(),
            player.getName(),
            player.getWorld().getName()
        );
    }
}
