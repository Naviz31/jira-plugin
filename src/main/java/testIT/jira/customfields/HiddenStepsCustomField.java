package testIT.jira.customfields;

import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import jakarta.inject.Inject;
import org.springframework.stereotype.Component;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import testIT.jira.ConfigResource;
import testIT.jira.testcase.TestCaseIssueType;

@Component("hiddenSteps")
public class HiddenStepsCustomField {
    private static final Logger log = LoggerFactory.getLogger(HiddenStepsCustomField.class);
    private static final String FIELD_NAME = "hiddenSteps";
    private static PluginSettingsFactory settingsFactory;

    @Inject
    public HiddenStepsCustomField(@ComponentImport PluginSettingsFactory pluginSettingsFactory) {
        settingsFactory = pluginSettingsFactory;
    }

    public static void changeCustomField() {
        List<Project> projects = CustomFieldProvisioner.projectsFromSettings(
                settingsFactory,
                ConfigResource.Config.class.getName() + ".projects");
        provision(projects);
    }

    public static void changeCustomField(String[] allowedProjectKeys) {
        provision(CustomFieldProvisioner.projectsByKeys(allowedProjectKeys));
    }

    private static void provision(List<Project> projects) {
        if (projects.isEmpty()) {
            return;
        }
        IssueType testCaseType = TestCaseIssueType.getTestCaseIssueType();
        if (testCaseType == null) {
            log.warn("IssueType 'TestCase' is not available; skipping {} custom field provision", FIELD_NAME);
            return;
        }
        CustomFieldProvisioner.ensureTextAreaField(FIELD_NAME, FIELD_NAME, testCaseType, projects);
    }
}
