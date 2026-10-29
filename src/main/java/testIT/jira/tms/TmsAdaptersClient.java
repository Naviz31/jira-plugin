package testIT.jira.tms;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import org.apache.http.client.utils.URIBuilder;

public class TmsAdaptersClient {
    private static final Gson GSON = new Gson();
    private static final String PROJECTS_PATH = "/adapters/projects/";
    private static final String WORKITEMS_PATH = "/adapters/workItems";
    private static final String WORKITEMS_SEARCH_PATH = "/adapters/workItems/search";
    private static final String TEST_RUNS_PATH = "/adapters/testRuns/";
    private static final String WORKITEM_HISTORY_PATH = "/api/v2/workItems/";
    private static final String TEST_PLANS_PATH = "/api/v2/testPlans/";
    private static final String PROJECTS_V2_PATH = "/api/v2/projects/";
    private static final String PROJECTS_V2_SEARCH_PATH = "/api/v2/projects/search";
    private static final String WORKFLOWS_PATH = "/api/v2/workflows/";

    public TmsPage<TmsProjectDto> searchProjects(
            String baseUrl,
            String token,
            int skip,
            int take,
            String name,
            String globalId)
            throws TmsClientException, IOException, URISyntaxException {
        URIBuilder uriBuilder = new URIBuilder(TmsHttpClient.normalizeBaseUrl(baseUrl) + PROJECTS_V2_SEARCH_PATH)
                .setParameter("Skip", String.valueOf(Math.max(0, skip)))
                .setParameter("Take", String.valueOf(take > 0 ? take : 20));
        JsonObject body = new JsonObject();
        body.addProperty("isDeleted", false);
        if (name != null && !name.trim().isEmpty()) {
            body.addProperty("name", name.trim());
        }
        if (globalId != null && globalId.trim().matches("\\d+")) {
            JsonArray globalIds = new JsonArray();
            globalIds.add(Long.parseLong(globalId.trim()));
            body.add("globalIds", globalIds);
        }
        TmsHttpClient.HttpResponseData response = TmsHttpClient.executePost(
                uriBuilder.build(), token, GSON.toJson(body), "TMS projects search failed");
        List<TmsProjectDto> items = parseProjects(response.body);
        return page(items, response, skip, take);
    }

    public static final String[] WORK_ITEM_TYPES_TEST_CASES = {"TestCases"};
    public static final String[] WORK_ITEM_TYPES_ALL = {"TestCases", "SharedSteps", "CheckLists"};

    public TmsPage<JsonObject> searchWorkItems(String baseUrl, String token, int skip, int take, String issueKey)
            throws TmsClientException, IOException, URISyntaxException {
        return searchWorkItems(baseUrl, token, skip, take, issueKey, null, WORK_ITEM_TYPES_TEST_CASES);
    }

    public TmsPage<JsonObject> searchWorkItems(
            String baseUrl,
            String token,
            int skip,
            int take,
            String issueKey,
            String projectId)
            throws TmsClientException, IOException, URISyntaxException {
        return searchWorkItems(baseUrl, token, skip, take, issueKey, projectId, WORK_ITEM_TYPES_TEST_CASES);
    }

    public TmsPage<JsonObject> searchWorkItems(
            String baseUrl,
            String token,
            int skip,
            int take,
            String issueKey,
            String projectId,
            String... entityTypes)
            throws TmsClientException, IOException, URISyntaxException {
        URIBuilder uriBuilder = new URIBuilder(TmsHttpClient.normalizeBaseUrl(baseUrl) + WORKITEMS_SEARCH_PATH)
                .setParameter("Skip", String.valueOf(Math.max(0, skip)))
                .setParameter("Take", String.valueOf(take > 0 ? take : 100));
        if (issueKey != null && !issueKey.isEmpty()) {
            uriBuilder.setParameter("SearchField", "name");
            uriBuilder.setParameter("SearchValue", issueKey);
        }
        JsonObject body = new JsonObject();
        JsonObject filter = new JsonObject();
        filter.addProperty("isDeleted", false);
        JsonArray types = new JsonArray();
        String[] resolvedTypes = (entityTypes != null && entityTypes.length > 0)
                ? entityTypes
                : WORK_ITEM_TYPES_TEST_CASES;
        for (String type : resolvedTypes) {
            if (type != null && !type.trim().isEmpty()) {
                types.add(type.trim());
            }
        }
        if (types.size() == 0) {
            types.add("TestCases");
        }
        filter.add("types", types);
        if (projectId != null && !projectId.trim().isEmpty()) {
            JsonArray projectIds = new JsonArray();
            projectIds.add(projectId.trim());
            filter.add("projectIds", projectIds);
        }
        body.add("filter", filter);
        TmsHttpClient.HttpResponseData response = TmsHttpClient.executePost(
                uriBuilder.build(), token, GSON.toJson(body), "TMS work items search failed");
        List<JsonObject> items = TmsJson.parseObjectArray(response.body);
        return page(items, response, skip, take);
    }

