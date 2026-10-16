package testIT.jira;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.entity.property.JsonEntityPropertyManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.atlassian.sal.api.user.UserManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import jakarta.inject.Inject;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import testIT.jira.tms.IssueTestPlanLinkStore;
import testIT.jira.tms.TmsAdaptersClient;
import testIT.jira.tms.TmsClientException;
import testIT.jira.tms.TmsIssueContextDto;
import testIT.jira.tms.TmsIssueContextService;
import testIT.jira.tms.TmsIssueTestPlanService;
import testIT.jira.tms.TmsIssueTestPlansDto;
import testIT.jira.tms.TmsPage;
import testIT.jira.tms.TmsProjectDto;
import testIT.jira.tms.TmsTestPlanRowDto;
import testIT.jira.tms.TmsUrlHelper;
import testIT.jira.tms.TmsWorkItemUnlinkService;

@Path(value="/tms")
@Component
public class TmsResource {
    private static final Logger log = LoggerFactory.getLogger(TmsResource.class);
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_TAKE = 20;

    @ComponentImport
    private final UserManager userManager;
    @ComponentImport
    private final PluginSettingsFactory pluginSettingsFactory;
    @ComponentImport
    private final JsonEntityPropertyManager jsonEntityPropertyManager;

    private final TmsAdaptersClient tmsClient = new TmsAdaptersClient();
    private final TmsIssueContextService issueContextService = new TmsIssueContextService();
    private final TmsIssueTestPlanService testPlanService = new TmsIssueTestPlanService();
    private final TmsWorkItemUnlinkService workItemUnlinkService = new TmsWorkItemUnlinkService();

    @Inject
    public TmsResource(
            UserManager userManager,
            PluginSettingsFactory pluginSettingsFactory,
            JsonEntityPropertyManager jsonEntityPropertyManager) {
        this.userManager = userManager;
        this.pluginSettingsFactory = pluginSettingsFactory;
        this.jsonEntityPropertyManager = jsonEntityPropertyManager;
    }

