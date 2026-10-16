package testIT.jira.duplication;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.google.gson.JsonObject;
import jakarta.inject.Inject;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import testIT.jira.ConfigResource;
import testIT.jira.tms.TmsAdaptersClient;
import testIT.jira.tms.TmsJson;
import testIT.jira.tms.TmsPage;
import testIT.jira.testcase.TestCaseIssueType;

@Component
public class DuplicationSyncService {
    private static final Logger log = LoggerFactory.getLogger(DuplicationSyncService.class);
    private static final int PAGE_SIZE = 100;

    private final DuplicationConfigStore configStore;
    private final DuplicationStatusStore statusStore;
    private final JiraTestCaseIssueFactory issueFactory;
    private final PluginSettingsFactory pluginSettingsFactory;
    private final TmsAdaptersClient tmsClient = new TmsAdaptersClient();
    private final TmsWorkItemLinkSupport linkSupport = new TmsWorkItemLinkSupport();

    @Inject
    public DuplicationSyncService(
            DuplicationConfigStore configStore,
            DuplicationStatusStore statusStore,
            JiraTestCaseIssueFactory issueFactory,
            @ComponentImport PluginSettingsFactory pluginSettingsFactory) {
        this.configStore = configStore;
        this.statusStore = statusStore;
        this.issueFactory = issueFactory;
        this.pluginSettingsFactory = pluginSettingsFactory;
    }

    public void syncMapping(String mappingId) {
        DuplicationConfig config = configStore.load();
        DuplicationMapping mapping = findMapping(config, mappingId);
        if (mapping == null || !Boolean.TRUE.equals(mapping.enabled)) {
            return;
        }
        DuplicationMappingStats skipped = statusStore.tryBegin(mappingId);
        if (skipped != null) {
            log.info("Duplication skip for {}: {}", mappingId, skipped.lastSkipReason);
            return;
        }
        long started = System.currentTimeMillis();
        int created = 0;
        int alreadyLinked = 0;
        int updated = 0;
        int errors = 0;
        String lastError = null;
        try {
            ensureTestCaseInProject(mapping.jiraProjectKey);
            TmsSettings tms = loadTmsSettings();
            int skip = 0;
            while (true) {
                TmsPage<JsonObject> page = tmsClient.searchWorkItems(
                        tms.url,
                        tms.token,
                        skip,
                        PAGE_SIZE,
                        null,
                        mapping.tmsProjectId,
                        TmsAdaptersClient.WORK_ITEM_TYPES_ALL);
                if (page.items == null || page.items.isEmpty()) {
                    break;
                }
                for (JsonObject item : page.items) {
                    try {
                        String wiId = TmsJson.getString(item, "id");
                        if (wiId == null || wiId.isEmpty()) {
                            errors++;
                            continue;
                        }
                        JsonObject workItem = tmsClient.getWorkItem(tms.url, tms.token, wiId);
                        String wiName = TmsJson.getString(workItem, "name");
                        if (wiName == null || wiName.isEmpty()) {
                            wiName = TmsJson.getString(item, "name");
                        }
                        String description = WorkItemDescriptionBuilder.buildWiki(workItem);
                        String linkedIssueKey = linkSupport.findFirstBrowseIssueKey(workItem);
                        if (linkedIssueKey != null) {
                            linkSupport.ensureNamePrefixIfMissing(
                                    tms.url, tms.token, wiId, linkedIssueKey, workItem);
                            if (issueFactory.updateDescriptionIfChanged(linkedIssueKey, description)) {
                                updated++;
                            } else {
                                alreadyLinked++;
                            }
                            continue;
                        }
                        String issueKey = issueFactory.createTestCaseIssue(
                                mapping.jiraProjectKey, wiName, description);
                        String issueUrl = JiraTestCaseIssueFactory.buildIssueBrowseUrl(issueKey);
                        String title = issueKey + (wiName != null ? ": " + wiName : "");
                        linkSupport.addIssueLink(tms.url, tms.token, wiId, issueKey, issueUrl, title);
                        created++;
                    } catch (Exception e) {
                        errors++;
                        lastError = e.getMessage();
                        log.warn("Duplication failed for WI in mapping {}: {}", mappingId, e.getMessage());
                    }
                }
                skip += page.items.size();
                if (page.items.size() < PAGE_SIZE) {
                    break;
                }
                if (page.totalItems > 0 && skip >= page.totalItems) {
                    break;
                }
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            log.error("Duplication sync failed for mapping " + mappingId, e);
        } finally {
            statusStore.finish(
                    mappingId,
                    System.currentTimeMillis() - started,
                    created,
                    alreadyLinked,
                    updated,
                    errors,
                    lastError);
        }
    }

    private static DuplicationMapping findMapping(DuplicationConfig config, String mappingId) {
        if (config.mappings == null || mappingId == null) {
            return null;
        }
        for (DuplicationMapping mapping : config.mappings) {
            if (mapping != null && mappingId.equals(mapping.id)) {
                return mapping;
            }
        }
        return null;
    }

    private void ensureTestCaseInProject(String projectKey) {
        TestCaseIssueType.addTestCaseToProjects(new String[]{projectKey});
    }

    private TmsSettings loadTmsSettings() {
        String base = ConfigResource.Config.class.getName();
        String url = (String) pluginSettingsFactory.createGlobalSettings().get(base + ".url");
        String token = (String) pluginSettingsFactory.createGlobalSettings().get(base + ".privateToken");
        if (url == null || url.trim().isEmpty() || token == null || token.trim().isEmpty()) {
            throw new IllegalStateException("Test IT URL and private token must be configured");
        }
        return new TmsSettings(url.trim(), token.trim());
    }

    private static final class TmsSettings {
        final String url;
        final String token;

        TmsSettings(String url, String token) {
            this.url = url;
            this.token = token;
        }
    }
}
