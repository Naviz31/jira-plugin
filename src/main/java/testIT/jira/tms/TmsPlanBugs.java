package testIT.jira.tms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Collects the defects of a test plan from the links of its test points and their last results. */
final class TmsPlanBugs {
    private static final Logger log = LoggerFactory.getLogger(TmsPlanBugs.class);
    private static final Pattern ISSUE_KEY = Pattern.compile("([A-Za-z][A-Za-z0-9_]+-\\d+)(?:[/?#].*)?$");

    private TmsPlanBugs() {
    }

    private static final long CACHE_TTL_MS = 30_000L;
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private static final class Cached {
        final List<TmsPlanBugDto> bugs;
        final long loadedAt = System.currentTimeMillis();

        Cached(List<TmsPlanBugDto> bugs) {
            this.bugs = bugs;
        }
    }

    /** A link of a test point: a plain URL string, or (on other Test IT versions) an object with a "url". */
    static String linkUrl(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonObject()) {
            return TmsJson.getString(element.getAsJsonObject(), "url");
        }
        return null;
    }

    /** Only links a browser can follow are kept: the URL ends up in an href in the Jira page. */
    static boolean isHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * Never throws: a failure here must not break the test plan panel. Results are kept for a few seconds
     * because every page view and every refresh reads all test points of the plan.
     */
    static List<TmsPlanBugDto> load(
            TmsAdaptersClient client, String baseUrl, String token, String planId, Long projectGlobalId) {
        if (planId == null || planId.isEmpty()) {
            return new ArrayList<>();
        }
        String key = baseUrl + "|" + planId;
        Cached cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.loadedAt < CACHE_TTL_MS) {
            return cached.bugs;
        }
        try {
            List<JsonObject> points = client.getTestPlanPoints(baseUrl, token, planId);
            List<TmsPlanBugDto> bugs = collect(points, baseUrl, projectGlobalId);
            CACHE.put(key, new Cached(bugs));
            return bugs;
        } catch (Exception e) {
            log.warn("TMS test plan bugs failed for {}: {}", planId,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            return new ArrayList<>();
        }
    }

    static List<TmsPlanBugDto> collect(List<JsonObject> points, String baseUrl, Long projectGlobalId) {
        Map<String, TmsPlanBugDto> byUrl = new LinkedHashMap<>();
        for (JsonObject point : points) {
            TmsPlanBugDto.Test test = toTest(point, baseUrl, projectGlobalId);

            JsonObject lastResult = point.has("lastTestResult") && point.get("lastTestResult").isJsonObject()
                    ? point.getAsJsonObject("lastTestResult")
                    : null;
            if (lastResult != null && lastResult.has("links") && lastResult.get("links").isJsonArray()) {
                for (JsonElement element : lastResult.getAsJsonArray("links")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject link = element.getAsJsonObject();
                    String type = TmsJson.getString(link, "type");
                    if (type == null || !"defect".equals(type.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    String url = TmsJson.getString(link, "url");
                    if (!isHttpUrl(url)) {
                        continue;
                    }
                    TmsPlanBugDto bug = bugFor(byUrl, url.trim());
                    bug.defect = true;
                    String title = TmsJson.getString(link, "title");
                    if (title != null && !title.isEmpty()) {
                        bug.title = title;
                    }
                    addTest(bug, test);
                }
            }

            if (point.has("links") && point.get("links").isJsonArray()) {
                JsonArray links = point.getAsJsonArray("links");
                for (JsonElement element : links) {
                    String url = linkUrl(element);
                    if (!isHttpUrl(url) || extractKey(url) == null) {
                        continue;
                    }
                    TmsPlanBugDto bug = bugFor(byUrl, url.trim());
                    bug.linked = true;
                    addTest(bug, test);
                }
            }
        }
        return new ArrayList<>(byUrl.values());
    }

    private static TmsPlanBugDto bugFor(Map<String, TmsPlanBugDto> byUrl, String url) {
        String normalized = url;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String key = extractKey(normalized);
        // The same issue is often linked with different URL shapes (/browse/X-1, /projects/X/issues/X-1?filter=...).
        String identity = key != null ? hostOf(normalized) + "|" + key : normalized;
        TmsPlanBugDto bug = byUrl.get(identity);
        if (bug == null) {
            bug = new TmsPlanBugDto();
            bug.url = key != null ? browseUrl(normalized, key) : normalized;
            bug.key = key;
            byUrl.put(identity, bug);
        }
        return bug;
    }

    static String hostOf(String url) {
        try {
            String host = new java.net.URI(url).getHost();
            return host != null ? host.toLowerCase(Locale.ROOT) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Canonical /browse/KEY form of an issue URL, so the panel can reuse it for the local Jira. */
    static String browseUrl(String url, String key) {
        try {
            java.net.URI uri = new java.net.URI(url);
            if (uri.getScheme() == null || uri.getAuthority() == null) {
                return url;
            }
            String path = uri.getPath() != null ? uri.getPath() : "";
            int browse = path.indexOf("/browse/");
            int projects = path.indexOf("/projects/");
            int prefixEnd;
            if (browse >= 0) {
                prefixEnd = browse;
            } else if (projects >= 0 && path.indexOf("/issues/", projects) > projects) {
                prefixEnd = projects;
            } else {
                // Not a Jira-shaped URL (YouTrack, Redmine, ...): keep the link exactly as it was stored.
                return url;
            }
            return uri.getScheme() + "://" + uri.getAuthority() + path.substring(0, prefixEnd) + "/browse/" + key;
        } catch (Exception e) {
            return url;
        }
    }

    static String extractKey(String url) {
        if (url == null) {
            return null;
        }
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        Matcher matcher = ISSUE_KEY.matcher(path);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static TmsPlanBugDto.Test toTest(JsonObject point, String baseUrl, Long projectGlobalId) {
        TmsPlanBugDto.Test test = new TmsPlanBugDto.Test();
        test.name = TmsJson.getString(point, "name");
        test.globalId = TmsJson.getLong(point, "workItemGlobalId");
        test.status = statusName(point);
        if (test.globalId != null && projectGlobalId != null) {
            test.url = TmsUrlHelper.workItemUrl(baseUrl, projectGlobalId, test.globalId);
        }
        return test;
    }

    private static String statusName(JsonObject point) {
        if (point.has("statusModel") && point.get("statusModel").isJsonObject()) {
            String name = TmsJson.getString(point.getAsJsonObject("statusModel"), "name");
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return TmsJson.getString(point, "status");
    }

    private static void addTest(TmsPlanBugDto bug, TmsPlanBugDto.Test test) {
        for (TmsPlanBugDto.Test existing : bug.tests) {
            if (existing.globalId != null && existing.globalId.equals(test.globalId)) {
                return;
            }
        }
        bug.tests.add(test);
    }
}
