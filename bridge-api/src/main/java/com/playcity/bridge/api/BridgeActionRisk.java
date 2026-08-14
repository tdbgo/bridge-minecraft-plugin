package com.playcity.bridge.api;

/** Operator-facing risk classification for a remotely callable action. */
public enum BridgeActionRisk {
    /** Reads state without intentionally changing server state. */
    READ_ONLY,
    /** Changes server or provider state. */
    MUTATING,
    /** May irreversibly remove or overwrite state. */
    DESTRUCTIVE
}
