package com.playcity.bridge;

import com.playcity.bridge.api.BridgeActionResult;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class IdempotencyStore {
    enum ClaimState {
        OWNER,
        REPLAY,
        CONFLICT,
        CAPACITY_EXCEEDED
    }

    static final class Claim {
        private final ClaimState state;
        private final Entry entry;

        private Claim(ClaimState state, Entry entry) {
            this.state = state;
            this.entry = entry;
        }

        ClaimState state() {
            return state;
        }

        CompletableFuture<BridgeActionResult> future() {
            return entry == null ? null : entry.future;
        }
    }

    private final Map<String, Entry> entries = new HashMap<>();

    synchronized Claim claim(
        String requestId,
        String fingerprint,
        long nowMillis,
        long ttlMillis,
        int maxEntries
    ) {
        Entry existing = entries.get(requestId);
        if (existing != null && existing.future.isDone() && existing.expiresAtMillis <= nowMillis) {
            entries.remove(requestId);
            existing = null;
        }
        if (existing != null) {
            ClaimState state = existing.fingerprint.equals(fingerprint)
                ? ClaimState.REPLAY
                : ClaimState.CONFLICT;
            return new Claim(state, existing);
        }

        if (entries.size() >= maxEntries) {
            entries.entrySet().removeIf(entry -> entry.getValue().future.isDone()
                && entry.getValue().expiresAtMillis <= nowMillis);
        }
        if (entries.size() >= maxEntries) {
            return new Claim(ClaimState.CAPACITY_EXCEEDED, null);
        }

        Entry created = new Entry(fingerprint, nowMillis + ttlMillis);
        entries.put(requestId, created);
        return new Claim(ClaimState.OWNER, created);
    }

    void complete(Claim claim, BridgeActionResult result, long nowMillis, long ttlMillis) {
        Entry entry;
        synchronized (this) {
            if (claim == null || claim.state != ClaimState.OWNER || claim.entry == null) {
                throw new IllegalArgumentException("Only an owner claim can be completed.");
            }
            entry = claim.entry;
            entry.expiresAtMillis = nowMillis + ttlMillis;
        }
        entry.future.complete(result);
    }

    private static final class Entry {
        private final String fingerprint;
        private final CompletableFuture<BridgeActionResult> future = new CompletableFuture<>();
        private long expiresAtMillis;

        private Entry(String fingerprint, long expiresAtMillis) {
            this.fingerprint = fingerprint;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
