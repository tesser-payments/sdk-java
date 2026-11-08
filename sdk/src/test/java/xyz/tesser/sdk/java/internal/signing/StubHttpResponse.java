package xyz.tesser.sdk.java.internal.signing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;

public final class StubHttpResponse implements HttpResponse<String> {

    private final int status;
    private final String body;
    private final Map<String, List<String>> headers;

    public StubHttpResponse(int status, String body) {
        this(status, body, Map.of());
    }

    public StubHttpResponse(int status, String body, Map<String, List<String>> headers) {
        this.status = status;
        this.body = body;
        this.headers = headers;
    }

    @Override
    public int statusCode() {
        return status;
    }

    @Override
    public HttpRequest request() {
        throw new UnsupportedOperationException("StubHttpResponse has no request");
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
        return HttpHeaders.of(headers, (name, value) -> true);
    }

    @Override
    public String body() {
        return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return Optional.empty();
    }

    @Override
    public URI uri() {
        return URI.create("https://api.tesser.xyz");
    }

    @Override
    public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
    }
}
