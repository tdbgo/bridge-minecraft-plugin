package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class NoticeMessagePolicyTest {
    @Test
    void acceptsNonBlankStringWithinLimit() {
        Map<String, Object> payload = Map.of("message", "Server maintenance in 10 minutes");

        NoticeMessagePolicy.Validation validation = NoticeMessagePolicy.validate(payload, 64);

        assertTrue(validation.valid());
        assertEquals("Server maintenance in 10 minutes", validation.message());
    }

    @Test
    void rejectsMissingNonStringAndBlankMessages() {
        assertEquals(
            "invalid_notice_message",
            NoticeMessagePolicy.validate(Map.of(), 64).errorCode()
        );

        Map<String, Object> numberPayload = Map.of("message", 123);
        assertFalse(NoticeMessagePolicy.validate(numberPayload, 64).valid());

        Map<String, Object> blankPayload = Map.of("message", "   ");
        assertFalse(NoticeMessagePolicy.validate(blankPayload, 64).valid());
    }

    @Test
    void rejectsMessageOverConfiguredLimit() {
        Map<String, Object> payload = Map.of("message", "12345");

        NoticeMessagePolicy.Validation validation = NoticeMessagePolicy.validate(payload, 4);

        assertFalse(validation.valid());
        assertEquals("notice_message_too_long", validation.errorCode());
    }
}
