package com.playcity.bridge;

import java.util.Map;
import java.util.Set;

final class NoticePayloadPolicy {
    private static final Set<String> SYNC_FIELDS = Set.of("requestId", "action", "source");
    private static final Set<String> SEND_FIELDS = Set.of("requestId", "action", "source", "message");

    private NoticePayloadPolicy() {
    }

    static String unexpectedSyncField(Map<String, ?> payload) {
        return unexpectedField(payload, SYNC_FIELDS);
    }

    static String unexpectedSendField(Map<String, ?> payload) {
        return unexpectedField(payload, SEND_FIELDS);
    }

    private static String unexpectedField(Map<String, ?> payload, Set<String> allowedFields) {
        if (payload == null) {
            return "";
        }
        return payload.keySet().stream()
            .filter(field -> !allowedFields.contains(field))
            .sorted()
            .findFirst()
            .orElse("");
    }
}
