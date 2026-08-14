package com.playcity.bridge.api;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.Plugin;

/** Public action registry and local execution API exposed through Bukkit ServicesManager. */
public interface BridgeApi {
    /**
     * Registers a typed action owned by one plugin.
     *
     * @param owner action-provider plugin
     * @param descriptor stable action metadata
     * @param action action implementation
     * @return registration handle
     */
    BridgeRegistration registerAction(Plugin owner, BridgeActionDescriptor descriptor, BridgeAction action);

    /**
     * Registers a typed event topic owned by one plugin.
     *
     * @param owner event-provider plugin
     * @param descriptor stable event metadata
     * @return publisher and registration handle
     */
    BridgeEventPublisher registerEvent(Plugin owner, BridgeEventDescriptor descriptor);

    /**
     * Lists registered action names.
     *
     * @return immutable snapshot of registered action names
     */
    Set<String> actionNames();

    /**
     * Describes registered actions.
     *
     * @return immutable snapshot of registered action descriptors keyed by name
     */
    Map<String, BridgeActionDescriptor> actionDescriptors();

    /**
     * Describes registered event topics.
     *
     * @return immutable snapshot of registered event descriptors keyed by name
     */
    Map<String, BridgeEventDescriptor> eventDescriptors();

    /**
     * Executes a registered action through the same caller and idempotency boundary as transports.
     *
     * @param request immutable action request
     * @return future completed with a stable result
     */
    CompletableFuture<BridgeActionResult> execute(BridgeActionRequest request);
}
