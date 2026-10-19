package testIT.jira;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.atlassian.sal.api.transaction.TransactionCallback;
import com.atlassian.sal.api.transaction.TransactionTemplate;
import com.atlassian.sal.api.user.UserManager;
import com.google.gson.Gson;
import jakarta.inject.Inject;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import testIT.jira.customfields.HiddenStepsCustomField;
import testIT.jira.customfields.TestResultIdCustomField;
import testIT.jira.duplication.DuplicationConfig;
import testIT.jira.duplication.DuplicationConfigStore;
import testIT.jira.duplication.DuplicationMapping;
import testIT.jira.duplication.DuplicationMappingStats;
import testIT.jira.duplication.DuplicationScheduler;
import testIT.jira.duplication.DuplicationStatusStore;
import testIT.jira.testcase.TestCaseIssueType;
import testIT.jira.tms.TmsRetrySettings;

@Path("/")
@Component
public class ConfigResource {
    private static final Logger log = LoggerFactory.getLogger(ConfigResource.class);
    private static final Gson GSON = new Gson();

    @ComponentImport
    private final UserManager userManager;
    @ComponentImport
    private final PluginSettingsFactory pluginSettingsFactory;
    @ComponentImport
    private final TransactionTemplate transactionTemplate;
    private final DuplicationConfigStore duplicationConfigStore;
    private final DuplicationStatusStore duplicationStatusStore;
    private final DuplicationScheduler duplicationScheduler;

    @Inject
    public ConfigResource(
            UserManager userManager,
            PluginSettingsFactory pluginSettingsFactory,
            TransactionTemplate transactionTemplate,
            DuplicationConfigStore duplicationConfigStore,
            DuplicationStatusStore duplicationStatusStore,
            DuplicationScheduler duplicationScheduler) {
        this.userManager = userManager;
        this.pluginSettingsFactory = pluginSettingsFactory;
        this.transactionTemplate = transactionTemplate;
        this.duplicationConfigStore = duplicationConfigStore;
        this.duplicationStatusStore = duplicationStatusStore;
        this.duplicationScheduler = duplicationScheduler;
    }

