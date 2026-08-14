package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BridgeHttpAccessControllerTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    void authenticatesAndEnforcesPerClientActionScopes() {
        BridgeHttpAccessController controller = controller(10, 2);
        BridgeHttpAccessController.Authentication authentication = controller.authenticate(TOKEN);

        assertTrue(authentication.authenticated());
        BridgeHttpAccessController.Authorization allowed = controller.authorize(
            authentication.session(),
            "minecraft.player_status",
            1_000L
        );
        assertTrue(allowed.allowed());
        allowed.permit().close();

        BridgeHttpAccessController.Authorization forbidden = controller.authorize(
            authentication.session(),
            "minecraft.teleport_player",
            1_001L
        );
        assertEquals(403, forbidden.status());
        assertEquals("action_forbidden", forbidden.code());
    }

    @Test
    void rejectsUnknownTokensAndBoundsRateAndConcurrency() {
        BridgeHttpAccessController controller = controller(1, 1);
        assertEquals(false, controller.authenticate("wrong").authenticated());
        BridgeHttpAccessController.Authentication authentication = controller.authenticate(TOKEN);

        BridgeHttpAccessController.Authorization first = controller.authorize(
            authentication.session(),
            "minecraft.player_status",
            1_000L
        );
        assertTrue(first.allowed());
        BridgeHttpAccessController.Authorization limited = controller.authorize(
            authentication.session(),
            "minecraft.player_status",
            1_001L
        );
        assertEquals(429, limited.status());
        assertEquals("rate_limited", limited.code());
        first.permit().close();
    }

    @Test
    void eventScopesAndConnectionCountsAreIndependentFromActionConcurrency() {
        BridgeHttpAccessController controller = new BridgeHttpAccessController(List.of(new BridgeHttpCredential(
            "web-1",
            TOKEN,
            Set.of("minecraft.player_status"),
            Set.of("minecraft.player_*"),
            10,
            1,
            1
        )));
        BridgeHttpAccessController.Session session = controller.authenticate(TOKEN).session();

        BridgeHttpAccessController.EventAuthorization first = controller.authorizeEventConnection(session, 1_000L);
        assertTrue(first.allowed());
        assertTrue(controller.authorizeEventSubscription(session, Set.of("minecraft.player_joined")));
        assertEquals(false, controller.authorizeEventSubscription(session, Set.of("minecraft.*")));

        BridgeHttpAccessController.EventAuthorization second = controller.authorizeEventConnection(session, 1_001L);
        assertEquals(429, second.status());
        assertEquals("too_many_event_connections", second.code());
        first.permit().close();
    }

    private static BridgeHttpAccessController controller(int requestsPerMinute, int maxConcurrent) {
        return new BridgeHttpAccessController(List.of(new BridgeHttpCredential(
            "web-1",
            TOKEN,
            Set.of("minecraft.player_status"),
            requestsPerMinute,
            maxConcurrent
        )));
    }
}
