package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.playcity.bridge.api.BridgeActionResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeRelayExecutionJournalTest {
    private static final String FINGERPRINT_A = "a".repeat(64);
    private static final String FINGERPRINT_B = "b".repeat(64);

    @TempDir
    Path tempDirectory;

    @Test
    void preservesCompletedResultAcrossRestart() throws IOException {
        Path path = tempDirectory.resolve("relay-executions.json");
        long now = System.currentTimeMillis();
        BridgeRelayExecutionJournal journal = BridgeRelayExecutionJournal.open(path, 300_000L, 128);
        assertEquals(
            BridgeRelayExecutionJournal.ClaimState.OWNER,
            journal.claim("maps.move.1", FINGERPRINT_A, now).state()
        );
        journal.complete("maps.move.1", BridgeActionResult.success(Map.of("accepted", true)), now + 1_000L);

        BridgeRelayExecutionJournal reopened = BridgeRelayExecutionJournal.open(path, 300_000L, 128);
        BridgeRelayExecutionJournal.Claim replay = reopened.claim("maps.move.1", FINGERPRINT_A, now + 2_000L);

        assertEquals(BridgeRelayExecutionJournal.ClaimState.REPLAY, replay.state());
        assertEquals(true, replay.result().data().get("accepted"));
        assertEquals(
            BridgeRelayExecutionJournal.ClaimState.CONFLICT,
            reopened.claim("maps.move.1", FINGERPRINT_B, now + 2_000L).state()
        );
    }

    @Test
    void doesNotRepeatInterruptedExecutionAfterRestart() throws IOException {
        Path path = tempDirectory.resolve("relay-executions.json");
        long now = System.currentTimeMillis();
        BridgeRelayExecutionJournal journal = BridgeRelayExecutionJournal.open(path, 300_000L, 128);
        journal.claim("maps.move.2", FINGERPRINT_A, now);

        BridgeRelayExecutionJournal reopened = BridgeRelayExecutionJournal.open(path, 300_000L, 128);
        BridgeRelayExecutionJournal.Claim claim = reopened.claim("maps.move.2", FINGERPRINT_A, now + 1_000L);

        assertEquals(BridgeRelayExecutionJournal.ClaimState.OUTCOME_UNKNOWN, claim.state());
        assertNull(claim.result());
    }
}
