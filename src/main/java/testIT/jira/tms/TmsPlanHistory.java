package testIT.jira.tms;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defects that Test IT counts for a plan but that are not on the last result of a test point: the test was
 * run again or reset afterwards. They are read from the result history of every test of the plan.
 */
final class TmsPlanHistory {
    private static final Logger log = LoggerFactory.getLogger(TmsPlanHistory.class);
    /** More tests than this and the history is skipped: it costs one request per test. */
    static final int MAX_WORK_ITEMS = 300;
    private static final int THREADS = 6;
    private static final long DEADLINE_SECONDS = 20L;

    private TmsPlanHistory() {
    }

    /** A defect link of an earlier result of a test, in this plan. */
    static final class Past {
        final String url;
        final String title;
        final String run;
        final String resultId;
        /** The test point (test + configuration) the result was for; null when it could not be resolved. */
        String pointId;

        Past(String url, String title, String run, String resultId) {
            this.url = url;
            this.title = title;
            this.run = run;
            this.resultId = resultId;
        }

        /** True when the defect belongs to this test point of the plan. */
        boolean matches(String point) {
            return pointId == null || point == null || pointId.equalsIgnoreCase(point);
        }
    }

    /**
     * Defects of earlier results by work item id. Never throws: the panel works without history.
     * Test points that are not part of the plan do not matter, results of other plans are dropped.
     */
    static Map<String, List<Past>> load(
            final TmsAdaptersClient client, final String baseUrl, final String token, final String planId,
            List<JsonObject> points) {
        Map<String, List<Past>> result = new HashMap<>();
        // Last result ids: those defects are already collected from the points themselves.
        Map<String, Set<String>> lastResults = new LinkedHashMap<>();
        for (JsonObject point : points) {
            String workItemId = TmsJson.getString(point, "workItemId");
            if (workItemId == null || workItemId.isEmpty()) {
                continue;
            }
            Set<String> last = lastResults.get(workItemId);
            if (last == null) {
                last = new HashSet<>();
                lastResults.put(workItemId, last);
            }
            if (point.has("lastTestResult") && point.get("lastTestResult").isJsonObject()) {
                String id = TmsJson.getString(point.getAsJsonObject("lastTestResult"), "id");
                if (id != null) {
                    last.add(id);
                }
            }
        }
        if (lastResults.isEmpty() || lastResults.size() > MAX_WORK_ITEMS || planId == null) {
            return result;
        }
        ExecutorService pool = Executors.newFixedThreadPool(THREADS, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "testit-plan-history");
                thread.setDaemon(true);
                return thread;
            }
        });
        try {
            Map<String, Future<List<JsonObject>>> futures = new LinkedHashMap<>();
            for (final String workItemId : lastResults.keySet()) {
                futures.put(workItemId, pool.submit(new Callable<List<JsonObject>>() {
                    @Override
                    public List<JsonObject> call() throws Exception {
                        return client.getWorkItemResults(baseUrl, token, workItemId);
                    }
                }));
            }
            // The history does not say which configuration a result was for: ask for the few results with a defect.
            final Map<String, Future<String>> pointIds = new LinkedHashMap<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
            for (Map.Entry<String, Future<List<JsonObject>>> entry : futures.entrySet()) {
                try {
                    long left = Math.max(1L, deadline - System.nanoTime());
                    List<JsonObject> results = entry.getValue().get(left, TimeUnit.NANOSECONDS);
                    List<Past> past = pastDefects(results, planId, lastResults.get(entry.getKey()));
                    if (!past.isEmpty()) {
                        result.put(entry.getKey(), past);
                        for (final Past item : past) {
                            if (item.resultId != null && !pointIds.containsKey(item.resultId)) {
                                pointIds.put(item.resultId, pool.submit(new Callable<String>() {
                                    @Override
                                    public String call() throws Exception {
                                        return TmsJson.getString(
                                                client.getTestResult(baseUrl, token, item.resultId), "testPointId");
                                    }
                                }));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("TMS result history failed for {}: {}", entry.getKey(), e.getMessage());
                }
            }
            for (List<Past> list : result.values()) {
                for (Past item : list) {
                    Future<String> future = pointIds.get(item.resultId);
                    if (future == null) {
                        continue;
                    }
                    try {
                        long left = Math.max(1L, deadline - System.nanoTime());
                        item.pointId = future.get(left, TimeUnit.NANOSECONDS);
                    } catch (Exception e) {
                        // Left unresolved: the defect then goes to every point of that test.
                        log.debug("TMS result {} lookup failed: {}", item.resultId, e.getMessage());
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    static List<Past> pastDefects(List<JsonObject> results, String planId, Set<String> lastResultIds) {
        List<Past> past = new ArrayList<>();
        if (results == null) {
            return past;
        }
        for (JsonObject res : results) {
            String resultPlan = TmsJson.getString(res, "testPlanId");
            if (resultPlan == null || !resultPlan.equalsIgnoreCase(planId.trim())) {
                continue;
            }
            String id = TmsJson.getString(res, "id");
            if (id != null && lastResultIds != null && lastResultIds.contains(id)) {
                continue;
            }
            if (!res.has("links") || !res.get("links").isJsonArray()) {
                continue;
            }
            String run = TmsJson.getString(res, "testRunName");
            for (JsonElement element : res.getAsJsonArray("links")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject link = element.getAsJsonObject();
                String type = TmsJson.getString(link, "type");
                String url = TmsJson.getString(link, "url");
                if (type == null || !"defect".equals(type.toLowerCase(Locale.ROOT)) || !TmsPlanBugs.isHttpUrl(url)) {
                    continue;
                }
                past.add(new Past(url.trim(), TmsJson.getString(link, "title"), run, id));
            }
        }
        return past;
    }
}
