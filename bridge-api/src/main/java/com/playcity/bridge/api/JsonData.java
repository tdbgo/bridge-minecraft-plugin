package com.playcity.bridge.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonData {
    private JsonData() {
    }

    static Map<String, Object> copyObject(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null) {
                throw new IllegalArgumentException("JSON object keys must not be null.");
            }
            copy.put(key, copyValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric)) {
                throw new IllegalArgumentException("JSON numbers must be finite.");
            }
            return number;
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> object = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("JSON object keys must be strings.");
                }
                object.put(stringKey, copyValue(nested));
            });
            return Collections.unmodifiableMap(object);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> list = new ArrayList<>();
            iterable.forEach(item -> list.add(copyValue(item)));
            return Collections.unmodifiableList(list);
        }
        throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass().getName());
    }
}
