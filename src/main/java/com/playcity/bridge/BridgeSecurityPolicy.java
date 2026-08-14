package com.playcity.bridge;

import java.nio.charset.StandardCharsets;

final class BridgeSecurityPolicy {
    static final int MIN_TOKEN_BYTES = 32;
    static final int MAX_TOKEN_BYTES = 4096;

    private BridgeSecurityPolicy() {
    }

    static boolean isUsableToken(String token) {
        if (token == null || token.isBlank() || "CHANGE_ME".equals(token)) {
            return false;
        }
        if (token.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        int bytes = token.getBytes(StandardCharsets.UTF_8).length;
        return bytes >= MIN_TOKEN_BYTES && bytes <= MAX_TOKEN_BYTES;
    }

    static boolean isDistinctUsableToken(String token, String otherToken) {
        return isUsableToken(token) && !token.equals(otherToken);
    }

    static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        return switch (host.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "127.0.0.1", "localhost", "::1", "[::1]", "0:0:0:0:0:0:0:1" -> true;
            default -> false;
        };
    }
}
