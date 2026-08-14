package com.playcity.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

final class BridgeTokenResolver {
    private static final long MAX_TOKEN_FILE_BYTES = 4096L;

    private BridgeTokenResolver() {
    }

    static Resolution resolve(String inlineToken, String tokenFile, Path dataDirectory) {
        String normalizedInline = trim(inlineToken);
        String normalizedFile = trim(tokenFile);
        boolean inlineUsable = BridgeSecurityPolicy.isUsableToken(normalizedInline);

        if (normalizedFile.isBlank()) {
            return inlineUsable
                ? Resolution.valid(normalizedInline, Source.INLINE)
                : Resolution.invalid(Source.NONE, "metrics_token_not_configured");
        }
        if (inlineUsable) {
            return Resolution.invalid(Source.INVALID, "metrics_token_source_conflict");
        }

        Path configuredPath;
        try {
            configuredPath = Path.of(normalizedFile);
        } catch (RuntimeException exception) {
            return Resolution.invalid(Source.INVALID, "metrics_token_file_invalid_path");
        }
        Path tokenPath = configuredPath.isAbsolute()
            ? configuredPath.normalize()
            : dataDirectory.resolve(configuredPath).normalize();

        try {
            if (containsSymbolicLink(tokenPath)) {
                return Resolution.invalid(Source.INVALID, "metrics_token_file_symlink");
            }
            if (!Files.isRegularFile(tokenPath, LinkOption.NOFOLLOW_LINKS)) {
                return Resolution.invalid(Source.INVALID, "metrics_token_file_missing");
            }
            long size = Files.size(tokenPath);
            if (size <= 0L || size > MAX_TOKEN_FILE_BYTES) {
                return Resolution.invalid(Source.INVALID, "metrics_token_file_size");
            }
            if (hasUnsafePosixPermissions(tokenPath)) {
                return Resolution.invalid(Source.INVALID, "metrics_token_file_permissions");
            }
            String token = removeSingleTrailingLineEnding(Files.readString(tokenPath, StandardCharsets.UTF_8));
            if (!BridgeSecurityPolicy.isUsableToken(token)) {
                return Resolution.invalid(Source.INVALID, "metrics_token_file_invalid");
            }
            return Resolution.valid(token, Source.FILE);
        } catch (IOException | SecurityException exception) {
            return Resolution.invalid(Source.INVALID, "metrics_token_file_unreadable");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String removeSingleTrailingLineEnding(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("\n")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean containsSymbolicLink(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path segment : absolute) {
            current = current == null ? segment : current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUnsafePosixPermissions(Path path) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(
            path,
            PosixFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (view == null) {
            return false;
        }
        Set<PosixFilePermission> permissions = view.readAttributes().permissions();
        return permissions.contains(PosixFilePermission.GROUP_WRITE)
            || permissions.contains(PosixFilePermission.OTHERS_READ)
            || permissions.contains(PosixFilePermission.OTHERS_WRITE)
            || permissions.contains(PosixFilePermission.OWNER_EXECUTE)
            || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
            || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
    }

    enum Source {
        NONE("none"),
        INLINE("inline"),
        FILE("file"),
        INVALID("invalid");

        private final String id;

        Source(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    record Resolution(String token, Source source, String issue) {
        static Resolution valid(String token, Source source) {
            return new Resolution(token, source, "");
        }

        static Resolution invalid(Source source, String issue) {
            return new Resolution("", source, issue);
        }

        boolean valid() {
            return issue.isBlank();
        }
    }
}
