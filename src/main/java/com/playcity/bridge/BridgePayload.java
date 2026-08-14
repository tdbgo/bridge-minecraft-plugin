package com.playcity.bridge;

import java.util.Map;

final class BridgePayload {
    private BridgePayload() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, ?> parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return (Map<String, Object>) map;
    }

    static String string(Map<String, ?> parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof String string ? string.trim() : "";
    }

    static Double number(Map<String, ?> parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        if (!(value instanceof Number number)) {
            return null;
        }
        double result = number.doubleValue();
        return Double.isFinite(result) ? result : null;
    }

    static Float floatNumber(Map<String, ?> parent, String key) {
        Double value = number(parent, key);
        if (value == null) {
            return null;
        }
        float result = value.floatValue();
        return Float.isFinite(result) ? result : null;
    }

    static boolean hasValue(Map<String, ?> parent, String key) {
        return parent != null && parent.containsKey(key) && parent.get(key) != null;
    }
}