    @GET
    @Path("/projects")
    @Produces("application/json")
    public Response getProjects(@Context HttpServletRequest request) {
        if (!isSystemAdmin(request)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        try {
            ProjectManager projectManager = ComponentAccessor.getProjectManager();
            Map<String, String> projects = new HashMap<>();
            for (Project project : projectManager.getProjectObjects()) {
                projects.put(project.getKey(), project.getName());
            }
            return Response.ok(GSON.toJson(projects)).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            log.error("Failed to load Jira projects list", e);
            return serverError(e);
        }
    }

    @GET
    @Path("/duplication/status")
    @Produces("application/json")
    public Response getDuplicationStatus(@Context HttpServletRequest request) {
        if (!isSystemAdmin(request)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        try {
            Map<String, DuplicationMappingStats> status = duplicationStatusStore.loadAll();
            return Response.ok(GSON.toJson(Collections.singletonMap("byMappingId", status)))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (Exception e) {
            log.error("Failed to load duplication status", e);
            return serverError(e);
        }
    }

    @GET
    @Produces("application/json")
    public Response get(@Context HttpServletRequest request) {
        if (!isSystemAdmin(request)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        try {
            Config config = transactionTemplate.execute(new TransactionCallback<Config>() {
                @Override
                public Config doInTransaction() {
                    return loadConfig();
                }
            });
            return Response.ok(GSON.toJson(config)).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            log.error("Failed to load Test IT config", e);
            return serverError(e);
        }
    }

    @PUT
    @Consumes("application/json")
    public Response put(String rawBody, @Context HttpServletRequest request) {
        if (!isSystemAdmin(request)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        final Config config;
        try {
            config = GSON.fromJson(rawBody, Config.class);
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Collections.singletonMap("message", "Invalid JSON body"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        final String url = config == null ? null : config.getUrl();
        final String privateToken = config == null || config.getPrivateToken() == null ? "" : config.getPrivateToken();
        final String[] projects = config == null || config.getProjects() == null ? new String[0] : config.getProjects();
        final boolean tmsRetryEnabled = config != null && Boolean.TRUE.equals(config.getTmsRetryEnabled());
        final int tmsRetryAttempts = normalizeRetryAttempts(config != null ? config.getTmsRetryAttempts() : null);
        final int tmsRetryIntervalMs = normalizeRetryIntervalMs(config != null ? config.getTmsRetryIntervalMs() : null);
        final DuplicationConfig duplication;
        try {
            duplication = DuplicationConfigStore.normalize(config != null ? config.getDuplication() : null);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Collections.singletonMap("message", e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        log.info("Saving Test IT config: url={}, privateTokenSet={}, projects={}, retryEnabled={}, retryAttempts={}, retryIntervalMs={}, enabledMappings={}/{}",
                url, !privateToken.isEmpty(), projects.length, tmsRetryEnabled, tmsRetryAttempts, tmsRetryIntervalMs,
                duplication.enabledMappingCount(),
                duplication.mappings != null ? duplication.mappings.size() : 0);
        try {
            transactionTemplate.execute((TransactionCallback<Void>) () -> {
                saveConfig(url, privateToken, projects, tmsRetryEnabled, tmsRetryAttempts, tmsRetryIntervalMs);
                duplicationConfigStore.save(duplication);
                applyProjectChanges(mergeProjectKeys(projects, duplication));
                return null;
            });
            duplicationScheduler.afterConfigSaved(duplication);
            return Response.noContent().build();
        } catch (Exception e) {
            log.error("Failed to save Test IT config", e);
            return serverError(e);
        }
    }

    private Config loadConfig() {
        PluginSettings settings = pluginSettingsFactory.createGlobalSettings();
        Config config = new Config();
        config.setUrl(readSetting(settings, ".url"));
        config.setPrivateToken(readSetting(settings, ".privateToken"));
        String projectsSetting = readSetting(settings, ".projects");
        config.setProjects(projectsSetting.isEmpty() ? new String[0] : projectsSetting.split(","));
        config.setTmsRetryEnabled(parseBooleanSetting(settings, ".tmsRetryEnabled"));
        config.setTmsRetryAttempts(normalizeRetryAttempts(parseIntSetting(settings, ".tmsRetryAttempts")));
        config.setTmsRetryIntervalMs(normalizeRetryIntervalMs(parseIntSetting(settings, ".tmsRetryIntervalMs")));
        config.setDuplication(duplicationConfigStore.load());
        return config;
    }

    private void saveConfig(
            String url,
            String privateToken,
            String[] projects,
            boolean tmsRetryEnabled,
            int tmsRetryAttempts,
            int tmsRetryIntervalMs) {
        PluginSettings settings = pluginSettingsFactory.createGlobalSettings();
        settings.put(Config.class.getName() + ".url", url);
        settings.put(Config.class.getName() + ".privateToken", privateToken);
        settings.put(Config.class.getName() + ".projects", String.join(",", projects));
        settings.put(Config.class.getName() + ".tmsRetryEnabled", String.valueOf(tmsRetryEnabled));
        settings.put(Config.class.getName() + ".tmsRetryAttempts", String.valueOf(tmsRetryAttempts));
        settings.put(Config.class.getName() + ".tmsRetryIntervalMs", String.valueOf(tmsRetryIntervalMs));
    }

    private static boolean parseBooleanSetting(PluginSettings settings, String suffix) {
        String value = (String) settings.get(Config.class.getName() + suffix);
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    private static Integer parseIntSetting(PluginSettings settings, String suffix) {
        String value = (String) settings.get(Config.class.getName() + suffix);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static int normalizeRetryAttempts(Integer value) {
        return TmsRetrySettings.normalizeAttempts(value);
    }

    static int normalizeRetryIntervalMs(Integer value) {
        return TmsRetrySettings.normalizeIntervalMs(value);
    }

    private void applyProjectChanges(String[] projects) {
        if (projects.length == 0) {
            return;
        }
        TestCaseIssueType.addTestedByIssueLinkType();
        TestCaseIssueType.addTestCaseToProjects(projects);
        HiddenStepsCustomField.changeCustomField(projects);
        TestResultIdCustomField.changeCustomField(projects);
    }

    private static String[] mergeProjectKeys(String[] whitelist, DuplicationConfig duplication) {
        Set<String> keys = new HashSet<>();
        if (whitelist != null) {
            for (String key : whitelist) {
                if (key != null && !key.trim().isEmpty()) {
                    keys.add(key.trim());
                }
            }
        }
        if (duplication != null && duplication.mappings != null) {
            for (DuplicationMapping mapping : duplication.mappings) {
                if (mapping != null && mapping.jiraProjectKey != null && !mapping.jiraProjectKey.isEmpty()) {
                    keys.add(mapping.jiraProjectKey);
                }
            }
        }
        return keys.toArray(new String[0]);
    }

    private String readSetting(PluginSettings settings, String suffix) {
        String value = (String) settings.get(Config.class.getName() + suffix);
        return value != null ? value : "";
    }

    private boolean isSystemAdmin(HttpServletRequest request) {
        String username = userManager.getRemoteUsername(request);
        return username != null && userManager.isSystemAdmin(username);
    }

    private Response serverError(Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
        return Response.serverError()
                .entity(Collections.singletonMap("message", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    @XmlRootElement
    @XmlAccessorType(XmlAccessType.FIELD)
    public static final class Config {
        @XmlElement
        private String url;
        @XmlElement
        private String privateToken;
        @XmlElement
        private Boolean tmsRetryEnabled;
        @XmlElement
        private Integer tmsRetryAttempts;
        @XmlElement
        private Integer tmsRetryIntervalMs;
        @XmlElement
        private String[] projects;
        @XmlElement
        private DuplicationConfig duplication;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getPrivateToken() {
            return privateToken;
        }

        public void setPrivateToken(String privateToken) {
            this.privateToken = privateToken;
        }

        public Boolean getTmsRetryEnabled() {
            return tmsRetryEnabled;
        }

        public void setTmsRetryEnabled(Boolean tmsRetryEnabled) {
            this.tmsRetryEnabled = tmsRetryEnabled;
        }

        public Integer getTmsRetryAttempts() {
            return tmsRetryAttempts;
        }

        public void setTmsRetryAttempts(Integer tmsRetryAttempts) {
            this.tmsRetryAttempts = tmsRetryAttempts;
        }

        public Integer getTmsRetryIntervalMs() {
            return tmsRetryIntervalMs;
        }

        public void setTmsRetryIntervalMs(Integer tmsRetryIntervalMs) {
            this.tmsRetryIntervalMs = tmsRetryIntervalMs;
        }

        public String[] getProjects() {
            return projects;
        }

        public void setProjects(String[] projects) {
            this.projects = projects;
        }

        public DuplicationConfig getDuplication() {
            return duplication;
        }

        public void setDuplication(DuplicationConfig duplication) {
            this.duplication = duplication;
        }
    }
}
