package com.playcity.bridge;

import java.util.UUID;

interface PlayerStatusResolver {
    PlayerStatusSnapshot findOnlinePlayer(UUID uuid);
}
