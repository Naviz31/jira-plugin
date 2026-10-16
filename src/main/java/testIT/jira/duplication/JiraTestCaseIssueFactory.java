package testIT.jira.duplication;

import org.springframework.stereotype.Component;

import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.entity.property.EntityProperty;
import com.atlassian.jira.entity.property.JsonEntityPropertyManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueInputParameters;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserUtil;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import testIT.jira.testcase.TestCaseIssueType;

@Component
public class JiraTestCaseIssueFactory {
    private static final Logger log = LoggerFactory.getLogger(JiraTestCaseIssueFactory.class);
    private static final Gson GSON = new Gson();

    static final String DESC_HASH_PROPERTY_KEY = "testit.duplication.descHash";
    static final String ENTITY_NAME = "IssueProperty";
    static final String HASH_JSON_FIELD = "hash";

    private final JsonEntityPropertyManager propertyManager;

    @Inject
    public JiraTestCaseIssueFactory(@ComponentImport JsonEntityPropertyManager propertyManager) {
        this.propertyManager = propertyManager;
    }

    public String createTestCaseIssue(String jiraProjectKey, String summary) {
        return createTestCaseIssue(jiraProjectKey, summary, null);
    }

    public String createTestCaseIssue(String jiraProjectKey, String summary, String description) {
        ProjectManager projectManager = ComponentAccessor.getProjectManager();
        Project project = projectManager.getProjectByCurrentKey(jiraProjectKey);
        if (project == null) {
            throw new IllegalArgumentException("Jira project not found: " + jiraProjectKey);
        }
        IssueType testCaseType = TestCaseIssueType.getTestCaseIssueType();
        if (testCaseType == null) {
            throw new IllegalStateException("TestCase issue type is not available");
        }
        ApplicationUser actor = resolveActor();
        if (actor == null) {
            throw new IllegalStateException("No Jira administrator available to create issues");
        }

        IssueService issueService = ComponentAccessor.getIssueService();
        IssueInputParameters params = issueService.newIssueInputParameters();
        params.setProjectId(project.getId());
        params.setIssueTypeId(testCaseType.getId());
        params.setSummary(summary != null && !summary.trim().isEmpty() ? summary.trim() : "Test IT work item");
        String desired = description != null ? normalizeDescription(description) : null;
        if (desired != null) {
            params.setDescription(desired);
        }
        params.setReporterId(actor.getName());
        params.setSkipScreenCheck(true);

        JiraAuthenticationContext authContext = ComponentAccessor.getJiraAuthenticationContext();
        ApplicationUser previous = authContext.getLoggedInUser();
        try {
            authContext.setLoggedInUser(actor);
            IssueService.CreateValidationResult validation = issueService.validateCreate(actor, params);
            if (!validation.isValid()) {
                throw new IllegalStateException("Issue create validation failed: " + validation.getErrorCollection());
            }
            IssueService.IssueResult created = issueService.create(actor, validation);
            if (!created.isValid() || created.getIssue() == null) {
                throw new IllegalStateException("Issue create failed: " + created.getErrorCollection());
            }
            Issue issue = created.getIssue();
            if (desired != null) {
                saveDescriptionHash(issue, sha256Hex(desired));
            }
            return issue.getKey();
        } finally {
            authContext.setLoggedInUser(previous);
        }
    }

    /**
     * @return true if description was written
     */
    public boolean updateDescriptionIfChanged(String issueKey, String description) {
        if (issueKey == null || issueKey.trim().isEmpty()) {
            throw new IllegalArgumentException("issueKey is required");
        }
        String desired = normalizeDescription(description);
        String desiredHash = sha256Hex(desired);
        ApplicationUser actor = resolveActor();
        if (actor == null) {
            throw new IllegalStateException("No Jira administrator available to update issues");
        }

        IssueService issueService = ComponentAccessor.getIssueService();
        JiraAuthenticationContext authContext = ComponentAccessor.getJiraAuthenticationContext();
        ApplicationUser previous = authContext.getLoggedInUser();
        try {
            authContext.setLoggedInUser(actor);
            IssueService.IssueResult issueResult = issueService.getIssue(actor, issueKey.trim());
            if (!issueResult.isValid() || issueResult.getIssue() == null) {
                throw new IllegalArgumentException("Issue not found: " + issueKey);
            }
            Issue issue = issueResult.getIssue();
            String storedHash = readDescriptionHash(issue);
            if (desiredHash.equals(storedHash)) {
                return false;
            }

            IssueInputParameters params = issueService.newIssueInputParameters();
            params.setDescription(desired);
            params.setSkipScreenCheck(true);
            IssueService.UpdateValidationResult validation = issueService.validateUpdate(actor, issue.getId(), params);
            if (!validation.isValid()) {
                throw new IllegalStateException("Issue update validation failed: " + validation.getErrorCollection());
            }
            IssueService.IssueResult updated = issueService.update(actor, validation);
            if (!updated.isValid()) {
                throw new IllegalStateException("Issue update failed: " + updated.getErrorCollection());
            }
            saveDescriptionHash(issue, desiredHash);
            return true;
        } finally {
            authContext.setLoggedInUser(previous);
        }
    }

