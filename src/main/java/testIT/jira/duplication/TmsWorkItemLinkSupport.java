package testIT.jira.duplication;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Locale;
import testIT.jira.tms.TmsAdaptersClient;
import testIT.jira.tms.TmsClientException;
import testIT.jira.tms.TmsJson;
import testIT.jira.tms.TmsWorkItemUnlinkService;

final class TmsWorkItemLinkSupport {
    private final TmsAdaptersClient client = new TmsAdaptersClient();

    /**
     * Same criterion as issue panel {@code isLinkedToIssue}: Issue link with /browse/.
     */
    boolean hasAnyIssueLink(JsonObject workItem) {
        return findFirstBrowseIssueKey(workItem) != null;
    }

    /**
     * Extracts issue key from the first Issue link whose URL contains {@code /browse/KEY}.
     */
    String findFirstBrowseIssueKey(JsonObject workItem) {
        if (workItem == null || !workItem.has("links") || !workItem.get("links").isJsonArray()) {
            return null;
        }
        for (JsonElement element : workItem.getAsJsonArray("links")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject link = element.getAsJsonObject();
            String type = TmsJson.getString(link, "type");
            String url = TmsJson.getString(link, "url");
            if (!"Issue".equalsIgnoreCase(type) || url == null) {
                continue;
            }
            String key = extractBrowseIssueKey(url);
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    static String extractBrowseIssueKey(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        int idx = lower.lastIndexOf("/browse/");
        if (idx < 0) {
            return null;
        }
        String rest = url.substring(idx + "/browse/".length()).trim();
        if (rest.isEmpty()) {
            return null;
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        int query = rest.indexOf('?');
        if (query >= 0) {
            rest = rest.substring(0, query);
        }
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }
        rest = rest.trim();
        return rest.isEmpty() ? null : rest.toUpperCase(Locale.ROOT);
    }

    /**
     * If WI name lacks {@code {issueKey}:} prefix, add it and persist.
     *
     * @return true if name was written to TMS
     */
    boolean ensureNamePrefixIfMissing(
            String tmsBaseUrl,
            String token,
            String workItemId,
            String issueKey,
            JsonObject workItem) throws TmsClientException, IOException {
        JsonObject target = workItem;
        if (target == null || target.entrySet().isEmpty()) {
            if (workItemId == null || workItemId.trim().isEmpty()) {
                return false;
            }
            target = client.getWorkItem(tmsBaseUrl, token, workItemId.trim());
            if (target.entrySet().isEmpty()) {
                throw new IllegalArgumentException("Work item not found: " + workItemId);
            }
        }
        if (!ensureIssueKeyNamePrefix(target, issueKey)) {
            return false;
        }
        client.updateWorkItem(tmsBaseUrl, token, TmsWorkItemUnlinkService.buildUpdatePayload(target));
        return true;
    }

    /**
     * Adds Issue-link and ensures WI {@code name} has {@code {issueKey}: } prefix (Create TestCase style).
     */
    void addIssueLink(
            String tmsBaseUrl,
            String token,
            String workItemId,
            String issueKey,
            String issueUrl,
            String title) throws TmsClientException, IOException {
        JsonObject workItem = client.getWorkItem(tmsBaseUrl, token, workItemId);
        if (workItem.entrySet().isEmpty()) {
            throw new IllegalArgumentException("Work item not found: " + workItemId);
        }
        if (!workItem.has("links") || !workItem.get("links").isJsonArray()) {
            workItem.add("links", new JsonArray());
        }

        String key = issueKey != null ? issueKey.trim() : "";
        String browseSuffix = "/browse/" + key.toUpperCase(Locale.ROOT);
        boolean linkExists = false;
        for (JsonElement element : workItem.getAsJsonArray("links")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject existing = element.getAsJsonObject();
            String type = TmsJson.getString(existing, "type");
            String url = TmsJson.getString(existing, "url");
            if ("Issue".equalsIgnoreCase(type) && url != null
                    && url.toUpperCase(Locale.ROOT).contains(browseSuffix)) {
                linkExists = true;
                break;
            }
        }

        boolean nameChanged = ensureIssueKeyNamePrefix(workItem, key);
        String resolvedTitle = title;
        if (resolvedTitle == null || resolvedTitle.trim().isEmpty()) {
            String name = TmsJson.getString(workItem, "name");
            resolvedTitle = name != null && !name.isEmpty() ? name : key;
        }

        if (!linkExists) {
            JsonObject link = new JsonObject();
            link.addProperty("type", "Issue");
            link.addProperty("url", issueUrl);
            link.addProperty("title", resolvedTitle);
            workItem.getAsJsonArray("links").add(link);
        }

        if (!linkExists || nameChanged) {
            JsonObject payload = TmsWorkItemUnlinkService.buildUpdatePayload(workItem);
            client.updateWorkItem(tmsBaseUrl, token, payload);
        }
    }

    /**
     * @return true if name was updated
     */
    static boolean ensureIssueKeyNamePrefix(JsonObject workItem, String issueKey) {
        if (workItem == null || issueKey == null || issueKey.trim().isEmpty()) {
            return false;
        }
        String key = issueKey.trim();
        String name = TmsJson.getString(workItem, "name");
        String prefix = key.toUpperCase(Locale.ROOT) + ":";
        if (name != null && name.toUpperCase(Locale.ROOT).startsWith(prefix)) {
            return false;
        }
        String rest = name != null ? name.trim() : "";
        if (rest.isEmpty()) {
            workItem.addProperty("name", key);
        } else {
            workItem.addProperty("name", key + ": " + rest);
        }
        return true;
    }
}
