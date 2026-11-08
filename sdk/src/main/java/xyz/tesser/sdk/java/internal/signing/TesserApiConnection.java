package xyz.tesser.sdk.java.internal.signing;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import xyz.tesser.sdk.java.error.TesserError;

public final class TesserApiConnection {

    public static final String PRODUCTION_API_BASE_URL = "https://api.tesser.xyz";

    public static final String SANDBOX_API_BASE_URL = "https://api-sandbox.tesser.xyz";

    public static final List<String> DEFAULT_API_BASE_URLS =
            List.of(PRODUCTION_API_BASE_URL, SANDBOX_API_BASE_URL);

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final HttpClient httpClient;
    private final List<String> candidateBaseUrls;
    private volatile String resolvedBaseUrl;

    private TesserApiConnection(
            HttpClient httpClient, List<String> candidateBaseUrls, String resolvedBaseUrl) {
        this.httpClient = httpClient;
        this.candidateBaseUrls = candidateBaseUrls;
        this.resolvedBaseUrl = resolvedBaseUrl;
    }

    public static TesserApiConnection create(String apiBaseUrl, HttpClient httpClient) {
        Objects.requireNonNull(apiBaseUrl, "apiBaseUrl");
        Objects.requireNonNull(httpClient, "httpClient");
        String normalizedApiBaseUrl = normalizeApiBaseUrl(apiBaseUrl);
        return new TesserApiConnection(
                httpClient, List.of(normalizedApiBaseUrl), normalizedApiBaseUrl);
    }

    public static TesserApiConnection detectingEnvironment(HttpClient httpClient) {
        return detectingEnvironment(httpClient, DEFAULT_API_BASE_URLS);
    }

    public static TesserApiConnection detectingEnvironment(
            HttpClient httpClient, List<String> candidateBaseUrls) {
        Objects.requireNonNull(httpClient, "httpClient");
        return new TesserApiConnection(httpClient, List.copyOf(candidateBaseUrls), null);
    }

    public HttpClient httpClient() {
        return httpClient;
    }

    public List<String> candidateBaseUrls() {
        return candidateBaseUrls;
    }

    public String resolvedBaseUrl() {
        return resolvedBaseUrl;
    }

    public void remember(String baseUrl) {
        if (resolvedBaseUrl == null) {
            resolvedBaseUrl = baseUrl;
        }
    }

    public static HttpClient createDefaultHttpClient() {
        return HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    }

    public static String normalizeApiBaseUrl(String apiBaseUrl) {
        String normalized = removeSuffix(apiBaseUrl.trim(), "/");
        normalized = removeSuffix(removeSuffix(normalized, "/v1"), "/");
        if (!isHttpsOrLoopbackHttpUrl(normalized)) {
            throw new TesserError.ConfigError(
                    "apiBaseUrl must be an https URL, for example https://api.tesser.xyz");
        }
        return normalized;
    }

    private static String removeSuffix(String value, String suffix) {
        if (value.endsWith(suffix)) {
            return value.substring(0, value.length() - suffix.length());
        }
        return value;
    }

    private static boolean isHttpsOrLoopbackHttpUrl(String value) {
        URI uri = parseUri(value);
        if (!hasHost(uri)) {
            return false;
        }
        if ("https".equals(uri.getScheme())) {
            return true;
        }
        return "http".equals(uri.getScheme()) && isLoopbackHost(uri.getHost());
    }

    private static boolean hasHost(URI uri) {
        if (uri == null) {
            return false;
        }
        String host = uri.getHost();
        return host != null && !host.isBlank();
    }

    private static boolean isLoopbackHost(String host) {
        return LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private static URI parseUri(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