    public WorkItemHistoryResult getWorkItemTestResultsHistory(String baseUrl, String token, Long workItemGlobalId,
            String workItemUuid, int skip, int take) throws TmsClientException, IOException, URISyntaxException {
        String historyId = buildWorkItemHistoryId(workItemGlobalId, workItemUuid);
        if (historyId == null) {
            return WorkItemHistoryResult.empty();
        }
        int lastStatusCode = 0;
        int lastBodyLength = 0;
        HistoryQueryOptions[] attempts = {
                new HistoryQueryOptions(false, null, PaginationStyle.BARE),
                new HistoryQueryOptions(false, null, PaginationStyle.MINIMAL),
                new HistoryQueryOptions(true, "completedOn|DESC", PaginationStyle.PASCAL),
                new HistoryQueryOptions(false, "completedOn|DESC", PaginationStyle.PASCAL),
                new HistoryQueryOptions(true, "completedOn|DESC", PaginationStyle.CAMEL),
                new HistoryQueryOptions(false, "completedOn|DESC", PaginationStyle.CAMEL),
                new HistoryQueryOptions(true, null, PaginationStyle.MINIMAL),
        };
        for (HistoryQueryOptions options : attempts) {
            HistoryAttempt attempt = fetchHistoryAttempt(baseUrl, token, historyId, skip, take, options);
            lastStatusCode = attempt.statusCode;
            lastBodyLength = attempt.bodyLength;
            if (attempt.error != null && !isRetriableHistoryStatus(attempt.statusCode)) {
                throw attempt.error;
            }
            if (!attempt.items.isEmpty()) {
                return new WorkItemHistoryResult(attempt.items, options.manualFilteredByApi, attempt.statusCode, attempt.bodyLength);
            }
        }
        return new WorkItemHistoryResult(new ArrayList<>(), false, lastStatusCode, lastBodyLength);
    }

    public JsonObject getTestRun(String baseUrl, String token, String testRunId)
            throws TmsClientException, IOException {
        if (testRunId == null || testRunId.trim().isEmpty()) {
            return new JsonObject();
        }
        URI uri = URI.create(TmsHttpClient.normalizeBaseUrl(baseUrl) + TEST_RUNS_PATH + testRunId.trim());
        TmsHttpClient.HttpResponseData response = TmsHttpClient.executeGet(uri, token, "TMS get test run failed");
        return TmsJson.parseObject(response.body);
    }

    public Long getProjectGlobalId(String baseUrl, String token, String projectUuid)
            throws TmsClientException, IOException, URISyntaxException {
        if (projectUuid == null || projectUuid.trim().isEmpty()) {
            return null;
        }
        URI uri = URI.create(TmsHttpClient.normalizeBaseUrl(baseUrl) + PROJECTS_PATH + projectUuid.trim());
        TmsHttpClient.HttpResponseData response = TmsHttpClient.executeGet(uri, token, "TMS get project failed");
        return TmsJson.getLong(TmsJson.parseObject(response.body), "globalId");
    }

    public JsonObject createWorkItem(String baseUrl, String token, JsonObject payload)
            throws TmsClientException, IOException {
        URI uri = URI.create(TmsHttpClient.normalizeBaseUrl(baseUrl) + WORKITEMS_PATH);
        TmsHttpClient.HttpResponseData response = TmsHttpClient.executePost(
                uri, token, GSON.toJson(payload), "TMS create work item failed");
        return TmsJson.parseObject(response.body);
    }

