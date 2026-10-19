package testIT.jira.duplication;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.google.gson.Gson;
import jakarta.inject.Inject;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class DuplicationConfigStore {
    static final String SETTINGS_KEY = DuplicationConfig.class.getName() + ".json";
    private static final Gson GSON = new Gson();

    private final PluginSettingsFactory pluginSettingsFactory;

    @Inject
    public DuplicationConfigStore(@ComponentImport PluginSettingsFactory pluginSettingsFactory) {
        this.pluginSettingsFactory = pluginSettingsFactory;
    }

    public DuplicationConfig load() {
        PluginSettings settings = pluginSettingsFactory.createGlobalSettings();
        String raw = (String) settings.get(SETTINGS_KEY);
        if (raw == null || raw.trim().isEmpty()) {
            return DuplicationConfig.disabled();
        }
        DuplicationConfig config = GSON.fromJson(raw, DuplicationConfig.class);
        if (config == null) {
            return DuplicationConfig.disabled();
        }
        return normalize(config);
    }

    public void save(DuplicationConfig config) {
        if (config == null) {
            config = DuplicationConfig.disabled();
        }
        pluginSettingsFactory.createGlobalSettings().put(SETTINGS_KEY, GSON.toJson(config));
    }

    /**
     * Validates and normalizes mappings. Throws IllegalArgumentException on duplicate pairs.
     * Migrates legacy global {@code enabled} onto mappings when per-mapping enabled is absent.
     */
    public static DuplicationConfig normalize(DuplicationConfig input) {
        DuplicationConfig config = new DuplicationConfig();
        config.mappings = new ArrayList<>();
        if (input == null || input.mappings == null) {
            config.enabled = false;
            return config;
        }
        boolean legacyGlobalEnabled = input.enabled;
        Set<String> pairs = new HashSet<>();
        for (DuplicationMapping mapping : input.mappings) {
            if (mapping == null) {
                continue;
            }
            String jiraKey = mapping.jiraProjectKey != null ? mapping.jiraProjectKey.trim().toUpperCase(Locale.ROOT) : "";
            String tmsId = mapping.tmsProjectId != null ? mapping.tmsProjectId.trim() : "";
            if (jiraKey.isEmpty() || tmsId.isEmpty()) {
                throw new IllegalArgumentException("Each mapping requires jiraProjectKey and tmsProjectId");
            }
            String pair = jiraKey + "|" + tmsId.toLowerCase(Locale.ROOT);
            if (!pairs.add(pair)) {
                throw new IllegalArgumentException("Duplicate mapping pair: " + jiraKey + " → " + tmsId);
            }
            DuplicationMapping normalized = new DuplicationMapping();
            normalized.id = (mapping.id != null && !mapping.id.trim().isEmpty())
                    ? mapping.id.trim()
                    : UUID.randomUUID().toString();
            normalized.enabled = mapping.enabled != null ? mapping.enabled : legacyGlobalEnabled;
            normalized.jiraProjectKey = jiraKey;
            normalized.tmsProjectId = tmsId;
            normalized.tmsProjectName = mapping.tmsProjectName;
            String mode = mapping.scheduleMode != null ? mapping.scheduleMode.trim().toUpperCase(Locale.ROOT) : "INTERVAL";
            normalized.scheduleMode = "DAILY".equals(mode) ? "DAILY" : "INTERVAL";
            normalized.intervalMinutes = sanitizeInterval(mapping.intervalMinutes);
            normalized.dailyAt = sanitizeDailyAt(mapping.dailyAt);
            config.mappings.add(normalized);
        }
        config.enabled = config.enabledMappingCount() > 0;
        return config;
    }

    private static int sanitizeInterval(int minutes) {
        int[] allowed = {15, 30, 60, 180, 360, 1440};
        for (int value : allowed) {
            if (minutes == value) {
                return value;
            }
        }
        return 60;
    }

    private static String sanitizeDailyAt(String dailyAt) {
        if (dailyAt != null && dailyAt.matches("^([01]?\\d|2[0-3]):[0-5]\\d$")) {
            String[] parts = dailyAt.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
        }
        return "02:00";
    }
}
