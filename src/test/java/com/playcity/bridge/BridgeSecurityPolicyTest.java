package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BridgeSecurityPolicyTest {
    @Test
    void rejectsMissingDefaultAndShortTokens() {
        assertFalse(BridgeSecurityPolicy.isUsableToken(null));
        assertFalse(BridgeSecurityPolicy.isUsableToken(""));
        assertFalse(BridgeSecurityPolicy.isUsableToken("CHANGE_ME"));
        assertFalse(BridgeSecurityPolicy.isUsableToken("short-token"));
        assertFalse(BridgeSecurityPolicy.isUsableToken("0123456789abcdef 0123456789abcdef"));
        assertFalse(BridgeSecurityPolicy.isUsableToken("x".repeat(BridgeSecurityPolicy.MAX_TOKEN_BYTES + 1)));
    }

    @Test
    void acceptsTokenWithAtLeastThirtyTwoUtf8Bytes() {
        assertTrue(BridgeSecurityPolicy.isUsableToken("0123456789abcdef0123456789abcdef"));
    }

    @Test
    void requiresMetricsTokenToDifferFromActionToken() {
        String actionToken = "0123456789abcdef0123456789abcdef";
        assertFalse(BridgeSecurityPolicy.isDistinctUsableToken(actionToken, actionToken));
        assertTrue(BridgeSecurityPolicy.isDistinctUsableToken(
            "abcdef0123456789abcdef0123456789",
            actionToken
        ));
    }

    @Test
    void recognizesOnlyExplicitLoopbackHostsByDefault() {
        assertTrue(BridgeSecurityPolicy.isLoopbackHost("127.0.0.1"));
        assertTrue(BridgeSecurityPolicy.isLoopbackHost("localhost"));
        assertTrue(BridgeSecurityPolicy.isLoopbackHost("::1"));
        assertFalse(BridgeSecurityPolicy.isLoopbackHost("0.0.0.0"));
        assertFalse(BridgeSecurityPolicy.isLoopbackHost("203.0.113.10"));
    }
}
