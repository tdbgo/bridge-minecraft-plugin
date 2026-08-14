package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.playcity.bridge.api.BridgeActionResult;
import org.junit.jupiter.api.Test;

class IdempotencyStoreTest {
    @Test
    void concurrentReplaySharesTheOwnerFuture() {
        IdempotencyStore store = new IdempotencyStore();
        IdempotencyStore.Claim owner = store.claim("request-1", "same", 1_000L, 300L, 10);
        IdempotencyStore.Claim replay = store.claim("request-1", "same", 1_001L, 300L, 10);

        assertEquals(IdempotencyStore.ClaimState.OWNER, owner.state());
        assertEquals(IdempotencyStore.ClaimState.REPLAY, replay.state());
        assertSame(owner.future(), replay.future());

        BridgeActionResult result = BridgeActionResult.success();
        store.complete(owner, result, 1_010L, 300L);
        assertSame(result, replay.future().join());
    }

    @Test
    void rejectsRequestIdReuseWithDifferentPayload() {
        IdempotencyStore store = new IdempotencyStore();
        store.claim("request-1", "first", 1_000L, 300L, 10);

        IdempotencyStore.Claim conflict = store.claim("request-1", "second", 1_001L, 300L, 10);

        assertEquals(IdempotencyStore.ClaimState.CONFLICT, conflict.state());
    }

    @Test
    void allowsRequestIdReuseAfterExpiry() {
        IdempotencyStore store = new IdempotencyStore();
        IdempotencyStore.Claim first = store.claim("request-1", "first", 1_000L, 100L, 10);
        store.complete(first, BridgeActionResult.success(), 1_000L, 100L);

        IdempotencyStore.Claim afterExpiry = store.claim("request-1", "second", 1_101L, 100L, 10);

        assertEquals(IdempotencyStore.ClaimState.OWNER, afterExpiry.state());
    }

    @Test
    void enforcesCapacityUntilAnEntryExpires() {
        IdempotencyStore store = new IdempotencyStore();
        IdempotencyStore.Claim first = store.claim("request-1", "first", 1_000L, 100L, 1);
        store.complete(first, BridgeActionResult.success(), 1_000L, 100L);

        IdempotencyStore.Claim full = store.claim("request-2", "second", 1_001L, 100L, 1);
        IdempotencyStore.Claim reclaimed = store.claim("request-2", "second", 1_101L, 100L, 1);

        assertEquals(IdempotencyStore.ClaimState.CAPACITY_EXCEEDED, full.state());
        assertEquals(IdempotencyStore.ClaimState.OWNER, reclaimed.state());
        assertFalse(reclaimed.future().isDone());
    }

    @Test
    void neverExpiresAnInFlightExecutionIntoADuplicateOwner() {
        IdempotencyStore store = new IdempotencyStore();
        store.claim("request-1", "same", 1_000L, 100L, 10);

        IdempotencyStore.Claim retry = store.claim("request-1", "same", 2_000L, 100L, 10);

        assertEquals(IdempotencyStore.ClaimState.REPLAY, retry.state());
        assertFalse(retry.future().isDone());
    }
}
