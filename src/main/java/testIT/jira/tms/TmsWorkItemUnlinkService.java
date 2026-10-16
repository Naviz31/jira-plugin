package testIT.jira.tms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes Jira Issue link from a TMS work item so the WI no longer appears on the issue panel.
 * Also strips the {@code {issueKey}:} name prefix used by plugin-created WIs for discovery.
 */
public class TmsWorkItemUnlinkService {
    private static final Logger log = LoggerFactory.getLogger(TmsWorkItemUnlinkService.class);

    private final TmsAdaptersClient client = new TmsAdaptersClient();

    public void unlinkWorkItemFromIssue(
            String tmsBaseUrl,
            String token,
            String workItemId,
            String issueKey,
            String issueUrl) throws TmsClientException, IOException {
        if (workItemId == null || workItemId.trim().isEmpty()) {
            throw new IllegalArgumentException("workItemId is required");
        }
        if (issueKey == null || issueKey.trim().isEmpty()) {
            throw new IllegalArgumentException("issueKey is required");
        }

        JsonObject workItem = client.getWorkItem(tmsBaseUrl, token, workItemId.trim());
        if (workItem.entrySet().isEmpty()) {
            throw new IllegalArgumentException("Work item not found: " + workItemId);
        }

        String browseSuffix = "/browse/" + issueKey.trim().toUpperCase(Locale.ROOT);
        String normalizedIssueUrl = normalizeLinkUrl(issueUrl);

        boolean linksChanged = removeIssueLinks(workItem, browseSuffix, normalizedIssueUrl);
        boolean nameChanged = stripIssueKeyPrefix(workItem, issueKey.trim());

        if (!linksChanged && !nameChanged) {
            log.info("Work item {} already unlinked from issue {}", workItemId, issueKey);
            return;
        }

        JsonObject updatePayload = buildUpdatePayload(workItem);
        client.updateWorkItem(tmsBaseUrl, token, updatePayload);
    }

    private static boolean removeIssueLinks(JsonObject workItem, String browseSuffix, String normalizedIssueUrl) {
        if (!workItem.has("links") || !workItem.get("links").isJsonArray()) {
            workItem.add("links", new JsonArray());
            return false;
        }
        JsonArray original = workItem.getAsJsonArray("links");
        JsonArray filtered = new JsonArray();
        boolean removed = false;
        for (JsonElement element : original) {
            if (!element.isJsonObject()) {
                filtered.add(element);
                continue;
            }
            JsonObject link = element.getAsJsonObject();
            String type = TmsJson.getString(link, "type");
            String url = TmsJson.getString(link, "url");
            if ("Issue".equalsIgnoreCase(type) && url != null) {
                String normalized = normalizeLinkUrl(url);
                if (normalized.contains(browseSuffix)
                        || (normalizedIssueUrl != null && normalized.equalsIgnoreCase(normalizedIssueUrl))) {
                    removed = true;
                    continue;
                }
            }
            filtered.add(link);
        }
        workItem.add("links", filtered);
        return removed;
    }

    private static boolean stripIssueKeyPrefix(JsonObject workItem, String issueKey) {
        String name = TmsJson.getString(workItem, "name");
        if (name == null) {
            return false;
        }
        String prefix = issueKey.toUpperCase(Locale.ROOT) + ":";
        String upperName = name.toUpperCase(Locale.ROOT);
        if (!upperName.startsWith(prefix)) {
            return false;
        }
        String rest = name.substring(prefix.length()).trim();
        if (rest.isEmpty()) {
            rest = issueKey + " test case";
        }
        workItem.addProperty("name", rest);
        return true;
    }

    /**
     * Maps GET work item JSON to UpdateWorkItemApiModel-shaped payload.
     */
    public static JsonObject buildUpdatePayload(JsonObject workItem) {
        JsonObject payload = new JsonObject();
        copyRequired(payload, workItem, "id");
        copyIfPresent(payload, workItem, "sectionId");
        copyIfPresent(payload, workItem, "description");
        copyIfPresent(payload, workItem, "state");
        copyIfPresent(payload, workItem, "priority");
        copyIfPresent(payload, workItem, "sourceType");
        copyArrayOrEmpty(payload, workItem, "steps");
        copyArrayOrEmpty(payload, workItem, "preconditionSteps");
        copyArrayOrEmpty(payload, workItem, "postconditionSteps");
        if (workItem.has("duration") && !workItem.get("duration").isJsonNull()) {
            payload.add("duration", workItem.get("duration"));
        } else {
            payload.addProperty("duration", 60000L);
        }
        if (workItem.has("attributes") && workItem.get("attributes").isJsonObject()) {
            payload.add("attributes", workItem.get("attributes"));
        } else {
            payload.add("attributes", new JsonObject());
        }
        copyArrayOrEmpty(payload, workItem, "tags");
        copyArrayOrEmpty(payload, workItem, "links");
        copyRequired(payload, workItem, "name");
        copyArrayOrEmpty(payload, workItem, "attachments");
        copyIfPresent(payload, workItem, "iterations");
        copyIfPresent(payload, workItem, "autoTests");
        copyIfPresent(payload, workItem, "parameters");
        return payload;
    }

    private static void copyRequired(JsonObject target, JsonObject source, String field) {
        if (!source.has(field) || source.get(field).isJsonNull()) {
            throw new IllegalStateException("Work item is missing required field: " + field);
        }
        target.add(field, source.get(field));
    }

    private static void copyIfPresent(JsonObject target, JsonObject source, String field) {
        if (source.has(field) && !source.get(field).isJsonNull()) {
            target.add(field, source.get(field));
        }
    }

    private static void copyArrayOrEmpty(JsonObject target, JsonObject source, String field) {
        if (source.has(field) && source.get(field).isJsonArray()) {
            target.add(field, source.get(field));
        } else {
            target.add(field, new JsonArray());
        }
    }

    private static String normalizeLinkUrl(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            trimmed = trimmed.substring(8);
        } else if (trimmed.regionMatches(true, 0, "http://", 0, 7)) {
            trimmed = trimmed.substring(7);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
