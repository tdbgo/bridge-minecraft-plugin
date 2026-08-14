package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeTokenResolverTest {
    private static final String INLINE = "0123456789abcdef0123456789abcdef";
    private static final String FILE_TOKEN = "abcdef0123456789abcdef0123456789";

    @TempDir
    Path tempDirectory;

    @Test
    void loadsRelativeTokenFileWithoutExposingItsPath() throws IOException {
        Path secretDirectory = Files.createDirectories(tempDirectory.resolve("secrets"));
        Files.writeString(secretDirectory.resolve("metrics.token"), FILE_TOKEN + System.lineSeparator());

        BridgeTokenResolver.Resolution result = BridgeTokenResolver.resolve(
            "CHANGE_ME",
            "secrets/metrics.token",
            tempDirectory
        );

        assertTrue(result.valid());
        assertEquals(FILE_TOKEN, result.token());
        assertEquals(BridgeTokenResolver.Source.FILE, result.source());
        assertTrue(result.issue().isBlank());
    }

    @Test
    void keepsInlineCredentialForExistingConfigurationsWithoutTokenFile() {
        BridgeTokenResolver.Resolution result = BridgeTokenResolver.resolve(INLINE, "", tempDirectory);

        assertTrue(result.valid());
        assertEquals(INLINE, result.token());
        assertEquals(BridgeTokenResolver.Source.INLINE, result.source());
    }

    @Test
    void rejectsAmbiguousInlineAndFileConfiguration() throws IOException {
        Files.writeString(tempDirectory.resolve("metrics.token"), FILE_TOKEN);

        BridgeTokenResolver.Resolution result = BridgeTokenResolver.resolve(
            INLINE,
            "metrics.token",
            tempDirectory
        );

        assertFalse(result.valid());
        assertTrue(result.token().isBlank());
        assertEquals("metrics_token_source_conflict", result.issue());
    }

    @Test
    void returnsStableIssueForMissingFile() {
        BridgeTokenResolver.Resolution result = BridgeTokenResolver.resolve(
            "CHANGE_ME",
            "secrets/missing.token",
            tempDirectory
        );

        assertFalse(result.valid());
        assertEquals("metrics_token_file_missing", result.issue());
        assertFalse(result.issue().contains(tempDirectory.toString()));
    }

    @Test
    void rejectsWhitespaceAndOversizedTokenFiles() throws IOException {
        Path whitespaceFile = tempDirectory.resolve("whitespace.token");
        Files.writeString(whitespaceFile, "0123456789abcdef 0123456789abcdef");
        Path oversizedFile = tempDirectory.resolve("oversized.token");
        Files.writeString(oversizedFile, "a".repeat(4097));

        assertEquals(
            "metrics_token_file_invalid",
            BridgeTokenResolver.resolve("", whitespaceFile.toString(), tempDirectory).issue()
        );
        assertEquals(
            "metrics_token_file_size",
            BridgeTokenResolver.resolve("", oversizedFile.toString(), tempDirectory).issue()
        );
    }

    @Test
    void rejectsExtraLinesInsteadOfSilentlyTrimmingThem() throws IOException {
        Path multilineFile = tempDirectory.resolve("multiline.token");
        Files.writeString(multilineFile, FILE_TOKEN + System.lineSeparator() + System.lineSeparator());

        assertEquals(
            "metrics_token_file_invalid",
            BridgeTokenResolver.resolve("", multilineFile.toString(), tempDirectory).issue()
        );
    }
}
