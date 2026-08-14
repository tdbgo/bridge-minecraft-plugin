package com.playcity.bridge.api;

import java.util.Objects;

/**
 * Immutable description of one event topic published through Bridge.
 *
 * @param name globally unique normalized event name
 * @param version provider-defined event contract version
 * @param description concise consumer-facing description
 * @param sensitivity data-sensitivity classification
 */
public record BridgeEventDescriptor(
    String name,
    String version,
    String description,
    BridgeEventSensitivity sensitivity
) {
    /** Validates and normalizes descriptor fields. */
    public BridgeEventDescriptor {
        name = Objects.requireNonNull(name, "name").trim().toLowerCase(java.util.Locale.ROOT);
        version = Objects.requireNonNull(version, "version").trim();
        description = Objects.requireNonNull(description, "description").trim();
        sensitivity = Objects.requireNonNull(sensitivity, "sensitivity");
        if (!name.matches("[a-z0-9][a-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("Invalid Bridge event name.");
        }
        if (!version.matches("[A-Za-z0-9_.-]{1,32}")) {
            throw new IllegalArgumentException("Invalid Bridge event version.");
        }
        if (description.isBlank() || description.length() > 240) {
            throw new IllegalArgumentException("Invalid Bridge event description.");
        }
    }
}
