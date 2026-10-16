package testIT.jira.duplication;

import com.atlassian.sal.api.scheduling.PluginJob;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SAL scheduled job entrypoint. Actual work is delegated to {@link DuplicationScheduler}.
 */
public class DuplicationTickJob implements PluginJob {
    private static final Logger log = LoggerFactory.getLogger(DuplicationTickJob.class);

    @Override
    public void execute(Map<String, Object> jobDataMap) {
        Object scheduler = jobDataMap != null ? jobDataMap.get(DuplicationScheduler.JOB_DATA_KEY) : null;
        if (scheduler instanceof DuplicationScheduler) {
            ((DuplicationScheduler) scheduler).onTick();
        } else {
            log.warn("DuplicationTickJob missing DuplicationScheduler in job data");
        }
    }
}
