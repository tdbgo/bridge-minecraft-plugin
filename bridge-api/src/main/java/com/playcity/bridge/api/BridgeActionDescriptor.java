package com.playcity.bridge.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable metadata used for discovery, authorization and documentation.
 *
 * @param name globally unique normalized action name
 * @param version provider-defined contract version
 * @param description concise operator-facing description
 * @param risk operator-facing risk classification
 */
public record BridgeActionDescriptor(
    String name,
    String version,
    String description,
    BridgeActionRisk risk
) {
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_.:-]{0,127}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.+-]{0,31}");

    /** Validates and normalizes descriptor fields. */
    public BridgeActionDescriptor {
        name = Objects.requireNonNull(name, "name").trim().toLowerCase(java.util.Locale.ROOT);
        version = Objects.requireNonNull(version, "version").trim();
        description = Objects.requireNonNull(description, "description").trim();
        risk = Objects.requireNonNull(risk, "risk");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid Bridge action name: " + name);
        }
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Invalid Bridge action version: " + version);
        }
        if (description.isBlank() || description.length() > 240) {
            throw new IllegalArgumentException("Bridge action description must contain 1-240 characters.");
        }
    }
}
