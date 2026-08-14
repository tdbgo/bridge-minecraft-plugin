package com.playcity.bridge.api;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.Plugin;

/** Runtime services available to an action provider. */
public interface BridgeActionContext {
    /**
     * Returns the plugin that registered this action, not the Bridge plugin.
     *
     * @return action-provider plugin
     */
    Plugin plugin();

    /**
     * Runs bounded Bukkit/Paper work on the primary Paper server thread.
     *
     * @param <T> result type
     * @param callable work that must access Paper state
     * @return future completed with the callable result
     */
    <T> CompletableFuture<T> callSync(Callable<T> callable);
}
