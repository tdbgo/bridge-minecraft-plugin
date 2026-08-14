package com.playcity.bridge.api;

import java.util.Map;
import java.util.Objects;

/** Immutable, JSON-compatible action request. */
public final class BridgeActionRequest {
    private final String requestId;
    private final String actionName;
    private final Map<String, Object> payload;
    private final BridgeCaller caller;

    /**
     * Creates a request from a local provider with unrestricted internal action scope.
     *
     * @param requestId caller-generated idempotency identifier
     * @param actionName registered action name
     * @param payload JSON-compatible payload
     */
    public BridgeActionRequest(String requestId, String actionName, Map<String, ?> payload) {
        this(requestId, actionName, payload, BridgeCaller.internal("local-api"));
    }

    /**
     * Creates a request with an explicit authenticated caller.
     *
     * @param requestId caller-generated idempotency identifier
     * @param actionName registered action name
     * @param payload JSON-compatible payload
     * @param caller authenticated request origin
     */
    public BridgeActionRequest(
        String requestId,
        String actionName,
        Map<String, ?> payload,
        BridgeCaller caller
    ) {
        this.requestId = requestId;
        this.actionName = actionName;
        this.payload = JsonData.copyObject(payload);
        this.caller = Objects.requireNonNull(caller, "caller");
    }

    /**
     * Returns the idempotency identifier.
     *
     * @return caller-generated idempotency identifier
     */
    public String requestId() {
        return requestId;
    }

    /**
     * Returns the action name.
     *
     * @return requested action name
     */
    public String actionName() {
        return actionName;
    }

    /**
     * Returns the request data.
     *
     * @return deeply immutable JSON-compatible payload
     */
    public Map<String, Object> payload() {
        return payload;
    }

    /**
     * Returns the request origin.
     *
     * @return authenticated request origin
     */
    public BridgeCaller caller() {
        return caller;
    }
}
