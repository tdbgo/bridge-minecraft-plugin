package com.playcity.bridge;

import java.util.Map;

final class NoticeMessagePolicy {
    private NoticeMessagePolicy() {
    }

    static Validation validate(Map<String, ?> payload, int maxLength) {
        Object value = payload == null ? null : payload.get("message");
        if (!(value instanceof String message)) {
            return Validation.failure("invalid_notice_message", "message must be a JSON string.");
        }
        if (message.isBlank()) {
            return Validation.failure("invalid_notice_message", "message must not be blank.");
        }
        if (message.length() > maxLength) {
            return Validation.failure("notice_message_too_long", "message exceeds the configured length limit.");
        }
        return Validation.success(message);
    }

    record Validation(boolean valid, String message, String errorCode, String errorMessage) {
        private static Validation success(String message) {
            return new Validation(true, message, "", "");
        }

        private static Validation failure(String errorCode, String errorMessage) {
            return new Validation(false, "", errorCode, errorMessage);
        }
    }
}
