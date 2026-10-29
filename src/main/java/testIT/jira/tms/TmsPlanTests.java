package testIT.jira.tms;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the test points of a plan once and derives from them everything the panel needs: the defects
 * and the list of tests with their statuses.
 */
final class TmsPlanTests {
    private static final Logger log = LoggerFactory.getLogger(TmsPlanTests.class);
    private static final long CACHE_TTL_MS = 30_000L;
    /** The panel shows at most this many tests per status; the rest are one click away in Test IT. */
    static final int PER_STATUS_LIMIT = 200;

    private static final Map<String, Result> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String[]> CONFIG_NAMES = new ConcurrentHashMap<>();
    private static final long CONFIG_TTL_MS = 60_000L;

    private TmsPlanTests() {
    }

    static final class Result {
        final List<TmsPlanBugDto> bugs;
        final List<TmsPlanPointDto> points;
        final boolean truncated;
        final long loadedAt = System.currentTimeMillis();

        Result(List<TmsPlanBugDto> bugs, List<TmsPlanPointDto> points, boolean truncated) {
            this.bugs = bugs;
            this.points = points;
            this.truncated = truncated;
        }
    }

    /** Never throws: a failure here must not break the test plan panel. */
    static Result load(
            TmsAdaptersClient client, String baseUrl, String token, String planId, Long projectGlobalId) {
        if (planId == null || planId.isEmpty()) {
            return new Result(new ArrayList<TmsPlanBugDto>(), new ArrayList<TmsPlanPointDto>(), false);
        }
        String key = baseUrl + "|" + planId;
        Result cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.loadedAt < CACHE_TTL_MS) {
            return cached;
        }
        try {
            List<JsonObject> points = client.getTestPlanPoints(baseUrl, token, planId);
            List<TmsPlanBugDto> bugs = TmsPlanBugs.collect(points, baseUrl, projectGlobalId);
            boolean[] truncated = new boolean[1];
            // Plans bigger than what is read from Test IT are cut too, not only per-status.
            truncated[0] = points.size() >= TmsAdaptersClient.TEST_POINTS_LIMIT;
            List<TmsPlanPointDto> tests = mapPoints(client, baseUrl, token, points, projectGlobalId, truncated);
            Result result = new Result(bugs, tests, truncated[0]);
            final long now = System.currentTimeMillis();
            for (java.util.Iterator<Result> it = CACHE.values().iterator(); it.hasNext();) {
                if (now - it.next().loadedAt >= CACHE_TTL_MS) {
                    it.remove();
                }
            }
            CACHE.put(key, result);
            return result;
        } catch (Exception e) {
            log.warn("TMS test plan tests failed for {}: {}", planId,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            return new Result(new ArrayList<TmsPlanBugDto>(), new ArrayList<TmsPlanPointDto>(), false);
        }
    }

    static List<TmsPlanPointDto> mapPoints(
            TmsAdaptersClient client, String baseUrl, String token, List<JsonObject> points,
            Long projectGlobalId, boolean[] truncated) {
        List<TmsPlanPointDto> result = new ArrayList<>();
        Map<String, Integer> perStatus = new HashMap<>();
        for (JsonObject point : points) {
            TmsPlanPointDto dto = new TmsPlanPointDto();
            dto.name = TmsJson.getString(point, "name");
            dto.globalId = TmsJson.getLong(point, "workItemGlobalId");
            if (dto.globalId != null && projectGlobalId != null) {
                dto.url = TmsUrlHelper.workItemUrl(baseUrl, projectGlobalId, dto.globalId);
            }
            fillStatus(point, dto);
            String statusKey = dto.status != null ? dto.status : "";
            int seen = perStatus.containsKey(statusKey) ? perStatus.get(statusKey) : 0;
            perStatus.put(statusKey, seen + 1);
            if (seen >= PER_STATUS_LIMIT) {
                truncated[0] = true;
                continue;
            }
            dto.section = TmsJson.getString(point, "sectionName");
            dto.suite = TmsJson.getString(point, "testSuiteName");
            dto.configuration = configurationName(client, baseUrl, token, TmsJson.getString(point, "configurationId"));
            String testerId = TmsJson.getString(point, "testerId");
            if (testerId != null && !testerId.isEmpty()) {
                // Left empty rather than showing a placeholder on every row when the token cannot read users.
                dto.tester = TmsPlanAttributes.findUserName(client, baseUrl, token, testerId);
            }
            fillLinks(point, dto);
            result.add(dto);
        }
        return result;
    }

    private static void fillStatus(JsonObject point, TmsPlanPointDto dto) {
        if (point.has("statusModel") && point.get("statusModel").isJsonObject()) {
            JsonObject model = point.getAsJsonObject("statusModel");
            dto.status = TmsJson.getString(model, "name");
            dto.statusType = TmsJson.getString(model, "type");
        }
        if (dto.status == null || dto.status.isEmpty()) {
            dto.status = TmsJson.getString(point, "status");
        }
    }

    private static void fillLinks(JsonObject point, TmsPlanPointDto dto) {
        Map<String, TmsPlanPointDto.Link> byIdentity = new HashMap<>();
        JsonObject last = point.has("lastTestResult") && point.get("lastTestResult").isJsonObject()
                ? point.getAsJsonObject("lastTestResult")
                : null;
        if (last != null && last.has("links") && last.get("links").isJsonArray()) {
            for (JsonElement element : last.getAsJsonArray("links")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject link = element.getAsJsonObject();
                String type = TmsJson.getString(link, "type");
                String url = TmsJson.getString(link, "url");
                if (type == null || !"defect".equals(type.toLowerCase(Locale.ROOT)) || !TmsPlanBugs.isHttpUrl(url)) {
                    continue;
                }
                addLink(dto, byIdentity, url.trim(), true);
            }
        }
        if (point.has("links") && point.get("links").isJsonArray()) {
            for (JsonElement element : point.getAsJsonArray("links")) {
                String url = TmsPlanBugs.linkUrl(element);
                if (TmsPlanBugs.isHttpUrl(url) && TmsPlanBugs.extractKey(url) != null) {
                    addLink(dto, byIdentity, url.trim(), false);
                }
            }
        }
    }

    private static void addLink(
            TmsPlanPointDto dto, Map<String, TmsPlanPointDto.Link> byIdentity, String url, boolean defect) {
        String key = TmsPlanBugs.extractKey(url);
        String identity = key != null ? TmsPlanBugs.hostOf(url) + "|" + key : url;
        TmsPlanPointDto.Link link = byIdentity.get(identity);
        if (link == null) {
            link = new TmsPlanPointDto.Link();
            link.key = key;
            link.url = key != null ? TmsPlanBugs.browseUrl(url, key) : url;
            byIdentity.put(identity, link);
            dto.links.add(link);
        }
        if (defect) {
            link.defect = true;
        }
    }

    private static String configurationName(TmsAdaptersClient client, String baseUrl, String token, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        String cacheKey = baseUrl + "|" + id;
        String[] cached = CONFIG_NAMES.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - Long.parseLong(cached[1]) < CONFIG_TTL_MS) {
            return cached[0];
        }
        String name = null;
        try {
            name = TmsJson.getString(client.getConfiguration(baseUrl, token, id), "name");
        } catch (Exception e) {
            log.debug("TMS configuration lookup failed for {}: {}", id, e.getMessage());
        }
        CONFIG_NAMES.put(cacheKey, new String[] {name, String.valueOf(System.currentTimeMillis())});
        return name;
    }
}
