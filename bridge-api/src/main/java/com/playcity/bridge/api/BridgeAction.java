package com.playcity.bridge.api;

import java.util.concurrent.CompletableFuture;

/** Executes one typed Bridge capability. */
@FunctionalInterface
public interface BridgeAction {
    /**
     * Executes off the server thread unless the caller is already on it. Bukkit state must be accessed through
     * the scheduling methods exposed by {@code context}.
     *
     * @param request immutable action request
     * @param context provider runtime context
     * @return future completed with a stable action result
     */
    CompletableFuture<BridgeActionResult> execute(BridgeActionRequest request, BridgeActionContext context);
}
