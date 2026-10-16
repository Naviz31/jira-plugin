package testIT.jira.testcase;

import com.atlassian.jira.avatar.Avatar;
import com.atlassian.jira.avatar.AvatarManager;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.icon.IconOwningObjectId;
import com.atlassian.jira.icon.IconType;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.link.IssueLinkType;
import com.atlassian.jira.issue.link.IssueLinkTypeManager;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import jakarta.inject.Inject;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import testIT.jira.ConfigResource;

@Component("testitTestCaseIssueType")
public class TestCaseIssueType {
    private static final Logger log = LoggerFactory.getLogger(TestCaseIssueType.class);

    private static final String ISSUE_TYPE_NAME = "TestCase";
    private static final String ISSUE_TYPE_DESCRIPTION = "Test case managed by Test IT";
    private static final String LINK_TYPE_NAME = "Tested by Test IT";
    private static final String ICON_CLASSPATH = "images/testcase.png";
    private static final String ICON_FILENAME = "testcase.png";
    /** Legacy hardcoded avatar id that is missing on many instances and shows as a broken icon. */
    private static final long LEGACY_BROKEN_AVATAR_ID = 10313L;

    private static PluginSettingsFactory settingsFactory;
    private static IssueType cachedIssueType;

    @Inject
    public TestCaseIssueType(@ComponentImport PluginSettingsFactory pluginSettingsFactory) {
        settingsFactory = pluginSettingsFactory;
    }

    /**
     * Ensures TestCase issue type exists with Test IT description and icon from plugin PNG.
     * Reclaims branding when an existing type named TestCase belongs to another plugin (e.g. Zephyr).
     * Safe to call on plugin enable — never throws.
     */
    public static void ensureTestCaseIcon() {
        try {
            IssueType issueType = getTestCaseIssueType();
            if (issueType == null) {
                return;
            }
            ensureBranding(issueType);
        } catch (Exception e) {
            log.warn("ensureTestCaseIcon failed", e);
        }
    }

    public static void addTestedByIssueLinkType() {
        IssueLinkTypeManager linkTypeManager = ComponentAccessor.getComponent(IssueLinkTypeManager.class);
        for (IssueLinkType linkType : linkTypeManager.getIssueLinkTypes(true)) {
            if (LINK_TYPE_NAME.equals(linkType.getName())) {
                return;
            }
        }
        linkTypeManager.createIssueLinkType(LINK_TYPE_NAME, "tests", "tested by", "");
    }

    public static void addTestCasetoProjects(String[] allowedProjectKeys) {
        addTestCaseToProjects(allowedProjectKeys);
    }

    public static void addTestCaseToProjectsFromSettings() {
        PluginSettingsFactory factory = settingsFactory != null
                ? settingsFactory
                : ComponentAccessor.getComponentOfType(PluginSettingsFactory.class);
        if (factory == null) {
            return;
        }
        PluginSettings settings = factory.createGlobalSettings();
        String allowedProjects = (String) settings.get(ConfigResource.Config.class.getName() + ".projects");
        if (allowedProjects == null) {
            return;
        }
        addTestCaseToProjects(allowedProjects.split(","));
    }

    public static void addTestCaseToProjects(String[] allowedProjectKeys) {
        if (allowedProjectKeys == null) {
            return;
        }
        IssueType testCaseType = getTestCaseIssueType();
        if (testCaseType == null) {
            return;
        }
        testCaseType = ensureBranding(testCaseType);
        Set<String> allowedKeys = new HashSet<>();
        for (String key : allowedProjectKeys) {
            if (key == null) {
                continue;
            }
            String trimmed = key.trim();
            if (!trimmed.isEmpty()) {
                allowedKeys.add(trimmed);
            }
        }
        if (allowedKeys.isEmpty()) {
            return;
        }
        ProjectManager projectManager = ComponentAccessor.getProjectManager();
        IssueTypeSchemeManager issueTypeSchemeManager = ComponentAccessor.getIssueTypeSchemeManager();
        for (Project project : projectManager.getProjectObjects()) {
            if (!allowedKeys.contains(project.getKey())) {
                continue;
            }
            Set<String> issueTypeIds = new HashSet<>();
            for (IssueType issueType : project.getIssueTypes()) {
                issueTypeIds.add(issueType.getId());
            }
            issueTypeIds.add(testCaseType.getId());
            FieldConfigScheme scheme = issueTypeSchemeManager.getConfigScheme(project);
            issueTypeSchemeManager.update(scheme, issueTypeIds);
        }
    }