    static String normalizeDescription(String description) {
        if (description == null) {
            return "";
        }
        return description.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private JsonEntityPropertyManager resolvePropertyManager() {
        if (propertyManager != null) {
            return propertyManager;
        }
        try {
            return ComponentAccessor.getComponent(JsonEntityPropertyManager.class);
        } catch (RuntimeException e) {
            log.error("JsonEntityPropertyManager unavailable via ComponentAccessor: {}", e.getMessage());
            return null;
        }
    }

    private String readDescriptionHash(Issue issue) {
        if (issue == null || issue.getId() == null) {
            return null;
        }
        try {
            JsonEntityPropertyManager manager = resolvePropertyManager();
            if (manager == null) {
                return null;
            }
            EntityProperty property = manager.get(ENTITY_NAME, issue.getId(), DESC_HASH_PROPERTY_KEY);
            if (property == null || property.getValue() == null) {
                return null;
            }
            return parseStoredHash(property.getValue());
        } catch (RuntimeException e) {
            log.warn("Failed to read {} for issue {}: {}", DESC_HASH_PROPERTY_KEY, issue.getKey(), e.getMessage());
            return null;
        }
    }

    /**
     * Accepts {@code {"hash":"..."}}, a JSON string {@code "..."}, or legacy raw hex.
     */
    static String parseStoredHash(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("{") || value.startsWith("\"")) {
            try {
                JsonElement element = JsonParser.parseString(value);
                if (element.isJsonObject()) {
                    JsonObject object = element.getAsJsonObject();
                    if (object.has(HASH_JSON_FIELD) && object.get(HASH_JSON_FIELD).isJsonPrimitive()) {
                        String hash = object.get(HASH_JSON_FIELD).getAsString();
                        return hash != null && !hash.trim().isEmpty() ? hash.trim() : null;
                    }
                } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    String hash = element.getAsString();
                    return hash != null && !hash.trim().isEmpty() ? hash.trim() : null;
                }
            } catch (RuntimeException e) {
                log.debug("Failed to parse descHash JSON, treating as raw: {}", e.getMessage());
            }
        }
        return value;
    }

    private void saveDescriptionHash(Issue issue, String hash) {
        if (issue == null || issue.getId() == null || hash == null || hash.isEmpty()) {
            return;
        }
        try {
            JsonEntityPropertyManager manager = resolvePropertyManager();
            if (manager == null) {
                log.error("JsonEntityPropertyManager unavailable; cannot save {} for issue {}",
                        DESC_HASH_PROPERTY_KEY, issue.getKey());
                return;
            }
            JsonObject root = new JsonObject();
            root.addProperty(HASH_JSON_FIELD, hash);
            manager.put(ENTITY_NAME, issue.getId(), DESC_HASH_PROPERTY_KEY, GSON.toJson(root));
        } catch (RuntimeException e) {
            log.error("Failed to save {} for issue {}: {}", DESC_HASH_PROPERTY_KEY, issue.getKey(), e.getMessage(), e);
        }
    }

    private static ApplicationUser resolveActor() {
        try {
            UserUtil userUtil = ComponentAccessor.getUserUtil();
            Collection<ApplicationUser> admins = userUtil.getJiraSystemAdministrators();
            if (admins != null) {
                for (ApplicationUser admin : admins) {
                    if (admin != null && admin.isActive()) {
                        return admin;
                    }
                }
            }
            Collection<ApplicationUser> jiraAdmins = userUtil.getJiraAdministrators();
            if (jiraAdmins != null) {
                for (ApplicationUser admin : jiraAdmins) {
                    if (admin != null && admin.isActive()) {
                        return admin;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve admin actor for duplication", e);
        }
        return null;
    }

    public static String buildIssueBrowseUrl(String issueKey) {
        String jiraBaseUrl = ComponentAccessor.getApplicationProperties().getString("jira.baseurl");
        if (jiraBaseUrl == null) {
            jiraBaseUrl = "";
        }
        while (jiraBaseUrl.endsWith("/")) {
            jiraBaseUrl = jiraBaseUrl.substring(0, jiraBaseUrl.length() - 1);
        }
        return jiraBaseUrl + "/browse/" + issueKey;
    }
}
