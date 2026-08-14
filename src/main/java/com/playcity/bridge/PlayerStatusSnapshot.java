package com.playcity.bridge;

import java.util.UUID;

record PlayerStatusSnapshot(UUID uuid, String username, String world) {}
