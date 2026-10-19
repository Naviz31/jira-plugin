package testIT.jira.duplication;

public class DuplicationMapping {
    public String id;
    /** Null means inherit legacy global config.enabled during normalize. */
    public Boolean enabled;
    public String jiraProjectKey;
    public String tmsProjectId;
    public String tmsProjectName;
    /** INTERVAL or DAILY */
    public String scheduleMode = "INTERVAL";
    public int intervalMinutes = 60;
    /** HH:mm server JVM timezone */
    public String dailyAt = "02:00";
}
