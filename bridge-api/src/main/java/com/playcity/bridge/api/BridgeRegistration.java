package com.playcity.bridge.api;

import org.bukkit.plugin.Plugin;

/** Owned registration handle. Closing it removes only the corresponding action registration. */
public interface BridgeRegistration extends AutoCloseable {
    /**
     * Returns this action's contract.
     *
     * @return registered action descriptor
     */
    BridgeActionDescriptor descriptor();

    /**
     * Returns this action's provider.
     *
     * @return plugin that owns this action
     */
    Plugin owner();

    /**
     * Reports whether this action remains registered.
     *
     * @return whether this handle still owns the registered action
     */
    boolean active();

    /** Unregisters this action if it is still owned by this handle. */
    @Override
    void close();
}
