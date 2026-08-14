package com.playcity.bridge;

import com.playcity.bridge.api.BridgeActionContext;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class DefaultBridgeActionContext implements BridgeActionContext {
    private final BridgePlugin bridge;
    private final Plugin owner;

    public DefaultBridgeActionContext(BridgePlugin bridge, Plugin owner) {
        this.bridge = bridge;
        this.owner = owner;
    }

    @Override
    public Plugin plugin() {
        return owner;
    }

    public BridgeConfig config() {
        return bridge.bridgeConfig();
    }

    @Override
    public <T> CompletableFuture<T> callSync(Callable<T> callable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!owner.isEnabled()) {
            future.completeExceptionally(new IllegalStateException("Action provider plugin is disabled."));
            return future;
        }
        if (Bukkit.isPrimaryThread()) {
            complete(callable, future);
            return future;
        }
        Bukkit.getScheduler().runTask(owner, () -> {
            if (!owner.isEnabled()) {
                future.completeExceptionally(new IllegalStateException("Action provider plugin is disabled."));
                return;
            }
            complete(callable, future);
        });
        return future;
    }

    private static <T> void complete(Callable<T> callable, CompletableFuture<T> future) {
        try {
            future.complete(callable.call());
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
    }
}
