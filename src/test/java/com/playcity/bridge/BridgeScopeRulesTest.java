package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class BridgeScopeRulesTest {
    @Test
    void intersectsScopesWithoutAllowingEitherSideToExpandTheOther() {
        assertEquals(
            Set.of("minecraft.player_status"),
            BridgeScopeRules.intersect(Set.of("minecraft.*"), Set.of("minecraft.player_status"))
        );
        assertEquals(
            Set.of("minecraft.player_*"),
            BridgeScopeRules.intersect(Set.of("minecraft.*"), Set.of("minecraft.player_*"))
        );
        assertEquals(Set.of(), BridgeScopeRules.intersect(Set.of("notice.*"), Set.of("minecraft.*")));
    }

    @Test
    void rejectsSubscriptionsBroaderThanLocalEventScope() {
        Set<String> allowed = Set.of("minecraft.player_joined", "notice.*");

        assertTrue(BridgeScopeRules.requestedRulesAllowed(Set.of("minecraft.player_joined"), allowed));
        assertTrue(BridgeScopeRules.requestedRulesAllowed(Set.of("notice.sent"), allowed));
        assertFalse(BridgeScopeRules.requestedRulesAllowed(Set.of("minecraft.*"), allowed));
        assertFalse(BridgeScopeRules.requestedRulesAllowed(Set.of("*"), allowed));
    }
}
