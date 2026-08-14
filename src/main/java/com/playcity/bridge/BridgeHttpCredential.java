package com.playcity.bridge;

import com.playcity.bridge.api.BridgeCaller;
import java.util.Set;

record BridgeHttpCredential(
    String id,
    String token,
    Set<String> allowedActions,
    Set<String> allowedEvents,
    int requestsPerMinute,
    int maxConcurrent,
    int maxEventConnections
) {
    BridgeHttpCredential {
        allowedActions = Set.copyOf(allowedActions);
        allowedEvents = Set.copyOf(allowedEvents);
    }

    BridgeHttpCredential(
        String id,
        String token,
        Set<String> allowedActions,
        int requestsPerMinute,
        int maxConcurrent
    ) {
        this(id, token, allowedActions, Set.of(), requestsPerMinute, maxConcurrent, 0);
    }

    boolean allows(String actionName) {
        return caller().allows(actionName);
    }

    BridgeCaller caller() {
        return new BridgeCaller(id, BridgeCaller.Type.HTTP, allowedActions);
    }

    boolean allowsEvent(String eventName) {
        return BridgeScopeRules.matches(allowedEvents, eventName);
    }
}
