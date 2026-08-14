package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NoticePayloadPolicyTest {
    @Test
    void syncAllowsOnlyEnvelopeFields() {
        Map<String, Object> valid = new LinkedHashMap<>();
        valid.put("requestId", "request-1");
        valid.put("action", "minecraft.notice_sync");
        valid.put("source", "block_admin.notice.sync");
        assertEquals("", NoticePayloadPolicy.unexpectedSyncField(valid));

        valid.put("message", "not allowed");
        assertEquals("message", NoticePayloadPolicy.unexpectedSyncField(valid));
    }

    @Test
    void sendAllowsMessageButRejectsOtherFields() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", "request-1");
        payload.put("action", "minecraft.notice_send");
        payload.put("source", "block_admin.notice.send");
        payload.put("message", "Hello");
        assertEquals("", NoticePayloadPolicy.unexpectedSendField(payload));

        payload.put("command", "notice send something else");
        assertEquals("command", NoticePayloadPolicy.unexpectedSendField(payload));
    }
}
