package com.playcity.bridge.api;

import java.util.Map;
import java.util.Objects;

/** Immutable action result with a stable machine-readable code. */
public final class BridgeActionResult {
    private final boolean ok;
    private final int status;
    private final String code;
    private final String message;
    private final Map<String, Object> data;

    private BridgeActionResult(boolean ok, int status, String code, String message, Map<String, ?> data) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("HTTP status must be between 100 and 599.");
        }
        this.ok = ok;
        this.status = status;
        this.code = Objects.requireNonNull(code, "code").trim();
        this.message = Objects.requireNonNull(message, "message");
        this.data = JsonData.copyObject(data);
        if (!this.code.matches("[a-z0-9][a-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("Invalid Bridge result code.");
        }
    }

    /**
     * Creates an empty success result.
     *
     * @return successful result without additional data
     */
    public static BridgeActionResult success() {
        return success(Map.of());
    }

    /**
     * Creates a successful result.
     *
     * @param data JSON-compatible result data
     * @return successful result
     */
    public static BridgeActionResult success(Map<String, ?> data) {
        return new BridgeActionResult(true, 200, "ok", "OK", data);
    }

    /**
     * Creates a stable failure result.
     *
     * @param status HTTP-compatible status from 400 through 599
     * @param code machine-readable failure code
     * @param message caller-safe failure message
     * @return failure result
     */
    public static BridgeActionResult failure(int status, String code, String message) {
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("Failure status must be between 400 and 599.");
        }
        return new BridgeActionResult(false, status, code, message, Map.of());
    }

    /**
     * Reports success or failure.
     *
     * @return whether execution succeeded
     */
    public boolean ok() {
        return ok;
    }

    /**
     * Returns the transport-compatible status.
     *
     * @return HTTP-compatible status
     */
    public int status() {
        return status;
    }

    /**
     * Returns the result code.
     *
     * @return stable machine-readable result code
     */
    public String code() {
        return code;
    }

    /**
     * Returns the human-readable result summary.
     *
     * @return caller-safe result message
     */
    public String message() {
        return message;
    }

    /**
     * Returns additional result data.
     *
     * @return deeply immutable JSON-compatible result data
     */
    public Map<String, Object> data() {
        return data;
    }
}
