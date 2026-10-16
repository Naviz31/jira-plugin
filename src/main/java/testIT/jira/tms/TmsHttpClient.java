package testIT.jira.tms;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.apache.http.Header;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import testIT.jira.ConfigResource;

final class TmsHttpClient {
    private static final Logger log = LoggerFactory.getLogger(TmsHttpClient.class);

    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int SOCKET_TIMEOUT_MS = 30_000;

    private TmsHttpClient() {
    }

    static HttpResponseData executeGet(URI uri, String token, String errorPrefix)
            throws TmsClientException, IOException {
        return executeWithRetry(errorPrefix, () -> {
            HttpGet request = new HttpGet(uri);
            request.setHeader("Authorization", authorizationHeader(token));
            request.setHeader("Accept", "application/json");
            return executeOnce(request, errorPrefix);
        });
    }

    static HttpResponseData executePost(URI uri, String token, String jsonBody, String errorPrefix)
            throws TmsClientException, IOException {
        String body = jsonBody != null ? jsonBody : "{}";
        return executeWithRetry(errorPrefix, () -> {
            HttpPost request = new HttpPost(uri);
            request.setHeader("Authorization", authorizationHeader(token));
            request.setHeader("Accept", "application/json");
            request.setHeader("Content-Type", "application/json");
            request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
            return executeOnce(request, errorPrefix);
        });
    }

    static HttpResponseData executePut(URI uri, String token, String jsonBody, String errorPrefix)
            throws TmsClientException, IOException {
        String body = jsonBody != null ? jsonBody : "{}";
        return executeWithRetry(errorPrefix, () -> {
            HttpPut request = new HttpPut(uri);
            request.setHeader("Authorization", authorizationHeader(token));
            request.setHeader("Accept", "application/json");
            request.setHeader("Content-Type", "application/json");
            request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
            return executeOnce(request, errorPrefix);
        });
    }

    static HttpResponseData executeGetWithoutError(URI uri, String token) throws IOException {
        RequestConfig requestConfig = requestConfig();
        HttpGet request = new HttpGet(uri);
        request.setConfig(requestConfig);
        request.setHeader("Authorization", authorizationHeader(token));
        request.setHeader("Accept", "application/json");
        try (CloseableHttpClient client = httpClient(requestConfig);
                CloseableHttpResponse response = client.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
                    : "";
            return new HttpResponseData(status, body, 0, 0, 0, null);
        }
    }

    private interface Attempt {
        HttpResponseData run() throws TmsClientException, IOException;
    }

    private static HttpResponseData executeWithRetry(String errorPrefix, Attempt attempt)
            throws TmsClientException, IOException {
        boolean retryEnabled = readRetryEnabled();
        int maxAttempts = retryEnabled
                ? TmsRetrySettings.clampAttempts(readRetryAttempts())
                : 1;
        int intervalMs = retryEnabled
                ? TmsRetrySettings.clampIntervalMs(readRetryIntervalMs())
                : 0;
        TmsClientException lastClientError = null;
        IOException lastIoError = null;
        for (int attemptIndex = 1; attemptIndex <= maxAttempts; attemptIndex++) {
            try {
                return attempt.run();
            } catch (TmsClientException e) {
                lastClientError = e;
                lastIoError = null;
                if (!isRetryableStatus(e.getStatusCode()) || attemptIndex >= maxAttempts) {
                    throw e;
                }
                log.warn("TMS request failed (attempt {}/{}): {}; retrying in {} ms",
                        attemptIndex, maxAttempts, e.getMessage(), intervalMs);
            } catch (IOException e) {
                lastIoError = e;
                lastClientError = null;
                if (attemptIndex >= maxAttempts) {
                    throw e;
                }
                log.warn("TMS request I/O failed (attempt {}/{}): {}; retrying in {} ms",
                        attemptIndex, maxAttempts,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                        intervalMs);
            }
            sleepQuietly(intervalMs);
        }
        if (lastClientError != null) {
            throw lastClientError;
        }
        if (lastIoError != null) {
            throw lastIoError;
        }
        throw new TmsClientException(0, truncate(errorPrefix + ": retry exhausted"));
    }

