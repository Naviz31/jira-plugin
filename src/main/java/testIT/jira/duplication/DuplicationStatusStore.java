package testIT.jira.duplication;

import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.inject.Inject;
import org.springframework.stereotype.Component;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

@Component
public class DuplicationStatusStore {
    private static final String SETTINGS_KEY = DuplicationConfig.class.getName() + ".status";
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, DuplicationMappingStats>>(){}.getType();

    private final PluginSettingsFactory pluginSettingsFactory;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Inject
    public DuplicationStatusStore(@ComponentImport PluginSettingsFactory pluginSettingsFactory) {
        this.pluginSettingsFactory = pluginSettingsFactory;
    }

    public Map<String, DuplicationMappingStats> loadAll() {
        PluginSettings settings = pluginSettingsFactory.createGlobalSettings();
        String raw = (String) settings.get(SETTINGS_KEY);
        if (raw == null || raw.trim().isEmpty()) {
            return new HashMap<>();
        }
        Map<String, DuplicationMappingStats> map = GSON.fromJson(raw, MAP_TYPE);
        return map != null ? map : new HashMap<>();
    }

    public DuplicationMappingStats get(String mappingId) {
        if (mappingId == null) {
            return new DuplicationMappingStats();
        }
        DuplicationMappingStats stats = loadAll().get(mappingId);
        return stats != null ? stats : new DuplicationMappingStats();
    }

    public synchronized void save(String mappingId, DuplicationMappingStats stats) {
        Map<String, DuplicationMappingStats> all = loadAll();
        all.put(mappingId, stats);
        pluginSettingsFactory.createGlobalSettings().put(SETTINGS_KEY, GSON.toJson(all));
    }

    public synchronized void clearStaleRunning() {
        Map<String, DuplicationMappingStats> all = loadAll();
        boolean changed = false;
        for (DuplicationMappingStats stats : all.values()) {
            if (stats != null && "running".equalsIgnoreCase(stats.state)) {
                stats.state = "idle";
                stats.lastError = "Interrupted (plugin restart or previous run stale)";
                stats.lastFinishedAt = Instant.now().toString();
                changed = true;
            }
        }
        if (changed) {
            pluginSettingsFactory.createGlobalSettings().put(SETTINGS_KEY, GSON.toJson(all));
        }
    }

    /**
     * @return null if started; stats snapshot if skipped (already running)
     */
    public synchronized DuplicationMappingStats tryBegin(String mappingId) {
        Object lock = locks.computeIfAbsent(mappingId, id -> new Object());
        synchronized (lock) {
            DuplicationMappingStats stats = get(mappingId);
            if ("running".equalsIgnoreCase(stats.state)) {
                stats.lastSkippedAt = Instant.now().toString();
                stats.lastSkipReason = "Previous sync still running";
                save(mappingId, stats);
                return stats;
            }
            stats.state = "running";
            stats.lastStartedAt = Instant.now().toString();
            stats.lastError = null;
            save(mappingId, stats);
            return null;
        }
    }

    public synchronized void finish(
            String mappingId,
            long durationMs,
            int created,
            int skippedLinked,
            int updated,
            int errors,
            String error) {
        DuplicationMappingStats stats = get(mappingId);
        stats.state = "idle";
        stats.lastFinishedAt = Instant.now().toString();
        stats.lastDurationMs = durationMs;
        stats.createdCount = created;
        stats.skippedAlreadyLinkedCount = skippedLinked;
        stats.updatedCount = updated;
        stats.errorCount = errors;
        stats.lastError = error;
        save(mappingId, stats);
    }

    public synchronized void setNextEligibleAt(String mappingId, Instant when) {
        DuplicationMappingStats stats = get(mappingId);
        stats.nextEligibleAt = when != null ? when.toString() : null;
        save(mappingId, stats);
    }

    /**
     * Records an overlap skip at most once per running period (does not clear lastDurationMs).
     */
    public synchronized void recordSkipOnceWhileRunning(String mappingId, String reason) {
        DuplicationMappingStats stats = get(mappingId);
        if (!"running".equalsIgnoreCase(stats.state)) {
            return;
        }
        if (stats.lastSkippedAt != null && stats.lastStartedAt != null) {
            try {
                Instant skipped = Instant.parse(stats.lastSkippedAt);
                Instant started = Instant.parse(stats.lastStartedAt);
                if (!skipped.isBefore(started)) {
                    return;
                }
            } catch (Exception ignored) {
                // fall through and write skip
            }
        }
        stats.lastSkippedAt = Instant.now().toString();
        stats.lastSkipReason = reason != null ? reason : "Previous sync still running";
        save(mappingId, stats);
    }
}
