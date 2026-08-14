package com.playcity.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

final class BridgeRelayIdentityStore {
    static final int SCHEMA_VERSION = 1;
    private static final long MAX_IDENTITY_BYTES = 4096L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BridgeRelayIdentityStore() {
    }

    static Identity loadOrCreate(Path path) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        if (containsSymbolicLink(target)) {
            throw new IOException("relay_identity_symlink");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return load(target);
        }

        Files.createDirectories(target.getParent());
        if (containsSymbolicLink(target.getParent())) {
            throw new IOException("relay_identity_symlink");
        }
        Identity identity = create();
        Path temporary = Files.createTempFile(target.getParent(), ".relay-identity-", ".tmp");
        try {
            Files.writeString(temporary, identity.toJson().toString(), StandardCharsets.UTF_8);
            restrictPermissions(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                return load(target);
            }
            restrictPermissions(target);
            return identity;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Identity load(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("relay_identity_not_regular");
        }
        long size = Files.size(path);
        if (size <= 0L || size > MAX_IDENTITY_BYTES) {
            throw new IOException("relay_identity_size");
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            int schemaVersion = json.get("schemaVersion").getAsInt();
            UUID agentId = UUID.fromString(json.get("agentId").getAsString());
            String credential = json.get("credential").getAsString();
            if (schemaVersion != SCHEMA_VERSION || !BridgeSecurityPolicy.isUsableToken(credential)) {
                throw new IOException("relay_identity_invalid");
            }
            restrictPermissions(path);
            return new Identity(agentId, credential);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("relay_identity_invalid", exception);
        }
    }

    private static Identity create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        try {
            return new Identity(UUID.randomUUID(), Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    static void restrictPermissions(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            AclEntry ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
            acl.setAcl(List.of(ownerOnly));
        }
    }

    static boolean containsSymbolicLink(Path path) {
        Path current = path.getRoot();
        for (Path segment : path) {
            current = current == null ? segment : current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    record Identity(UUID agentId, String credential) {
        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("schemaVersion", SCHEMA_VERSION);
            json.addProperty("agentId", agentId.toString());
            json.addProperty("credential", credential);
            return json;
        }
    }
}