    public static IssueType getTestCaseIssueType() {
        try {
            if (cachedIssueType != null) {
                return cachedIssueType;
            }
            Collection<IssueType> issueTypes = ComponentAccessor.getConstantsManager().getAllIssueTypeObjects();
            for (IssueType issueType : issueTypes) {
                if (ISSUE_TYPE_NAME.equalsIgnoreCase(issueType.getName())) {
                    cachedIssueType = issueType;
                    return cachedIssueType;
                }
            }
            Long avatarId = createAvatarFromPluginPng("0");
            if (avatarId == null) {
                avatarId = getDefaultIssueTypeAvatarId();
            }
            if (avatarId == null) {
                log.warn("Cannot create issue type TestCase: no avatar id available");
                return null;
            }
            IssueTypeManager issueTypeManager = ComponentAccessor.getComponentOfType(IssueTypeManager.class);
            cachedIssueType = issueTypeManager.createIssueType(
                    ISSUE_TYPE_NAME, ISSUE_TYPE_DESCRIPTION, avatarId);
            return cachedIssueType;
        } catch (Exception e) {
            log.warn("Failed to resolve TestCase issue type", e);
            return cachedIssueType;
        }
    }

    private static IssueType ensureBranding(IssueType issueType) {
        if (issueType == null || !needsBrandingUpdate(issueType)) {
            return issueType;
        }
        try {
            Long newAvatarId = createAvatarFromPluginPng(issueType.getId());
            if (newAvatarId == null) {
                newAvatarId = getDefaultIssueTypeAvatarId();
            }
            if (newAvatarId == null) {
                return issueType;
            }
            IssueTypeManager issueTypeManager = ComponentAccessor.getComponentOfType(IssueTypeManager.class);
            issueTypeManager.updateIssueType(
                    issueType,
                    ISSUE_TYPE_NAME,
                    ISSUE_TYPE_DESCRIPTION,
                    newAvatarId);
            cachedIssueType = null;
            Collection<IssueType> refreshed = ComponentAccessor.getConstantsManager().getAllIssueTypeObjects();
            for (IssueType refreshedType : refreshed) {
                if (issueType.getId().equals(refreshedType.getId())) {
                    cachedIssueType = refreshedType;
                    return refreshedType;
                }
            }
            return issueType;
        } catch (Exception e) {
            log.warn("Failed to apply TestCase issue type branding", e);
            return issueType;
        }
    }

    private static boolean needsBrandingUpdate(IssueType issueType) {
        String description = issueType.getDescription();
        if (description == null || description.trim().isEmpty()
                || !ISSUE_TYPE_DESCRIPTION.equals(description)) {
            return true;
        }
        return needsIconRepair(issueType);
    }

    private static boolean needsIconRepair(IssueType issueType) {
        try {
            Avatar avatar = issueType.getAvatar();
            if (avatar == null || avatar.getId() == null) {
                return true;
            }
            Long avatarId = avatar.getId();
            if (avatarId == LEGACY_BROKEN_AVATAR_ID) {
                return true;
            }
            AvatarManager avatarManager = ComponentAccessor.getComponent(AvatarManager.class);
            return avatarManager.getById(avatarId) == null;
        } catch (Exception e) {
            log.warn("Failed to inspect TestCase avatar; skipping icon repair", e);
            return false;
        }
    }

    private static Long createAvatarFromPluginPng(String ownerId) {
        try {
            AvatarManager avatarManager = ComponentAccessor.getComponent(AvatarManager.class);
            try (InputStream imageData = openIconResource()) {
                if (imageData == null) {
                    log.warn("Classpath resource {} not found", ICON_CLASSPATH);
                    return null;
                }
                Avatar created = avatarManager.create(
                        ICON_FILENAME,
                        AvatarManager.PNG_CONTENT_TYPE,
                        IconType.ISSUE_TYPE_ICON_TYPE,
                        new IconOwningObjectId(ownerId != null ? ownerId : "0"),
                        imageData,
                        null);
                return created != null ? created.getId() : null;
            }
        } catch (Exception e) {
            log.warn("Failed to create issue type avatar from {}", ICON_CLASSPATH, e);
            return null;
        }
    }

    private static InputStream openIconResource() {
        ClassLoader loader = TestCaseIssueType.class.getClassLoader();
        InputStream stream = loader != null ? loader.getResourceAsStream(ICON_CLASSPATH) : null;
        if (stream == null && Thread.currentThread().getContextClassLoader() != null) {
            stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(ICON_CLASSPATH);
        }
        return stream;
    }

    private static Long getDefaultIssueTypeAvatarId() {
        try {
            AvatarManager avatarManager = ComponentAccessor.getComponent(AvatarManager.class);
            return avatarManager.getDefaultAvatarId(IconType.ISSUE_TYPE_ICON_TYPE);
        } catch (Exception e) {
            log.warn("Failed to resolve default issue type avatar id", e);
            return null;
        }
    }
}
