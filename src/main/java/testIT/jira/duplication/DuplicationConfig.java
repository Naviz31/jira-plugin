package testIT.jira.duplication;

import java.util.ArrayList;
import java.util.List;

public class DuplicationConfig {
    /**
     * Legacy global switch kept for migration of old PluginSettings JSON only.
     * Runtime scheduling uses {@link DuplicationMapping#enabled} per mapping.
     */
    public boolean enabled;
    public List<DuplicationMapping> mappings = new ArrayList<>();

    public static DuplicationConfig disabled() {
        DuplicationConfig config = new DuplicationConfig();
        config.enabled = false;
        config.mappings = new ArrayList<>();
        return config;
    }

    public int enabledMappingCount() {
        if (mappings == null) {
            return 0;
        }
        int count = 0;
        for (DuplicationMapping mapping : mappings) {
            if (mapping != null && Boolean.TRUE.equals(mapping.enabled)) {
                count++;
            }
        }
        return count;
    }
}
