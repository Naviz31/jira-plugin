package testIT.jira.tms;

import com.atlassian.jira.issue.Issue;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TmsIssueTestPlanService {
    private static final Logger log = LoggerFactory.getLogger(TmsIssueTestPlanService.class);

    private static final List<String> STATUS_TYPE_ORDER = Arrays.asList(
            "Pending", "InProgress", "Succeeded", "Failed", "Incomplete");

    /** Analytics outcome group labels that must not appear as workflow status rows. */
    private static final List<String> ANALYTICS_OUTCOME_GROUPS = Arrays.asList(
            "PASSED", "NORESULTS");

    private final TmsAdaptersClient tmsClient = new TmsAdaptersClient();

    public TmsIssueTestPlansDto listLinkedTestPlans(
            Issue issue,
            String tmsBaseUrl,
            String token,
            IssueTestPlanLinkStore store) {
        TmsIssueTestPlansDto result = new TmsIssueTestPlansDto();
        result.issueKey = issue.getKey();
        Map<String, List<TmsTestStatusDto>> workflowStatusCache = new HashMap<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        for (String url : store.getUrls(issue)) {
            String normalized = TmsTestPlanUrlHelper.normalizeLinkUrl(url);
            if (!seenUrls.add(normalized)) {
                continue;
            }
            result.testPlans.add(resolveTestPlanRow(tmsBaseUrl, token, url, workflowStatusCache));
        }
        return result;
    }

    public TmsTestPlanRowDto addTestPlanLink(
            Issue issue,
            String rawUrl,
            String tmsBaseUrl,
            String token,
            IssueTestPlanLinkStore store) {
        TmsTestPlanUrlHelper.ParsedTestPlanUrl parsed = TmsTestPlanUrlHelper.parse(rawUrl, tmsBaseUrl);
        store.addUrl(issue, parsed.canonicalUrl);
        return resolveTestPlanRow(tmsBaseUrl, token, parsed.canonicalUrl, new HashMap<>());
    }

    public boolean removeTestPlanLink(
            Issue issue,
            String rawUrl,
            String tmsBaseUrl,
            IssueTestPlanLinkStore store) {
        TmsTestPlanUrlHelper.ParsedTestPlanUrl parsed = TmsTestPlanUrlHelper.parse(rawUrl, tmsBaseUrl);
        return store.removeUrl(issue, parsed.canonicalUrl);
    }

    private TmsTestPlanRowDto resolveTestPlanRow(
            String tmsBaseUrl,
            String token,
            String url,
            Map<String, List<TmsTestStatusDto>> workflowStatusCache) {
        TmsTestPlanUrlHelper.ParsedTestPlanUrl parsed;
        try {
            parsed = TmsTestPlanUrlHelper.parse(url, tmsBaseUrl);
        } catch (IllegalArgumentException e) {
            TmsTestPlanRowDto fallback = new TmsTestPlanRowDto();
            fallback.url = url;
            fallback.name = url;
            fallback.tests = mapTestCounts(new JsonObject(), new ArrayList<>());
            return fallback;
        }
        try {
            TmsTestPlanRowDto row = loadTestPlanRow(tmsBaseUrl, token, parsed, workflowStatusCache);
            if (row != null) {
                return row;
            }
        } catch (IOException | TmsClientException e) {
            log.warn("TMS test plan fetch failed for {}: {}",
                    parsed.canonicalUrl, e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        }
        return fallbackRow(parsed, resolveWorkflowStatuses(
                tmsBaseUrl, token, null, parsed.projectGlobalId, workflowStatusCache));
    }

    private TmsTestPlanRowDto loadTestPlanRow(
            String tmsBaseUrl,
            String token,
            TmsTestPlanUrlHelper.ParsedTestPlanUrl parsed,
            Map<String, List<TmsTestStatusDto>> workflowStatusCache) throws IOException, TmsClientException {
        String planId = String.valueOf(parsed.testPlanGlobalId);
        JsonObject plan = tmsClient.getTestPlan(tmsBaseUrl, token, planId);
        if (plan.entrySet().isEmpty()) {
            return null;
        }
        JsonObject analytics;
        try {
            analytics = tmsClient.getTestPlanAnalytics(tmsBaseUrl, token, planId);
        } catch (IOException | TmsClientException e) {
            log.warn("TMS test plan analytics failed for {}: {}", planId, e.getMessage());
            analytics = new JsonObject();
        }
        String planProjectId = TmsJson.getString(plan, "projectId");
        List<TmsTestStatusDto> statusCatalog = resolveWorkflowStatuses(
                tmsBaseUrl, token, planProjectId, parsed.projectGlobalId, workflowStatusCache);
        TmsTestPlanRowDto row = mapTestPlanRow(plan, parsed, analytics, statusCatalog);
        row.attributes = TmsPlanAttributes.load(tmsClient, tmsBaseUrl, token, planProjectId, plan);
        TmsPlanTests.Result planTests = TmsPlanTests.load(tmsClient, tmsBaseUrl, token, row.id, parsed.projectGlobalId);
        row.bugs = planTests.bugs;
        row.points = planTests.points;
        row.pointsTruncated = planTests.truncated;
        return row;
    }

    private List<TmsTestStatusDto> resolveWorkflowStatuses(
            String tmsBaseUrl,
            String token,
            String planProjectId,
            long projectGlobalId,
            Map<String, List<TmsTestStatusDto>> workflowStatusCache) {
        String cacheKey = planProjectId != null && !planProjectId.isEmpty()
                ? planProjectId
                : String.valueOf(projectGlobalId);
        if (workflowStatusCache.containsKey(cacheKey)) {
            return workflowStatusCache.get(cacheKey);
        }
        List<TmsTestStatusDto> statuses = new ArrayList<>();
        if (planProjectId != null && !planProjectId.isEmpty()) {
            statuses = tmsClient.getWorkflowStatusesForProject(tmsBaseUrl, token, planProjectId);
        }
        if (statuses.isEmpty()) {
            statuses = tmsClient.getWorkflowStatusesForProject(tmsBaseUrl, token, String.valueOf(projectGlobalId));
        }
        workflowStatusCache.put(cacheKey, statuses);
        if (planProjectId != null && !planProjectId.isEmpty()
                && !cacheKey.equals(String.valueOf(projectGlobalId))) {
            workflowStatusCache.put(String.valueOf(projectGlobalId), statuses);
        }
        return statuses;
    }

    private static TmsTestPlanRowDto fallbackRow(
            TmsTestPlanUrlHelper.ParsedTestPlanUrl parsed,
            List<TmsTestStatusDto> statusCatalog) {
        TmsTestPlanRowDto row = new TmsTestPlanRowDto();
        row.globalId = parsed.testPlanGlobalId;
        row.projectGlobalId = parsed.projectGlobalId;
        row.url = parsed.canonicalUrl;
        row.name = "Test plan " + parsed.testPlanGlobalId;
        row.tests = mapTestCounts(new JsonObject(), statusCatalog);
        return row;
    }

    private TmsTestPlanRowDto mapTestPlanRow(
            JsonObject plan,
            TmsTestPlanUrlHelper.ParsedTestPlanUrl parsed,
            JsonObject analytics,
            List<TmsTestStatusDto> statusCatalog) {
        TmsTestPlanRowDto row = new TmsTestPlanRowDto();
        row.id = TmsJson.getString(plan, "id");
        row.globalId = TmsJson.getLong(plan, "globalId");
        if (row.globalId == null) {
            row.globalId = parsed.testPlanGlobalId;
        }
        row.projectGlobalId = parsed.projectGlobalId;
        row.name = TmsJson.getString(plan, "name");
        if (row.name == null || row.name.isEmpty()) {
            row.name = "Test plan " + row.globalId;
        }
        row.url = parsed.canonicalUrl;
        row.build = TmsJson.getString(plan, "build");
        row.productName = TmsJson.getString(plan, "productName");
        row.startDate = TmsJson.formatDateField(plan, "startDate");
        row.endDate = TmsJson.formatDateField(plan, "endDate");

        String statusType = extractStatusType(plan);
        row.statusType = statusType;
        row.statusLabel = mapStatusLabel(statusType);
        row.tests = mapTestCounts(analytics, statusCatalog);
        return row;
    }

    private static String extractStatusType(JsonObject plan) {
        if (plan == null || !plan.has("status") || plan.get("status").isJsonNull()) {
            return null;
        }
        JsonElement statusElement = plan.get("status");
        if (statusElement.isJsonObject()) {
            JsonObject statusObject = statusElement.getAsJsonObject();
            String type = TmsJson.getString(statusObject, "type");
            if (type != null && !type.isEmpty()) {
                return type;
            }
            String name = TmsJson.getString(statusObject, "name");
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        if (statusElement.isJsonPrimitive()) {
            return statusElement.getAsString();
        }
        return null;
    }

    static String mapStatusLabel(String statusType) {
        if (statusType == null) {
            return "—";
        }
        switch (statusType) {
            case "InProgress":
                return "в процессе";
            case "Completed":
                return "завершен";
            case "New":
                return "новый";
            case "Paused":
                return "на паузе";
            default:
                return statusType;
        }
    }

    private static TmsTestPlanCountsDto mapTestCounts(
            JsonObject analytics,
            List<TmsTestStatusDto> statusCatalog) {
        TmsTestPlanCountsDto counts = new TmsTestPlanCountsDto();
        if (analytics == null) {
            analytics = new JsonObject();
        }

        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, AnalyticsStatusHit> analyticsHits = new LinkedHashMap<>();
        int total = 0;
        if (analytics.has("countGroupByStatusType") && analytics.get("countGroupByStatusType").isJsonArray()) {
            for (JsonElement element : analytics.getAsJsonArray("countGroupByStatusType")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject group = element.getAsJsonObject();
                String statusType = TmsJson.getString(group, "statusType");
                if (statusType == null || statusType.isEmpty()) {
                    continue;
                }
                int count = extractGroupValue(group);
                boolean hasStatuses = group.has("statuses") && group.get("statuses").isJsonArray()
                        && group.getAsJsonArray("statuses").size() > 0;
                if (hasStatuses) {
                    int nestedSum = 0;
                    for (JsonElement statusElement : group.getAsJsonArray("statuses")) {
                        if (!statusElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject status = statusElement.getAsJsonObject();
                        String name = firstNonEmpty(
                                TmsJson.getString(status, "statusName"),
                                TmsJson.getString(status, "status"));
                        String code = TmsJson.getString(status, "statusCode");
                        String label = firstNonEmpty(name, code);
                        if (label == null) {
                            continue;
                        }
                        int statusCount = extractGroupValue(status);
                        nestedSum += statusCount;
                        putAnalyticsHit(analyticsHits, new AnalyticsStatusHit(label, code, name, statusType, statusCount));
                    }
                    if (count == 0) {
                        count = nestedSum;
                    }
                }
                // Do not emit statusType group aggregates as status lines.
                byType.put(statusType, count);
                total += count;
            }
        }

        // Primary per-status counts from official analytics fields.
        ingestStatusCodeHits(analytics, analyticsHits);
        ingestStatusNameHits(analytics, analyticsHits);

        Set<String> seenCodes = new LinkedHashSet<>();
        int blockedFromCode = sumMatchingFromArray(analytics, "countGroupByStatusCode", true, false, seenCodes);
        int skippedFromCode = sumMatchingFromArray(analytics, "countGroupByStatusCode", false, true, seenCodes);

        if (blockedFromCode == 0) {
            blockedFromCode = sumMatchingFromArray(analytics, "countGroupByStatus", true, false, seenCodes);
        }
        if (skippedFromCode == 0) {
            skippedFromCode = sumMatchingFromArray(analytics, "countGroupByStatus", false, true, seenCodes);
        }

        if (blockedFromCode == 0 || skippedFromCode == 0) {
            int[] nested = sumMatchingFromNestedStatuses(analytics, seenCodes);
            if (blockedFromCode == 0) {
                blockedFromCode = nested[0];
            }
            if (skippedFromCode == 0) {
                skippedFromCode = nested[1];
            }
        }

        if (!seenCodes.isEmpty()) {
            log.debug("TMS analytics status codes: {}", seenCodes);
        }

        List<TmsTestPlanStatusCountDto> statusLines = mergeStatusCatalog(statusCatalog, analyticsHits);

        final Map<String, Integer> priorityByKey = buildPriorityIndex(statusCatalog);
        statusLines.sort(Comparator
                .comparingInt((TmsTestPlanStatusCountDto s) -> statusTypeOrderIndex(s.statusType))
                .thenComparingInt(s -> priorityOrMax(priorityByKey, s))
                .thenComparing(s -> s.label != null ? s.label : "", String.CASE_INSENSITIVE_ORDER));

        counts.statuses = statusLines;
        counts.total = total;
        counts.waiting = byType.getOrDefault("Pending", 0);
        counts.inProgress = byType.getOrDefault("InProgress", 0);
        counts.passed = byType.getOrDefault("Succeeded", 0);
        counts.failed = byType.getOrDefault("Failed", 0);
        counts.blocked = blockedFromCode;

        int incomplete = byType.getOrDefault("Incomplete", 0);
        if (skippedFromCode > 0) {
            counts.skipped = skippedFromCode;
        } else {
            counts.skipped = Math.max(0, incomplete - blockedFromCode);
        }
        return counts;
    }

    private static List<TmsTestPlanStatusCountDto> mergeStatusCatalog(
            List<TmsTestStatusDto> statusCatalog,
            Map<String, AnalyticsStatusHit> analyticsHits) {
        List<TmsTestPlanStatusCountDto> statusLines = new ArrayList<>();
        Set<String> consumedKeys = new LinkedHashSet<>();
        boolean hasCatalog = false;

        if (statusCatalog != null) {
            for (TmsTestStatusDto catalogStatus : statusCatalog) {
                if (catalogStatus == null) {
                    continue;
                }
                AnalyticsStatusHit hit = findAnalyticsHit(analyticsHits, catalogStatus.code, catalogStatus.name);
                if (hit != null) {
                    consumedKeys.add(hit.key);
                }
                String label = firstNonEmpty(catalogStatus.name, catalogStatus.code);
                if (label == null) {
                    continue;
                }
                hasCatalog = true;
                String type = catalogStatus.type != null ? catalogStatus.type
                        : (hit != null ? hit.statusType : null);
                int count = hit != null ? hit.count : 0;
                statusLines.add(new TmsTestPlanStatusCountDto(label, type, count));
            }
        }

        // With a workflow catalog, only catalog rows are shown (no analytics group orphans).
        if (hasCatalog) {
            return statusLines;
        }

        for (AnalyticsStatusHit hit : analyticsHits.values()) {
            if (consumedKeys.contains(hit.key)) {
                continue;
            }
            if ((hit.code == null || hit.code.isEmpty()) && (hit.name == null || hit.name.isEmpty())) {
                continue;
            }
            if (isAnalyticsGroupHit(hit)) {
                continue;
            }
            statusLines.add(new TmsTestPlanStatusCountDto(hit.label, hit.statusType, hit.count));
        }
        return statusLines;
    }

    private static Map<String, Integer> buildPriorityIndex(List<TmsTestStatusDto> statusCatalog) {
        Map<String, Integer> priorities = new HashMap<>();
        if (statusCatalog == null) {
            return priorities;
        }
        for (TmsTestStatusDto status : statusCatalog) {
            if (status == null || status.priority == null) {
                continue;
            }
            if (status.code != null && !status.code.isEmpty()) {
                priorities.put(status.code.toLowerCase(Locale.ROOT), status.priority);
            }
            if (status.name != null && !status.name.isEmpty()) {
                priorities.put(status.name.toLowerCase(Locale.ROOT), status.priority);
            }
        }
        return priorities;
    }

    private static int priorityOrMax(Map<String, Integer> priorityByKey, TmsTestPlanStatusCountDto status) {
        if (priorityByKey == null || status == null || status.label == null) {
            return Integer.MAX_VALUE;
        }
        Integer priority = priorityByKey.get(status.label.toLowerCase(Locale.ROOT));
        return priority != null ? priority : Integer.MAX_VALUE;
    }

    private static void ingestStatusCodeHits(JsonObject analytics, Map<String, AnalyticsStatusHit> hits) {
        if (analytics == null || !analytics.has("countGroupByStatusCode")
                || !analytics.get("countGroupByStatusCode").isJsonArray()) {
            return;
        }
        for (JsonElement element : analytics.getAsJsonArray("countGroupByStatusCode")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject group = element.getAsJsonObject();
            String code = TmsJson.getString(group, "statusCode");
            if (code == null || code.isEmpty()) {
                continue;
            }
            int value = extractGroupValue(group);
            mergeAnalyticsHit(hits, new AnalyticsStatusHit(code, code, null, null, value));
        }
    }

    private static void ingestStatusNameHits(JsonObject analytics, Map<String, AnalyticsStatusHit> hits) {
        if (analytics == null || !analytics.has("countGroupByStatus")
                || !analytics.get("countGroupByStatus").isJsonArray()) {
            return;
        }
        for (JsonElement element : analytics.getAsJsonArray("countGroupByStatus")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject group = element.getAsJsonObject();
            String status = TmsJson.getString(group, "status");
            if (status == null || status.isEmpty()) {
                continue;
            }
            // Type/outcome group aggregates (Failed, PASSED, NORESULTS, …) are not workflow statuses.
            if (isAnalyticsGroupKey(status)) {
                continue;
            }
            int value = extractGroupValue(group);
            mergeAnalyticsHit(hits, new AnalyticsStatusHit(status, null, status, null, value));
        }
    }

    private static void putAnalyticsHit(Map<String, AnalyticsStatusHit> hits, AnalyticsStatusHit hit) {
        mergeAnalyticsHit(hits, hit);
    }

    /**
     * Merge hit into map: match existing by code/name; fill zero counts; do not double-add.
     */
    private static void mergeAnalyticsHit(Map<String, AnalyticsStatusHit> hits, AnalyticsStatusHit hit) {
        if (hit == null || hit.key == null) {
            return;
        }
        AnalyticsStatusHit existing = findAnalyticsHit(hits, hit.code, hit.name);
        if (existing == null && hit.label != null) {
            existing = findAnalyticsHit(hits, hit.label, hit.label);
        }
        if (existing == null) {
            hits.put(hit.key, hit);
            return;
        }
        if (existing.count == 0 && hit.count > 0) {
            existing.count = hit.count;
        }
    }

    private static AnalyticsStatusHit findAnalyticsHit(
            Map<String, AnalyticsStatusHit> hits,
            String code,
            String name) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        if (code != null && !code.isEmpty()) {
            for (AnalyticsStatusHit hit : hits.values()) {
                if (equalsIgnoreCase(code, hit.code)) {
                    return hit;
                }
            }
        }
        if (name != null && !name.isEmpty()) {
            for (AnalyticsStatusHit hit : hits.values()) {
                if (isAnalyticsGroupHit(hit) || isStatusTypeAggregateHit(hit)) {
                    continue;
                }
                if (equalsIgnoreCase(name, hit.name) || equalsIgnoreCase(name, hit.label)) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** True for analytics statusType keys (Pending/InProgress/Succeeded/Failed/Incomplete). */
    private static boolean isStatusTypeKey(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (String type : STATUS_TYPE_ORDER) {
            if (type.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /** True for statusType keys and analytics outcome groups (PASSED, NORESULTS). */
    private static boolean isAnalyticsGroupKey(String value) {
        return isStatusTypeKey(value) || isOutcomeGroupKey(value);
    }

    private static boolean isOutcomeGroupKey(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (String key : ANALYTICS_OUTCOME_GROUPS) {
            if (key.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hit with no status code whose name/label is a statusType — type aggregate, not a workflow status.
     */
    private static boolean isStatusTypeAggregateHit(AnalyticsStatusHit hit) {
        if (hit == null || (hit.code != null && !hit.code.isEmpty())) {
            return false;
        }
        return isStatusTypeKey(hit.name) || isStatusTypeKey(hit.label);
    }

    /**
     * Orphan hit that is an analytics group (PASSED/NORESULTS) or a statusType aggregate, not a workflow status.
     * Does not treat real status codes like {@code failed} as groups.
     */
    private static boolean isAnalyticsGroupHit(AnalyticsStatusHit hit) {
        if (hit == null) {
            return false;
        }
        if (isOutcomeGroupKey(hit.code) || isOutcomeGroupKey(hit.name) || isOutcomeGroupKey(hit.label)) {
            return true;
        }
        return isStatusTypeAggregateHit(hit);
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static final class AnalyticsStatusHit {
        final String key;
        final String label;
        final String code;
        final String name;
        final String statusType;
        int count;

        AnalyticsStatusHit(String label, String code, String name, String statusType, int count) {
            this.label = label;
            this.code = code;
            this.name = name;
            this.statusType = statusType;
            this.count = count;
            String keyBase = firstNonEmpty(code, name, label);
            this.key = keyBase != null ? keyBase.toLowerCase(Locale.ROOT) : ("@" + System.identityHashCode(this));
        }
    }

    private static int statusTypeOrderIndex(String statusType) {
        if (statusType == null) {
            return STATUS_TYPE_ORDER.size();
        }
        int index = STATUS_TYPE_ORDER.indexOf(statusType);
        return index >= 0 ? index : STATUS_TYPE_ORDER.size();
    }

    /**
     * @return int[2] = { blocked, skipped }
     */
    private static int[] sumMatchingFromNestedStatuses(JsonObject analytics, Set<String> seenCodes) {
        int blocked = 0;
        int skipped = 0;
        if (analytics == null || !analytics.has("countGroupByStatusType")
                || !analytics.get("countGroupByStatusType").isJsonArray()) {
            return new int[]{0, 0};
        }
        for (JsonElement element : analytics.getAsJsonArray("countGroupByStatusType")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject group = element.getAsJsonObject();
            if (!group.has("statuses") || !group.get("statuses").isJsonArray()) {
                continue;
            }
            for (JsonElement statusElement : group.getAsJsonArray("statuses")) {
                if (!statusElement.isJsonObject()) {
                    continue;
                }
                JsonObject status = statusElement.getAsJsonObject();
                String label = firstNonEmpty(
                        TmsJson.getString(status, "statusCode"),
                        TmsJson.getString(status, "status"),
                        TmsJson.getString(status, "statusName"));
                if (label != null) {
                    seenCodes.add(label);
                }
                int value = extractGroupValue(status);
                if (isBlockedLabel(label)) {
                    blocked += value;
                } else if (isSkippedLabel(label)) {
                    skipped += value;
                }
            }
        }
        return new int[]{blocked, skipped};
    }

    private static int sumMatchingFromArray(
            JsonObject analytics,
            String arrayField,
            boolean matchBlocked,
            boolean matchSkipped,
            Set<String> seenCodes) {
        if (analytics == null || !analytics.has(arrayField) || !analytics.get(arrayField).isJsonArray()) {
            return 0;
        }
        int sum = 0;
        for (JsonElement element : analytics.getAsJsonArray(arrayField)) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject group = element.getAsJsonObject();
            String label = firstNonEmpty(
                    TmsJson.getString(group, "statusCode"),
                    TmsJson.getString(group, "status"),
                    TmsJson.getString(group, "statusName"));
            if (label != null) {
                seenCodes.add(label);
            }
            int value = extractGroupValue(group);
            if (matchBlocked && isBlockedLabel(label)) {
                sum += value;
            } else if (matchSkipped && isSkippedLabel(label)) {
                sum += value;
            }
        }
        return sum;
    }

    private static int extractGroupValue(JsonObject group) {
        Long value = TmsJson.getLong(group, "value");
        if (value != null) {
            return value.intValue();
        }
        Long count = TmsJson.getLong(group, "count");
        return count != null ? count.intValue() : 0;
    }

    private static boolean isBlockedLabel(String label) {
        if (label == null || label.isEmpty()) {
            return false;
        }
        String lower = label.toLowerCase(Locale.ROOT);
        return "blocked".equals(lower) || lower.contains("blocked") || lower.contains("заблок");
    }

    private static boolean isSkippedLabel(String label) {
        if (label == null || label.isEmpty()) {
            return false;
        }
        String lower = label.toLowerCase(Locale.ROOT);
        return "skipped".equals(lower) || lower.contains("skipped") || lower.contains("пропущ");
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
