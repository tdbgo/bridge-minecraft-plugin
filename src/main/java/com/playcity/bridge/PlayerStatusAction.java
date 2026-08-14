package com.playcity.bridge;

import com.playcity.bridge.api.BridgeAction;
import com.playcity.bridge.api.BridgeActionContext;
import com.playcity.bridge.api.BridgeActionRequest;
import com.playcity.bridge.api.BridgeActionResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

final class PlayerStatusAction implements BridgeAction {
    private static final Set<String> REQUEST_FIELDS = Set.of("requestId", "action", "source", "targetPlayer");
    private static final Set<String> TARGET_FIELDS = Set.of("uuid", "username");
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );
    private static final Pattern SOURCE_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");

    private final PlayerStatusResolver resolver;

    PlayerStatusAction(PlayerStatusResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public CompletableFuture<BridgeActionResult> execute(
        BridgeActionRequest request,
        BridgeActionContext context
    ) {
        Map<String, Object> payload = request.payload();
        if (hasUnsupportedField(payload, REQUEST_FIELDS) || !validOptionalSource(payload.get("source"))) {
            return completedFailure(
                "invalid_player_status_payload",
                "Player status payload contains an unsupported or invalid field."
            );
        }

        Object targetElement = payload.get("targetPlayer");
        if (!(targetElement instanceof Map<?, ?>)) {
            return completedFailure("target_player_required", "targetPlayer.uuid is required.");
        }
        Map<String, Object> targetPlayer = BridgePayload.object(payload, "targetPlayer");
        if (hasUnsupportedField(targetPlayer, TARGET_FIELDS)) {
            return completedFailure(
                "invalid_player_status_payload",
                "targetPlayer contains an unsupported field."
            );
        }

        String uuidText = requiredString(targetPlayer.get("uuid"));
        if (uuidText.isBlank()) {
            return completedFailure("target_player_required", "targetPlayer.uuid is required.");
        }
        if (!validOptionalUsername(targetPlayer.get("username"))) {
            return completedFailure(
                "invalid_player_status_payload",
                "targetPlayer.username must be a non-blank string of at most 64 characters."
            );
        }

        UUID uuid = parseCanonicalUuid(uuidText);
        if (uuid == null) {
            return completedFailure("invalid_player_uuid", "targetPlayer.uuid is invalid.");
        }

        return context.callSync(() -> resultFor(resolver.findOnlinePlayer(uuid)));
    }

    private static BridgeActionResult resultFor(PlayerStatusSnapshot snapshot) {
        Map<String, Object> data = new LinkedHashMap<>();
        boolean online = snapshot != null;
        data.put("online", online);
        if (online) {
            data.put("player", Map.of(
                "uuid", snapshot.uuid().toString(),
                "username", snapshot.username(),
                "world", snapshot.world()
            ));
        }
        return BridgeActionResult.success(data);
    }

    private static CompletableFuture<BridgeActionResult> completedFailure(String code, String message) {
        return CompletableFuture.completedFuture(BridgeActionResult.failure(400, code, message));
    }

    private static boolean hasUnsupportedField(Map<String, ?> object, Set<String> allowed) {
        return object.keySet().stream().anyMatch(key -> !allowed.contains(key));
    }

    private static boolean validOptionalSource(Object element) {
        if (element == null) {
            return true;
        }
        String value = requiredString(element);
        return SOURCE_PATTERN.matcher(value).matches();
    }

    private static boolean validOptionalUsername(Object element) {
        if (element == null) {
            return true;
        }
        String value = requiredString(element);
        return !value.isBlank()
            && value.length() <= 64
            && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static String requiredString(Object element) {
        return element instanceof String string ? string.trim() : "";
    }

    private static UUID parseCanonicalUuid(String value) {
        if (!UUID_PATTERN.matcher(value).matches()) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value) ? parsed : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
