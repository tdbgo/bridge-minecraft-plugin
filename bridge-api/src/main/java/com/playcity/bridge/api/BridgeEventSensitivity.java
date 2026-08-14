package com.playcity.bridge.api;

/** Data-sensitivity classification advertised with a Bridge event contract. */
public enum BridgeEventSensitivity {
    /** Operational or non-personal data. */
    GENERAL,
    /** Data directly associated with a Minecraft player. */
    PLAYER,
    /** Data that requires an explicitly reviewed consumer policy. */
    SENSITIVE
}
