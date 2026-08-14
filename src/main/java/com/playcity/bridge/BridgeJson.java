package com.playcity.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.playcity.bridge.api.BridgeActionResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BridgeJson {
    private BridgeJson() {
    }

    static Map<String, Object> toMap(JsonObject object) {
        if (object == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        object.entrySet().forEach(entry -> result.put(entry.getKey(), fromJson(entry.getValue())));
        return result;
    }

    static Map<String, Object> toMapExcluding(JsonObject object, String... excludedKeys) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(toMap(object));
        for (String key : Set.of(excludedKeys)) {
            result.remove(key);
        }
        return result;
    }

    static JsonObject toJsonObject(Map<String, ?> object) {
        JsonObject result = new JsonObject();
        if (object != null) {
            object.forEach((key, value) -> result.add(key, toJson(value)));
        }
        return result;
    }

    static JsonObject resultToJson(BridgeActionResult result, String requestId) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", result.ok());
        json.addProperty("status", result.ok() ? "succeeded" : "failed");
        json.addProperty("code", result.code());
        json.addProperty("message", result.message());
        if (requestId != null && !requestId.isBlank()) {
            json.addProperty("requestId", requestId);
        }
        json.add("data", toJsonObject(result.data()));
        return json;
    }

    private static Object fromJson(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            return toMap(element.getAsJsonObject());
        }
        if (element.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            element.getAsJsonArray().forEach(item -> values.add(fromJson(item)));
            return values;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsNumber();
        }
        return primitive.getAsString();
    }

    private static JsonElement toJson(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof String string) {
            return new JsonPrimitive(string);
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            map.forEach((key, nested) -> object.add(String.valueOf(key), toJson(nested)));
            return object;
        }
        if (value instanceof Iterable<?> iterable) {
            JsonArray array = new JsonArray();
            iterable.forEach(item -> array.add(toJson(item)));
            return array;
        }
        throw new IllegalArgumentException("Unsupported Bridge JSON value: " + value.getClass().getName());
    }
}
