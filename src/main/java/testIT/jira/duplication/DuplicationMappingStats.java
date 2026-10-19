package testIT.jira.duplication;

public class DuplicationMappingStats {
    public String state = "idle";
    public String lastStartedAt;
    public String lastFinishedAt;
    public Long lastDurationMs;
    public int createdCount;
    public int skippedAlreadyLinkedCount;
    public int updatedCount;
    public int errorCount;
    public String lastError;
    public String lastSkippedAt;
    public String lastSkipReason;
    /** ISO time when this mapping becomes eligible again (kick / schedule). */
    public String nextEligibleAt;
}
