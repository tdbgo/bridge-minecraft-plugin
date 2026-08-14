package com.playcity.bridge.api;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable event envelope created after a provider publishes an event.
 *
 * @param instanceId Bridge process instance identifier
 * @param sequence process-local monotonic sequence
 * @param name registered event name
 * @param version provider-defined event contract version
 * @param publishedAtEpochMillis publication time in Unix epoch milliseconds
 * @param data deeply immutable JSON-compatible event data
 */
public record BridgeEvent(
    String instanceId,
    long sequence,
    String name,
    String version,
    long publishedAtEpochMillis,
    Map<String, Object> data
) {
    /** Validates envelope fields and deeply snapshots event data. */
    public BridgeEvent {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        name = Objects.requireNonNull(name, "name");
        version = Objects.requireNonNull(version, "version");
        if (sequence < 1L || publishedAtEpochMillis < 1L) {
            throw new IllegalArgumentException("Invalid Bridge event sequence or timestamp.");
        }
        data = JsonData.copyObject(data);
    }
}
