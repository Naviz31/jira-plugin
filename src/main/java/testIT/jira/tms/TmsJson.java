package testIT.jira.tms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

public final class TmsJson {
    private static final String[] HISTORY_ARRAY_KEYS = {"items", "data", "results", "value"};
    private static final String[] RESULT_DATE_KEYS = {"completedOn", "date", "createdDate", "modifiedDate", "startedOn"};

    private TmsJson() {
    }

    public static String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    static Long getLong(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    static boolean getBoolean(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return false;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    static List<JsonObject> parseObjectArray(String body) {
        List<JsonObject> items = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return items;
        }
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonArray()) {
            return items;
        }
        return extractObjectArray(root.getAsJsonArray());
    }

    static List<JsonObject> parseHistoryResponse(String body) {
        if (body == null || body.isEmpty()) {
            return new ArrayList<>();
        }
        JsonElement root = JsonParser.parseString(body);
        if (root.isJsonArray()) {
            return extractObjectArray(root.getAsJsonArray());
        }
        if (!root.isJsonObject()) {
            return new ArrayList<>();
        }
        JsonObject object = root.getAsJsonObject();
        for (String key : HISTORY_ARRAY_KEYS) {
            if (object.has(key) && object.get(key).isJsonArray()) {
                return extractObjectArray(object.getAsJsonArray(key));
            }
        }
        return new ArrayList<>();
    }

    static JsonObject parseObject(String body) {
        if (body == null || body.isEmpty()) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(body);
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    static String extractResultDate(JsonObject object) {
        if (object == null) {
            return null;
        }
        for (String key : RESULT_DATE_KEYS) {
            String value = getString(object, key);
            if (value != null && !value.isEmpty()) {
                return formatDate(value);
            }
        }
        return null;
    }

    static String formatDateField(JsonObject object, String key) {
        if (object == null || key == null) {
            return null;
        }
        String value = getString(object, key);
        if (value != null && !value.isEmpty()) {
            return formatDate(value);
        }
        return null;
    }

    private static List<JsonObject> extractObjectArray(JsonArray array) {
        List<JsonObject> items = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                items.add(element.getAsJsonObject());
            }
        }
        return items;
    }

    private static String formatDate(String isoDate) {
        if (isoDate == null) {
            return null;
        }
        String trimmed = isoDate.trim();
        if (trimmed.length() >= 10 && trimmed.charAt(4) == '-' && trimmed.charAt(7) == '-') {
            String[] parts = trimmed.substring(0, 10).split("-");
            if (parts.length == 3) {
                return parts[2] + "/" + parts[1] + "/" + parts[0];
            }
        }
        return trimmed.length() > 10 ? trimmed.substring(0, 10) : trimmed;
    }
}
