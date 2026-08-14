package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeRelayIdentityStoreTest {
    @TempDir
    Path tempDirectory;

    @Test
    void createsAndReusesOneProtectedIdentity() throws IOException {
        Path identityFile = tempDirectory.resolve("secrets/relay-identity.json");

        BridgeRelayIdentityStore.Identity created = BridgeRelayIdentityStore.loadOrCreate(identityFile);
        BridgeRelayIdentityStore.Identity loaded = BridgeRelayIdentityStore.loadOrCreate(identityFile);

        assertEquals(created, loaded);
        assertTrue(Files.isRegularFile(identityFile));
        assertTrue(BridgeSecurityPolicy.isUsableToken(created.credential()));
        assertNotEquals(created.agentId().toString(), created.credential());
    }

    @Test
    void rejectsMalformedIdentityInsteadOfRotatingIt() throws IOException {
        Path identityFile = tempDirectory.resolve("relay-identity.json");
        Files.writeString(identityFile, "{\"schemaVersion\":1,\"agentId\":\"bad\",\"credential\":\"bad\"}");

        IOException exception = assertThrows(
            IOException.class,
            () -> BridgeRelayIdentityStore.loadOrCreate(identityFile)
        );

        assertEquals("relay_identity_invalid", exception.getMessage());
        assertTrue(Files.readString(identityFile).contains("\"agentId\":\"bad\""));
    }
}
