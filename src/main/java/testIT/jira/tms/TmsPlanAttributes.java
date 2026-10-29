package testIT.jira.tms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resolves the custom attributes of a test plan into display-ready values. */
final class TmsPlanAttributes {
    private static final Logger log = LoggerFactory.getLogger(TmsPlanAttributes.class);
    private static final long CACHE_TTL_MS = 60_000L;

    private static final Map<String, Cached<List<JsonObject>>> DEFINITIONS = new ConcurrentHashMap<>();
    private static final Map<String, Cached<String>> USER_NAMES = new ConcurrentHashMap<>();

    private TmsPlanAttributes() {
    }

    private static final class Cached<T> {
        final T value;
        final long loadedAt = System.currentTimeMillis();

        Cached(T value) {
            this.value = value;
        }

        boolean isFresh() {
            return System.currentTimeMillis() - loadedAt < CACHE_TTL_MS;
        }
    }

    /** Never throws: a failure here must not break the test plan panel. */
    static List<TmsPlanAttributeDto> load(
            TmsAdaptersClient client, String baseUrl, String token, String projectId, JsonObject plan) {
        List<TmsPlanAttributeDto> result = new ArrayList<>();
        if (plan == null || projectId == null || projectId.trim().isEmpty()) {
            return result;
        }
        try {
            List<JsonObject> definitions = loadDefinitions(client, baseUrl, token, projectId.trim());
            JsonObject values = plan.has("attributes") && plan.get("attributes").isJsonObject()
                    ? plan.getAsJsonObject("attributes")
                    : new JsonObject();
            for (JsonObject definition : definitions) {
                if (TmsJson.getBoolean(definition, "isDeleted") || !isEnabled(definition)) {
                    continue;
                }
                TmsPlanAttributeDto dto = mapAttribute(client, baseUrl, token, definition, values);
                if (dto != null) {
                    result.add(dto);
                }
            }
        } catch (Exception e) {
            log.warn("TMS test plan attributes failed for project {}: {}", projectId,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        }
        return result;
    }

    private static boolean isEnabled(JsonObject definition) {
        return !definition.has("isEnabled") || definition.get("isEnabled").isJsonNull()
                || TmsJson.getBoolean(definition, "isEnabled");
    }

    private static List<JsonObject> loadDefinitions(
            TmsAdaptersClient client, String baseUrl, String token, String projectId) throws Exception {
        String key = baseUrl + "|" + projectId;
        Cached<List<JsonObject>> cached = DEFINITIONS.get(key);
        if (cached != null && cached.isFresh()) {
            return cached.value;
        }
        List<JsonObject> loaded = client.getTestPlanAttributeDefinitions(baseUrl, token, projectId);
        DEFINITIONS.put(key, new Cached<>(loaded));
        return loaded;
    }

    private static TmsPlanAttributeDto mapAttribute(
            TmsAdaptersClient client, String baseUrl, String token, JsonObject definition, JsonObject values) {
        String id = TmsJson.getString(definition, "id");
        if (id == null || id.isEmpty()) {
            return null;
        }
        TmsPlanAttributeDto dto = new TmsPlanAttributeDto();
        dto.id = id;
        dto.name = TmsJson.getString(definition, "name");
        dto.type = TmsJson.getString(definition, "type");
        dto.required = TmsJson.getBoolean(definition, "isRequired");

        JsonElement raw = values.has(id) ? values.get(id) : null;
        if (raw == null || raw.isJsonNull()) {
            return dto;
        }
        String type = dto.type != null ? dto.type : "";
        switch (type) {
            case "checkbox":
                dto.checked = raw.isJsonPrimitive() && raw.getAsBoolean();
                break;
            case "options":
            case "multipleOptions":
                Map<String, String> optionTitles = optionTitles(definition);
                for (String optionId : asStrings(raw)) {
                    String title = optionTitles.get(optionId);
                    dto.values.add(title != null ? title : "Значение удалено");
                }
                break;
            case "user":
                for (String userId : asStrings(raw)) {
                    dto.values.add(resolveUserName(client, baseUrl, token, userId));
                }
                break;
            case "datetime":
                for (String value : asStrings(raw)) {
                    JsonObject holder = new JsonObject();
                    holder.addProperty("v", value);
                    String formatted = TmsJson.formatDateField(holder, "v");
                    dto.values.add(formatted != null ? formatted : value);
                }
                break;
            default:
                for (String value : asStrings(raw)) {
                    dto.values.add(value);
                }
                break;
        }
        return dto;
    }

    private static Map<String, String> optionTitles(JsonObject definition) {
        Map<String, String> titles = new HashMap<>();
        if (definition.has("options") && definition.get("options").isJsonArray()) {
            for (JsonElement element : definition.getAsJsonArray("options")) {
                if (element.isJsonObject()) {
                    JsonObject option = element.getAsJsonObject();
                    String optionId = TmsJson.getString(option, "id");
                    String value = TmsJson.getString(option, "value");
                    if (optionId != null && value != null) {
                        titles.put(optionId, value);
                    }
                }
            }
        }
        return titles;
    }

    private static List<String> asStrings(JsonElement raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isJsonNull()) {
            return out;
        }
        if (raw.isJsonArray()) {
            JsonArray array = raw.getAsJsonArray();
            for (JsonElement element : array) {
                if (element.isJsonPrimitive()) {
                    String s = element.getAsString();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
        } else if (raw.isJsonPrimitive()) {
            String s = raw.getAsString();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    static final String UNKNOWN_USER = "Пользователь недоступен";

    /** The user's name, or null when Test IT does not let the configured token look the user up. */
    static String findUserName(TmsAdaptersClient client, String baseUrl, String token, String userId) {
        String name = resolveUserName(client, baseUrl, token, userId);
        return UNKNOWN_USER.equals(name) ? null : name;
    }

    static String resolveUserName(TmsAdaptersClient client, String baseUrl, String token, String userId) {
        String key = baseUrl + "|" + userId;
        Cached<String> cached = USER_NAMES.get(key);
        if (cached != null && cached.isFresh()) {
            return cached.value;
        }
        String name = UNKNOWN_USER;
        try {
            JsonObject user = client.getUser(baseUrl, token, userId);
            String display = TmsJson.getString(user, "displayName");
            if (display == null || display.isEmpty()) {
                String first = TmsJson.getString(user, "firstName");
                String last = TmsJson.getString(user, "lastName");
                display = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
            }
            if (display == null || display.isEmpty()) {
                display = TmsJson.getString(user, "userName");
            }
            if (display != null && !display.isEmpty()) {
                name = display;
            }
        } catch (Exception e) {
            log.debug("TMS user lookup failed for {}: {}", userId, e.getMessage());
        }
        USER_NAMES.put(key, new Cached<>(name));
        return name;
    }
}
