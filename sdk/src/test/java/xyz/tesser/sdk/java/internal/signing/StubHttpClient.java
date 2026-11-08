package xyz.tesser.sdk.java.internal.signing;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

public final class StubHttpClient extends HttpClient {

    private final Function<HttpRequest, CompletableFuture<HttpResponse<String>>> respond;
    private final List<HttpRequest> requests = Collections.synchronizedList(new ArrayList<>());

    private StubHttpClient(Function<HttpRequest, CompletableFuture<HttpResponse<String>>> respond) {
        this.respond = respond;
    }

    public static StubHttpClient responding(
            Function<HttpRequest, CompletableFuture<HttpResponse<String>>> respond) {
        return new StubHttpClient(respond);
    }

    public static StubHttpClient respondingWith(HttpResponse<String> response) {
        return new StubHttpClient(request -> CompletableFuture.completedFuture(response));
    }

    public static StubHttpClient failingWith(Throwable failure) {
        return new StubHttpClient(request -> CompletableFuture.failedFuture(failure));
    }

    public static StubHttpClient unused() {
        return failingWith(new AssertionError("No request to Tesser was expected"));
    }

    public List<HttpRequest> requests() {
        return requests;
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        throw new UnsupportedOperationException("StubHttpClient only supports sendAsync");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        requests.add(request);
        return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) respond.apply(request);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return sendAsync(request, responseBodyHandler);
    }
}