    private static HttpResponseData executeOnce(HttpRequestBase request, String errorPrefix)
            throws TmsClientException, IOException {
        RequestConfig requestConfig = requestConfig();
        request.setConfig(requestConfig);
        try (CloseableHttpClient client = httpClient(requestConfig);
                CloseableHttpResponse response = client.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
                    : "";
            if (status < 200 || status >= 300) {
                throw new TmsClientException(status, truncate(errorPrefix + ": " + body));
            }
            return new HttpResponseData(status, body,
                    parseIntHeader(response, "Pagination-Skip", 0),
                    parseIntHeader(response, "Pagination-Take", 0),
                    parseIntHeader(response, "Pagination-Total-Items", 0),
                    null);
        }
    }

    static boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static boolean readRetryEnabled() {
        String raw = readSetting(".tmsRetryEnabled");
        return raw != null && "true".equalsIgnoreCase(raw.trim());
    }

    private static int readRetryAttempts() {
        String raw = readSetting(".tmsRetryAttempts");
        if (raw == null || raw.isEmpty()) {
            return TmsRetrySettings.DEFAULT_RETRY_ATTEMPTS;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return TmsRetrySettings.DEFAULT_RETRY_ATTEMPTS;
        }
    }

    private static int readRetryIntervalMs() {
        String raw = readSetting(".tmsRetryIntervalMs");
        if (raw == null || raw.isEmpty()) {
            return TmsRetrySettings.DEFAULT_RETRY_INTERVAL_MS;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return TmsRetrySettings.DEFAULT_RETRY_INTERVAL_MS;
        }
    }

    private static String readSetting(String suffix) {
        try {
            PluginSettingsFactory factory = ComponentAccessor.getOSGiComponentInstanceOfType(PluginSettingsFactory.class);
            if (factory == null) {
                return null;
            }
            PluginSettings settings = factory.createGlobalSettings();
            Object value = settings.get(ConfigResource.Config.class.getName() + suffix);
            return value != null ? String.valueOf(value) : null;
        } catch (RuntimeException e) {
            log.debug("Cannot read TMS retry setting {}: {}", suffix, e.getMessage());
            return null;
        }
    }

    private static void sleepQuietly(int intervalMs) {
        if (intervalMs <= 0) {
            return;
        }
        try {
            Thread.sleep(intervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 800 ? message.substring(0, 800) + "..." : message;
    }

    private static RequestConfig requestConfig() {
        return RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setConnectionRequestTimeout(CONNECT_TIMEOUT_MS)
                .setSocketTimeout(SOCKET_TIMEOUT_MS)
                .build();
    }

    private static CloseableHttpClient httpClient(RequestConfig requestConfig) {
        return HttpClients.custom().setDefaultRequestConfig(requestConfig).build();
    }

    private static String authorizationHeader(String token) {
        String trimmedToken = token == null ? "" : token.trim();
        if (trimmedToken.regionMatches(true, 0, "PrivateToken ", 0, "PrivateToken ".length())
                || trimmedToken.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return trimmedToken;
        }
        return "PrivateToken " + trimmedToken;
    }

    private static int parseIntHeader(CloseableHttpResponse response, String name, int defaultValue) {
        Header header = response.getFirstHeader(name);
        if (header == null || header.getValue() == null || header.getValue().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(header.getValue().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    static final class HttpResponseData {
        final int statusCode;
        final String body;
        final int skip;
        final int take;
        final int totalItems;
        final TmsClientException error;

        HttpResponseData(int statusCode, String body, int skip, int take, int totalItems, TmsClientException error) {
            this.statusCode = statusCode;
            this.body = body;
            this.skip = skip;
            this.take = take;
            this.totalItems = totalItems;
            this.error = error;
        }
    }
}
