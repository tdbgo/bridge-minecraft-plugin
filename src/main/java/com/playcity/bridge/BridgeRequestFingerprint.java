package com.playcity.bridge;

import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BridgeRequestFingerprint {
    private BridgeRequestFingerprint() {
    }

    static String create(String actionName, Map<String, ?> payload) {
        Map<String, Object> actionPayload = new LinkedHashMap<>();
        if (payload != null) {
            actionPayload.putAll(payload);
        }
        actionPayload.remove("requestId");
        actionPayload.remove("action");

        String canonical = actionName + "\n" + canonicalJson(actionPayload);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String canonicalJson(Object element) {
        if (element == null) {
            return "null";
        }
        if (element instanceof String string) {
            return new JsonPrimitive(string).toString();
        }
        if (element instanceof Number || element instanceof Boolean) {
            return String.valueOf(element);
        }
        if (element instanceof Iterable<?> iterable) {
            StringBuilder result = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    result.append(',');
                }
                first = false;
                result.append(canonicalJson(item));
            }
            return result.append(']').toString();
        }

        if (!(element instanceof Map<?, ?> rawObject)) {
            throw new IllegalArgumentException("Unsupported Bridge payload value.");
        }
        Map<String, Object> object = new LinkedHashMap<>();
        rawObject.forEach((key, value) -> object.put(String.valueOf(key), value));
        List<String> keys = new ArrayList<>(object.keySet());
        keys.sort(String::compareTo);
        StringBuilder result = new StringBuilder("{");
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                result.append(',');
            }
            first = false;
            result.append(new JsonPrimitive(key));
            result.append(':');
            result.append(canonicalJson(object.get(key)));
        }
        return result.append('}').toString();
    }
}