    public JsonObject getWorkItem(String baseUrl, String token, String workItemId)
            throws TmsClientException, IOException {
        if (workItemId == null || workItemId.trim().isEmpty()) {
            throw new IllegalArgumentException("workItemId is required");
        }
        URI uri = URI.create(TmsHttpClient.normalizeBaseUrl(baseUrl) + WORKITEM_HISTORY_PATH + workItemId.trim());
        TmsHttpClient.HttpResponseData response = TmsHttpClient.executeGet(uri, token, "TMS get work item failed");
        return TmsJson.parseObject(response.body);
    }

    public void updateWorkItem(String baseUrl, String token, JsonObject payload)
            throws TmsClientException, IOException {
        URI uri = URI.create(TmsHttpClient.normalizeBaseUrl(baseUrl) + "/api/v2/workItems");
        TmsHttpClient.executePut(uri, token, GSON.toJson(payload), "TMS update work item failed");
    }

    public JsonObject getTestPlan(String baseUrl, String token, String testPlanId)
            throws TmsClientException, IOException {
        if (testPlanId == null || testPlanId.trim().isEmpty()) {
            return new JsonObject();
        }
        URI uri = URI.create(TmsHttpClient.normalizeBaseUrl(baseUrl) + TEST_PLANS_PATH + testPlanId.trim());
        TmsHttpClient.HttpResponseData response = TmsHttpClient.executeGet(uri, token, "TMS get test plan failed");
        return TmsJson.parseObject(response.body);
    }

    public JsonObject getTestPlanAnalytics(String baseUrl, String token, String testPlanId)
            throws TmsClientException, IOException {
        if (testPlanId == null || testPlanId.trim().isEmpty()) {
            return new JsonObject();
        }
        URI uri = URI.create(
                TmsHttpClient.normalizeBaseUrl(baseUrl) + TEST_PLANS_PATH + testPlanId.trim() + "/analytics");
        TmsHttpClient.HttpResponseData response = TmsHttpClient.executeGet(uri, token, "TMS get test plan analytics failed");
        return TmsJson.parseObject(response.body);
    }

    /**
     * Statuses from the project's workflow. Fail-soft: empty list on error.
     */
    public List<JsonObject> getTestPlanAttributeDefinitions(String baseUrl, String token, String projectId)
            throws TmsClientException, IOException {
        if (projectId == null || projectId.trim().isEmpty()) {
            return new ArrayList<>();
        }
        URI uri = URI.create(TmsHttpClient.normalizeBaseUrl(baseUrl)
                + "/api/v2/projects/" + projectId.trim() + "/testPlans/attributes");
        TmsHttpClient.HttpResponseData response =
                TmsHttpClient.executeGet(uri, token, "TMS get test plan attributes failed");
        return TmsJson.parseObjectArray(response.body);
    }

    private static final int TEST_POINTS_PAGE_SIZE = 1000;
    private static final int TEST_POINTS_MAX_PAGES = 5;
    /** The most test points of one plan that are ever read. */
    static final int TEST_POINTS_LIMIT = TEST_POINTS_PAGE_SIZE * TEST_POINTS_MAX_PAGES;

    /** Test points of a plan (by plan UUID), at most {@code PAGE_SIZE * MAX_PAGES} of them. */
    public List<JsonObject> getTestPlanPoints(String baseUrl, String token, String testPlanId)
            throws TmsClientException, IOException {
        List<JsonObject> points = new ArrayList<>();
        if (testPlanId == null || testPlanId.trim().isEmpty()) {
            return points;
        }
        JsonObject body = new JsonObject();
        JsonArray planIds = new JsonArray();
        planIds.add(testPlanId.trim());
        body.add("testPlanIds", planIds);
        for (int page = 0; page < TEST_POINTS_MAX_PAGES; page++) {
            int skip = page * TEST_POINTS_PAGE_SIZE;
            URI uri = URI.create(TmsHttpClient.normalizeBaseUrl(baseUrl)
                    + "/api/v2/testPoints/search?skip=" + skip + "&take=" + TEST_POINTS_PAGE_SIZE);
            TmsHttpClient.HttpResponseData response = TmsHttpClient.executePost(
                    uri, token, GSON.toJson(body), "TMS get test plan points failed");
            List<JsonObject> items = TmsJson.parseObjectArray(response.body);
            points.addAll(items);
            // totalItems is 0 when the server sends no pagination header: then only a short page ends the loop.
            boolean lastPage = items.size() < TEST_POINTS_PAGE_SIZE
                    || (response.totalItems > 0 && points.size() >= response.totalItems);
            if (lastPage) {
                break;
            }
        }
        return points;
    }

