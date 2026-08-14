package com.playcity.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.playcity.bridge.api.BridgeActionResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BridgeRelayExecutionJournal {
    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;

    enum ClaimState {
        OWNER,
        REPLAY,
        CONFLICT,
        OUTCOME_UNKNOWN,
        CAPACITY_EXCEEDED
    }

    record Claim(ClaimState state, BridgeActionResult result) {
    }

    private final Path path;
    private final long ttlMillis;
    private final int maxEntries;
    private final Map<String, Entry> entries;

    private BridgeRelayExecutionJournal(Path path, long ttlMillis, int maxEntries, Map<String, Entry> entries) {
        this.path = path;
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
        this.entries = entries;
    }

    static BridgeRelayExecutionJournal open(Path path, long ttlMillis, int maxEntries) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        if (BridgeRelayIdentityStore.containsSymbolicLink(target)) {
            throw new IOException("relay_journal_symlink");
        }
        Map<String, Entry> entries = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            ? load(target)
            : new HashMap<>();
        BridgeRelayExecutionJournal journal = new BridgeRelayExecutionJournal(target, ttlMillis, maxEntries, entries);
        journal.removeExpired(System.currentTimeMillis());
        return journal;
    }

    synchronized Claim claim(String requestId, String fingerprint, long nowMillis) throws IOException {
        removeExpired(nowMillis);
        Entry existing = entries.get(requestId);
        if (existing != null) {
            if (!existing.fingerprint.equals(fingerprint)) {
                return new Claim(ClaimState.CONFLICT, null);
            }
            return existing.result == null
                ? new Claim(ClaimState.OUTCOME_UNKNOWN, null)
                : new Claim(ClaimState.REPLAY, existing.result);
        }
        if (entries.size() >= maxEntries) {
            return new Claim(ClaimState.CAPACITY_EXCEEDED, null);
        }
        entries.put(requestId, new Entry(fingerprint, nowMillis, null));
        try {
            persist();
        } catch (IOException exception) {
            entries.remove(requestId);
            throw exception;
        }
        return new Claim(ClaimState.OWNER, null);
    }

    synchronized void complete(String requestId, BridgeActionResult result, long nowMillis) throws IOException {
        Entry entry = entries.get(requestId);
        if (entry == null || entry.result != null) {
            throw new IOException("relay_journal_claim_missing");
        }
        Entry completed = new Entry(entry.fingerprint, nowMillis, result);
        entries.put(requestId, completed);
        try {
            persist();
        } catch (IOException exception) {
            entries.put(requestId, entry);
            throw exception;
        }
    }

    private void removeExpired(long nowMillis) {
        entries.entrySet().removeIf(entry -> entry.getValue().updatedAtMillis + ttlMillis <= nowMillis);
    }

    private void persist() throws IOException {
        Path parent = path.getParent();
        Files.createDirectories(parent);
        if (BridgeRelayIdentityStore.containsSymbolicLink(parent)) {
            throw new IOException("relay_journal_symlink");
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray serializedEntries = new JsonArray();
        List<String> requestIds = new ArrayList<>(entries.keySet());
        requestIds.sort(String::compareTo);
        for (String requestId : requestIds) {
            Entry entry = entries.get(requestId);
            JsonObject value = new JsonObject();
            value.addProperty("requestId", requestId);
            value.addProperty("fingerprint", entry.fingerprint);
            value.addProperty("updatedAtMillis", entry.updatedAtMillis);
            value.addProperty("state", entry.result == null ? "in_progress" : "complete");
            if (entry.result != null) {
                value.addProperty("httpStatus", entry.result.status());
                value.add("result", BridgeJson.resultToJson(entry.result, requestId));
            }
            serializedEntries.add(value);
        }
        root.add("entries", serializedEntries);

        Path temporary = Files.createTempFile(parent, ".relay-executions-", ".tmp");
        try {
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            BridgeRelayIdentityStore.restrictPermissions(temporary);
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            BridgeRelayIdentityStore.restrictPermissions(path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Map<String, Entry> load(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("relay_journal_not_regular");
        }
        long size = Files.size(path);
        if (size <= 0L || size > MAX_FILE_BYTES) {
            throw new IOException("relay_journal_size");
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
                throw new IOException("relay_journal_invalid");
            }
            Map<String, Entry> entries = new HashMap<>();
            for (JsonElement element : root.getAsJsonArray("entries")) {
                JsonObject value = element.getAsJsonObject();
                String requestId = value.get("requestId").getAsString();
                String fingerprint = value.get("fingerprint").getAsString();
                long updatedAtMillis = value.get("updatedAtMillis").getAsLong();
                String state = value.get("state").getAsString();
                if (!requestId.matches("[A-Za-z0-9_.:-]{1,160}") || !fingerprint.matches("[0-9a-f]{64}")) {
                    throw new IOException("relay_journal_invalid");
                }
                BridgeActionResult result = null;
                if ("complete".equals(state)) {
                    result = parseResult(value.get("httpStatus").getAsInt(), value.getAsJsonObject("result"));
                } else if (!"in_progress".equals(state)) {
                    throw new IOException("relay_journal_invalid");
                }
                if (entries.putIfAbsent(requestId, new Entry(fingerprint, updatedAtMillis, result)) != null) {
                    throw new IOException("relay_journal_invalid");
                }
            }
            BridgeRelayIdentityStore.restrictPermissions(path);
            return entries;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("relay_journal_invalid", exception);
        }
    }

    private static BridgeActionResult parseResult(int httpStatus, JsonObject json) throws IOException {
        boolean ok = json.get("ok").getAsBoolean();
        String code = json.get("code").getAsString();
        String message = json.get("message").getAsString();
        JsonObject data = json.has("data") && json.get("data").isJsonObject()
            ? json.getAsJsonObject("data").deepCopy()
            : new JsonObject();
        if (ok && httpStatus == 200 && "ok".equals(code)) {
            return BridgeActionResult.success(BridgeJson.toMap(data));
        }
        if (!ok && httpStatus >= 400 && httpStatus <= 599 && !code.isBlank()) {
            return BridgeActionResult.failure(httpStatus, code, message);
        }
        throw new IOException("relay_journal_invalid");
    }

    private record Entry(String fingerprint, long updatedAtMillis, BridgeActionResult result) {
    }
}
