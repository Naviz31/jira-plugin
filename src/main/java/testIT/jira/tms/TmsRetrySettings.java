package testIT.jira.tms;

/**
 * Shared defaults and clamps for TMS HTTP retry policy (plugin config + {@link TmsHttpClient}).
 */
public final class TmsRetrySettings {
    public static final int DEFAULT_RETRY_ATTEMPTS = 3;
    public static final int DEFAULT_RETRY_INTERVAL_MS = 2000;
    public static final int MIN_RETRY_ATTEMPTS = 1;
    public static final int MAX_RETRY_ATTEMPTS = 10;
    public static final int MIN_RETRY_INTERVAL_MS = 0;
    public static final int MAX_RETRY_INTERVAL_MS = 60_000;

    private TmsRetrySettings() {
    }

    public static int clampAttempts(int value) {
        if (value < MIN_RETRY_ATTEMPTS) {
            return MIN_RETRY_ATTEMPTS;
        }
        if (value > MAX_RETRY_ATTEMPTS) {
            return MAX_RETRY_ATTEMPTS;
        }
        return value;
    }

    public static int clampIntervalMs(int value) {
        if (value < MIN_RETRY_INTERVAL_MS) {
            return MIN_RETRY_INTERVAL_MS;
        }
        if (value > MAX_RETRY_INTERVAL_MS) {
            return MAX_RETRY_INTERVAL_MS;
        }
        return value;
    }

    public static int normalizeAttempts(Integer value) {
        return clampAttempts(value != null ? value : DEFAULT_RETRY_ATTEMPTS);
    }

    public static int normalizeIntervalMs(Integer value) {
        return clampIntervalMs(value != null ? value : DEFAULT_RETRY_INTERVAL_MS);
    }
}