    public JsonObject getConfiguration(String baseUrl, String token, String configurationId)
            throws TmsClientException, IOException {
        if (configurationId == null || configurationId.trim().isEmpty()) {
            return new JsonObject();
        }
        URI uri = URI.create(TmsHttpClient.normalizeBaseUrl(baseUrl)
                + "/api/v2/configurations/" + configurationId.trim());
        TmsHttpClient.HttpResponseData response =
                TmsHttpClient.executeGet(uri, token, "TMS get configuration failed");
        return TmsJson.parseObject(response.body);
    }

    public JsonObject getUser(String baseUrl, String token, String userId)
            throws TmsClientException, IOException {
        if (userId == null || userId.trim().isEmpty()) {
            return new JsonObject();
        }
        URI uri = URI.create(TmsHttpClient.normalizeBaseUrl(baseUrl) + "/api/v2/users/" + userId.trim());
        TmsHttpClient.HttpResponseData response = TmsHttpClient.executeGet(uri, token, "TMS get user failed");
        return TmsJson.parseObject(response.body);
    }

    public List<TmsTestStatusDto> getWorkflowStatusesForProject(String baseUrl, String token, String projectId) {
        if (projectId == null || projectId.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            JsonObject project = getProject(baseUrl, token, projectId.trim());
            String workflowId = TmsJson.getString(project, "workflowId");
            if (workflowId == null || workflowId.isEmpty()) {
                return new ArrayList<>();
            }
            return getWorkflowStatuses(baseUrl, token, workflowId);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public JsonObject getProject(String baseUrl, String token, String projectId)
            throws TmsClientException, IOException {
        if (projectId == null || projectId.trim().isEmpty()) {
            return new JsonObject();
        }
        URI uri = URI.create(TmsHttpClient.normalizeBaseUrl(baseUrl) + PROJECTS_V2_PATH + projectId.trim());
        TmsHttpClient.HttpResponseData response = TmsHttpClient.executeGet(uri, token, "TMS get project failed");
        return TmsJson.parseObject(response.body);
    }

    public List<TmsTestStatusDto> getWorkflowStatuses(String baseUrl, String token, String workflowId) {
        if (workflowId == null || workflowId.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            URI uri = URI.create(TmsHttpClient.normalizeBaseUrl(baseUrl) + WORKFLOWS_PATH + workflowId.trim());
            TmsHttpClient.HttpResponseData response = TmsHttpClient.executeGet(uri, token, "TMS get workflow failed");
            JsonObject workflow = TmsJson.parseObject(response.body);
            List<TmsTestStatusDto> statuses = new ArrayList<>();
            if (!workflow.has("statuses") || !workflow.get("statuses").isJsonArray()) {
                return statuses;
            }
            for (com.google.gson.JsonElement element : workflow.getAsJsonArray("statuses")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String name = TmsJson.getString(object, "name");
                String code = TmsJson.getString(object, "code");
                String type = TmsJson.getString(object, "type");
                if ((name == null || name.isEmpty()) && (code == null || code.isEmpty())) {
                    continue;
                }
                Integer priority = null;
                Long priorityLong = TmsJson.getLong(object, "priority");
                if (priorityLong != null) {
                    priority = priorityLong.intValue();
                }
                statuses.add(new TmsTestStatusDto(
                        name != null ? name : code,
                        code,
                        type,
                        priority));
            }
            return statuses;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private HistoryAttempt fetchHistoryAttempt(String baseUrl, String token, String workItemId, int skip, int take,
            HistoryQueryOptions options) throws IOException, URISyntaxException {
        URI uri = buildHistoryUri(baseUrl, workItemId, skip, take, options);
        TmsHttpClient.HttpResponseData response = TmsHttpClient.executeGetWithoutError(uri, token);
        if (response.statusCode >= 200 && response.statusCode < 300) {
            return new HistoryAttempt(response.statusCode, response.body, TmsJson.parseHistoryResponse(response.body), null);
        }
        return new HistoryAttempt(
                response.statusCode,
                response.body,
                new ArrayList<>(),
                new TmsClientException(response.statusCode,
                        TmsHttpClient.truncate("TMS work item test results history failed: " + response.body)));
    }

    private URI buildHistoryUri(String baseUrl, String workItemId, int skip, int take, HistoryQueryOptions options)
            throws URISyntaxException {
        URIBuilder uriBuilder = new URIBuilder(
                TmsHttpClient.normalizeBaseUrl(baseUrl) + WORKITEM_HISTORY_PATH + workItemId + "/testResults/history");
        if (options.paginationStyle == PaginationStyle.BARE) {
            return uriBuilder.build();
        }
        if (options.paginationStyle == PaginationStyle.MINIMAL) {
            if (take > 0) {
                uriBuilder.setParameter("take", String.valueOf(take));
            }
        } else if (options.paginationStyle == PaginationStyle.CAMEL) {
            uriBuilder.setParameter("skip", String.valueOf(Math.max(0, skip)));
            uriBuilder.setParameter("take", String.valueOf(take > 0 ? take : 100));
        } else {
            uriBuilder.setParameter("Skip", String.valueOf(Math.max(0, skip)));
            uriBuilder.setParameter("Take", String.valueOf(take > 0 ? take : 100));
        }
        if (options.manualFilteredByApi) {
            uriBuilder.setParameter("automated", "false");
        }
        if (options.orderBy != null && !options.orderBy.isEmpty()
                && options.paginationStyle != PaginationStyle.MINIMAL) {
            if (options.paginationStyle == PaginationStyle.CAMEL) {
                uriBuilder.setParameter("orderBy", options.orderBy);
            } else {
                uriBuilder.setParameter("OrderBy", options.orderBy);
            }
        }
        return uriBuilder.build();
    }

    private static List<TmsProjectDto> parseProjects(String body) {
        List<TmsProjectDto> items = new ArrayList<>();
        for (JsonObject object : TmsJson.parseObjectArray(body)) {
            String id = TmsJson.getString(object, "id");
            String name = TmsJson.getString(object, "name");
            Long globalId = TmsJson.getLong(object, "globalId");
            if (id != null) {
                items.add(new TmsProjectDto(id, globalId, name != null ? name : ""));
            }
        }
        return items;
    }

    private static <T> TmsPage<T> page(List<T> items, TmsHttpClient.HttpResponseData response, int skip, int take) {
        return new TmsPage<>(
                items,
                response.skip > 0 ? response.skip : skip,
                response.take > 0 ? response.take : take,
                response.totalItems > 0 ? response.totalItems : items.size());
    }

    private static String buildWorkItemHistoryId(Long workItemGlobalId, String workItemUuid) {
        if (workItemUuid != null && !workItemUuid.trim().isEmpty()) {
            return workItemUuid.trim();
        }
        if (workItemGlobalId != null) {
            return String.valueOf(workItemGlobalId);
        }
        return null;
    }

    private static boolean isRetriableHistoryStatus(int status) {
        return status == 403 || status == 404 || status == 422;
    }

    public static final class WorkItemHistoryResult {
        public final List<JsonObject> items;
        public final boolean manualFilteredByApi;
        public final int lastStatusCode;
        public final int lastBodyLength;

        public WorkItemHistoryResult(List<JsonObject> items, boolean manualFilteredByApi,
                int lastStatusCode, int lastBodyLength) {
            this.items = items != null ? items : new ArrayList<>();
            this.manualFilteredByApi = manualFilteredByApi;
            this.lastStatusCode = lastStatusCode;
            this.lastBodyLength = lastBodyLength;
        }

        public static WorkItemHistoryResult empty() {
            return new WorkItemHistoryResult(new ArrayList<>(), false, 0, 0);
        }
    }

    private enum PaginationStyle {
        BARE,
        PASCAL,
        CAMEL,
        MINIMAL
    }

    private static final class HistoryQueryOptions {
        final boolean manualFilteredByApi;
        final String orderBy;
        final PaginationStyle paginationStyle;

        HistoryQueryOptions(boolean manualFilteredByApi, String orderBy, PaginationStyle paginationStyle) {
            this.manualFilteredByApi = manualFilteredByApi;
            this.orderBy = orderBy;
            this.paginationStyle = paginationStyle;
        }
    }

    private static final class HistoryAttempt {
        final int statusCode;
        final int bodyLength;
        final List<JsonObject> items;
        final TmsClientException error;

        HistoryAttempt(int statusCode, String body, List<JsonObject> items, TmsClientException error) {
            this.statusCode = statusCode;
            this.bodyLength = body != null ? body.length() : 0;
            this.items = items != null ? items : new ArrayList<>();
            this.error = error;
        }
    }
}