    @GET
    @Path(value="/projects")
    @Produces(value={"application/json"})
    public Response searchProjects(
            @QueryParam("skip") @DefaultValue("0") int skip,
            @QueryParam("take") @DefaultValue("20") int take,
            @QueryParam("q") String q,
            @QueryParam("globalId") String globalId,
            @Context HttpServletRequest request) {
        if (!isLoggedIn(request)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        try {
            TmsSettings settings = requireTmsSettings();
            int safeTake = take > 0 ? Math.min(take, 100) : DEFAULT_TAKE;
            int safeSkip = Math.max(0, skip);
            String safeGlobalId = globalId != null && globalId.trim().matches("\\d+")
                    ? globalId.trim()
                    : null;
            TmsPage<TmsProjectDto> page = tmsClient.searchProjects(
                    settings.url, settings.token, safeSkip, safeTake, q, safeGlobalId);
            Map<String, Object> body = new HashMap<>();
            body.put("items", page.items);
            body.put("skip", page.skip);
            body.put("take", page.take);
            body.put("totalItems", page.totalItems);
            return Response.ok(GSON.toJson(body)).type(MediaType.APPLICATION_JSON).build();
        } catch (IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (TmsClientException e) {
            log.warn("TMS projects search failed: status={}", e.getStatusCode());
            return upstreamError(e);
        } catch (Exception e) {
            log.error("TMS projects search failed", e);
            return serverError(e);
        }
    }

    @GET
    @Path(value="/issues/{issueKey}/context")
    @Produces(value={"application/json"})
    public Response getIssueContext(
            @PathParam("issueKey") String issueKey,
            @Context HttpServletRequest request) {
        if (!isLoggedIn(request)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (issueKey == null || issueKey.trim().isEmpty()) {
            return badRequest("issueKey is required");
        }
        try {
            TmsSettings settings = requireTmsSettings();
            Issue issue = requireIssue(issueKey);
            String issueUrl = buildIssueBrowseUrl(issue.getKey());

            TmsIssueContextDto context = issueContextService.loadIssueContext(
                    settings.url, settings.token, issue.getKey(), issueUrl, issue.getSummary());
            return Response.ok(GSON.toJson(context)).type(MediaType.APPLICATION_JSON).build();
        } catch (IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (TmsClientException e) {
            log.warn("TMS issue context failed: status={}", e.getStatusCode());
            return upstreamError(e);
        } catch (Exception e) {
            log.error("TMS issue context failed", e);
            return serverError(e);
        }
    }

    @DELETE
    @Path(value="/issues/{issueKey}/workitems/{workItemId}")
    @Produces(value={"application/json"})
    public Response unlinkWorkItem(
            @PathParam("issueKey") String issueKey,
            @PathParam("workItemId") String workItemId,
            @Context HttpServletRequest request) {
        if (!isLoggedIn(request)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (issueKey == null || issueKey.trim().isEmpty()) {
            return badRequest("issueKey is required");
        }
        if (workItemId == null || workItemId.trim().isEmpty()) {
            return badRequest("workItemId is required");
        }
        try {
            TmsSettings settings = requireTmsSettings();
            Issue issue = requireIssue(issueKey);
            String issueUrl = buildIssueBrowseUrl(issue.getKey());
            workItemUnlinkService.unlinkWorkItemFromIssue(
                    settings.url, settings.token, workItemId.trim(), issue.getKey(), issueUrl);
            Map<String, Object> result = new HashMap<>();
            result.put("ok", true);
            result.put("issueKey", issue.getKey());
            result.put("workItemId", workItemId.trim());
            return Response.ok(GSON.toJson(result)).type(MediaType.APPLICATION_JSON).build();
        } catch (IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (TmsClientException e) {
            log.warn("TMS unlink work item failed: status={}", e.getStatusCode());
            return upstreamError(e);
        } catch (Exception e) {
            log.error("TMS unlink work item failed", e);
            return serverError(e);
        }
    }

    @POST
    @Path(value="/workitems")
    @Consumes(value={"application/json"})
    @Produces(value={"application/json"})
    public Response createWorkItem(String rawBody, @Context HttpServletRequest request) {
        if (!isLoggedIn(request)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        CreateWorkItemRequest body = null;
        try {
            if (rawBody != null && !rawBody.trim().isEmpty()) {
                body = GSON.fromJson(rawBody, CreateWorkItemRequest.class);
            }
        } catch (Exception e) {
            return badRequest("Invalid JSON body");
        }
        if (body == null || body.issueKey == null || body.issueKey.trim().isEmpty()
                || body.projectId == null || body.projectId.trim().isEmpty()) {
            return badRequest("issueKey and projectId are required");
        }
        try {
            TmsSettings settings = requireTmsSettings();
            Issue issue = requireIssue(body.issueKey);

            String issueKey = issue.getKey();
            String summary = issue.getSummary() != null ? issue.getSummary() : "";
            String name = issueKey + ": " + summary;
            String issueUrl = buildIssueBrowseUrl(issueKey);

            JsonObject payload = new JsonObject();
            payload.addProperty("projectId", body.projectId.trim());
            payload.addProperty("name", name);
            payload.addProperty("entityTypeName", "TestCases");
            payload.addProperty("duration", 60000L);
            payload.addProperty("state", "Ready");
            payload.addProperty("priority", "Medium");

            JsonObject link = new JsonObject();
            link.addProperty("url", issueUrl);
            link.addProperty("type", "Issue");
            link.addProperty("title", name);
            JsonArray links = new JsonArray();
            links.add(link);
            payload.add("links", links);

            JsonObject created = tmsClient.createWorkItem(settings.url, settings.token, payload);

            String projectId = body.projectId.trim();
            Long globalId = null;
            if (created.has("globalId") && !created.get("globalId").isJsonNull()) {
                try {
                    globalId = created.get("globalId").getAsLong();
                } catch (Exception ignored) {
                    globalId = null;
                }
            }
            Long projectGlobalId = tmsClient.getProjectGlobalId(settings.url, settings.token, projectId);
            String url = TmsUrlHelper.workItemUrl(settings.url, projectGlobalId, globalId);

            Map<String, Object> result = new HashMap<>();
            result.put("id", created.has("id") && !created.get("id").isJsonNull()
                    ? created.get("id").getAsString() : null);
            result.put("globalId", globalId);
            result.put("name", created.has("name") && !created.get("name").isJsonNull()
                    ? created.get("name").getAsString() : name);
            result.put("projectId", projectId);
            result.put("url", url);
            return Response.ok(GSON.toJson(result)).type(MediaType.APPLICATION_JSON).build();
        } catch (IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (TmsClientException e) {
            log.warn("TMS create work item failed: status={}", e.getStatusCode());
            return upstreamError(e);
        } catch (Exception e) {
            log.error("TMS create work item failed", e);
            return serverError(e);
        }
    }

    @GET
    @Path(value="/issues/{issueKey}/testplans")
    @Produces(value={"application/json"})
    public Response getIssueTestPlans(
            @PathParam("issueKey") String issueKey,
            @Context HttpServletRequest request) {
        if (!isLoggedIn(request)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (issueKey == null || issueKey.trim().isEmpty()) {
            return badRequest("issueKey is required");
        }
        try {
            TmsSettings settings = requireTmsSettings();
            Issue issue = requireIssue(issueKey);
            TmsIssueTestPlansDto dto = testPlanService.listLinkedTestPlans(
                    issue, settings.url, settings.token, linkStore());
            return Response.ok(GSON.toJson(dto)).type(MediaType.APPLICATION_JSON).build();
        } catch (IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("TMS issue test plans failed", e);
            return serverError(e);
        }
    }

    @POST
    @Path(value="/issues/{issueKey}/testplans")
    @Consumes(value={"application/json"})
    @Produces(value={"application/json"})
    public Response addIssueTestPlan(
            @PathParam("issueKey") String issueKey,
            String rawBody,
            @Context HttpServletRequest request) {
        if (!isLoggedIn(request)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (issueKey == null || issueKey.trim().isEmpty()) {
            return badRequest("issueKey is required");
        }
        AddTestPlanRequest body = null;
        try {
            if (rawBody != null && !rawBody.trim().isEmpty()) {
                body = GSON.fromJson(rawBody, AddTestPlanRequest.class);
            }
        } catch (Exception e) {
            return badRequest("Invalid JSON body");
        }
        if (body == null || body.url == null || body.url.trim().isEmpty()) {
            return badRequest("url is required");
        }
        try {
            TmsSettings settings = requireTmsSettings();
            Issue issue = requireIssue(issueKey);
            TmsTestPlanRowDto row = testPlanService.addTestPlanLink(
                    issue, body.url.trim(), settings.url, settings.token, linkStore());
            return Response.ok(GSON.toJson(row)).type(MediaType.APPLICATION_JSON).build();
        } catch (IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("TMS add test plan link failed", e);
            return serverError(e);
        }
    }

    @DELETE
    @Path(value="/issues/{issueKey}/testplans")
    @Consumes(value={"application/json"})
    @Produces(value={"application/json"})
    public Response removeIssueTestPlan(
            @PathParam("issueKey") String issueKey,
            String rawBody,
            @Context HttpServletRequest request) {
        if (!isLoggedIn(request)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (issueKey == null || issueKey.trim().isEmpty()) {
            return badRequest("issueKey is required");
        }
        AddTestPlanRequest body = null;
        try {
            if (rawBody != null && !rawBody.trim().isEmpty()) {
                body = GSON.fromJson(rawBody, AddTestPlanRequest.class);
            }
        } catch (Exception e) {
            return badRequest("Invalid JSON body");
        }
        if (body == null || body.url == null || body.url.trim().isEmpty()) {
            return badRequest("url is required");
        }
        try {
            TmsSettings settings = requireTmsSettings();
            Issue issue = requireIssue(issueKey);
            boolean removed = testPlanService.removeTestPlanLink(
                    issue, body.url.trim(), settings.url, linkStore());
            if (!removed) {
                return badRequest("Test plan link not found");
            }
            Map<String, Object> result = new HashMap<>();
            result.put("ok", true);
            result.put("issueKey", issue.getKey());
            result.put("url", body.url.trim());
            return Response.ok(GSON.toJson(result)).type(MediaType.APPLICATION_JSON).build();
        } catch (IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Remove test plan link failed", e);
            return serverError(e);
        }
    }

    private IssueTestPlanLinkStore linkStore() {
        return new IssueTestPlanLinkStore(jsonEntityPropertyManager);
    }

    private Issue requireIssue(String issueKey) {
        IssueManager issueManager = ComponentAccessor.getIssueManager();
        Issue issue = issueManager.getIssueObject(issueKey.trim());
        if (issue == null) {
            throw new IllegalArgumentException("Issue not found: " + issueKey);
        }
        return issue;
    }

    private static String buildIssueBrowseUrl(String issueKey) {
        String jiraBaseUrl = ComponentAccessor.getApplicationProperties().getString("jira.baseurl");
        if (jiraBaseUrl == null) {
            jiraBaseUrl = "";
        }
        while (jiraBaseUrl.endsWith("/")) {
            jiraBaseUrl = jiraBaseUrl.substring(0, jiraBaseUrl.length() - 1);
        }
        return jiraBaseUrl + "/browse/" + issueKey;
    }

    private boolean isLoggedIn(HttpServletRequest request) {
        return this.userManager.getRemoteUsername(request) != null;
    }

    private TmsSettings requireTmsSettings() {
        PluginSettings settings = this.pluginSettingsFactory.createGlobalSettings();
        String url = (String) settings.get(ConfigResource.Config.class.getName() + ".url");
        String token = (String) settings.get(ConfigResource.Config.class.getName() + ".privateToken");
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalStateException("Test IT URL is not configured");
        }
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalStateException("Test IT privateToken is not configured");
        }
        return new TmsSettings(url.trim(), token.trim());
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Collections.singletonMap("message", message != null ? message : "Bad request"))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static Response upstreamError(TmsClientException e) {
        int mapped = e.getStatusCode() >= 400 && e.getStatusCode() < 500
                ? e.getStatusCode()
                : Response.Status.BAD_GATEWAY.getStatusCode();
        return Response.status(mapped)
                .entity(Collections.singletonMap("message", e.getMessage() != null ? e.getMessage() : "TMS error"))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static Response serverError(Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
        return Response.serverError()
                .entity(Collections.singletonMap("message", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static final class TmsSettings {
        final String url;
        final String token;

        TmsSettings(String url, String token) {
            this.url = url;
            this.token = token;
        }
    }

    public static final class CreateWorkItemRequest {
        public String issueKey;
        public String projectId;
    }

    public static final class AddTestPlanRequest {
        public String url;
    }
}
