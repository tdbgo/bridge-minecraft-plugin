package com.playcity.bridge.api;

import java.util.Map;
import org.bukkit.plugin.Plugin;

/** Owned event publisher. Closing it removes only the corresponding event topic. */
public interface BridgeEventPublisher extends AutoCloseable {
    /**
     * Returns this topic's contract.
     *
     * @return registered event descriptor
     */
    BridgeEventDescriptor descriptor();

    /**
     * Returns this topic's provider.
     *
     * @return plugin that owns this topic
     */
    Plugin owner();

    /**
     * Reports whether this handle may still publish.
     *
     * @return whether the topic remains registered
     */
    boolean active();

    /**
     * Publishes immutable JSON-compatible data without performing Bukkit work.
     * This method is safe to call from either a server or worker thread.
     *
     * @param data JSON-compatible event data
     * @return immutable published event envelope
     */
    BridgeEvent publish(Map<String, ?> data);

    /** Unregisters this event topic if it is still owned by this handle. */
    @Override
    void close();
}
