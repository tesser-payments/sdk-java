package xyz.tesser.sdk.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import xyz.tesser.sdk.java.error.TesserError;
import xyz.tesser.sdk.java.internal.signing.Stamp;
import xyz.tesser.sdk.java.internal.signing.StampResult;
import xyz.tesser.sdk.java.internal.signing.StubHttpClient;
import xyz.tesser.sdk.java.internal.signing.StubHttpResponse;
import xyz.tesser.sdk.java.internal.signing.TesserApiConnection;
import xyz.tesser.sdk.java.internal.signing.UnsignedPayloadFixtures;

class LocalSignerEnvironmentDetectionTest {

    private static final SigningConfig CFG =
            TestKeys.config(UnsignedPayloadFixtures.organizationId());

    private static final String PRODUCTION_HOST = "api.tesser.xyz";
    private static final String SANDBOX_HOST = "api-sandbox.tesser.xyz";

    private static final String SIGNING_KEY_NOT_ACCEPTED_BODY =
            "{\"errors\":[{\"error_code\":\"signing-4010\","
                    + "\"error_message\":\"Signing key not accepted\"}]}";

    private final Map<String, Function<HttpRequest, CompletableFuture<HttpResponse<String>>>>
            responsesByHost = new HashMap<>();

    private final StubHttpClient httpClient =
            StubHttpClient.responding(
                    request -> responsesByHost.get(request.uri().getHost()).apply(request));

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

    private final List<String> stampValues = new ArrayList<>();

    private final Stamp countingStamp =
            (keys, body) -> {
                String stampValue = "STAMP_" + stampValues.size();
                stampValues.add(stampValue);
                return CompletableFuture.completedFuture(new StampResult("X-Stamp", stampValue));
            };

    private LocalSigner detectingSigner() {
        return new LocalSigner(
                CFG,
                TesserApiConnection.detectingEnvironment(httpClient),
                countingStamp,
                clock::incrementAndGet);
    }

    private void respond(String host, int status, String body) {
        respond(host, status, body, Map.of());
    }

    private void respond(String host, int status, String body, Map<String, List<String>> headers) {
        responsesByHost.put(
                host,
                request ->
                        CompletableFuture.completedFuture(
                                new StubHttpResponse(status, body, headers)));
    }

    private void respondWithEthereumFixture(String host) {
        respond(host, 200, UnsignedPayloadFixtures.loadEthereumResponse());
    }

    private void rejectSigningKey(String host) {
        respond(host, 401, SIGNING_KEY_NOT_ACCEPTED_BODY);
    }

    private static CreateWalletParams ethereumParams() {
        return new CreateWalletParams(
                UnsignedPayloadFixtures.walletName(), WalletType.STABLECOIN_ETHEREUM);
    }

    private List<String> requestedHosts() {
        return httpClient.requests().stream().map(request -> request.uri().getHost()).toList();
    }

    private static <T extends Throwable> T failureOf(
            CompletableFuture<SignedResult> future, Class<T> type) {
        ExecutionException failure = assertThrows(ExecutionException.class, future::get);
        assertThat(failure.getCause()).isInstanceOf(type);
        return type.cast(failure.getCause());
    }

    private static Map<String, String> queryParameters(HttpRequest request) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : request.uri().getRawQuery().split("&")) {
            String[] keyAndValue = pair.split("=", 2);
            parameters.put(
                    keyAndValue[0], URLDecoder.decode(keyAndValue[1], StandardCharsets.UTF_8));
        }
        return parameters;
    }

    @Test
    void productionAcceptingTheKeyMakesOneRequestAndIsRemembered() throws Exception {
        respondWithEthereumFixture(PRODUCTION_HOST);
        LocalSigner signer = detectingSigner();

        signer.signCreateWallet(ethereumParams()).get();
        signer.signCreateWallet(ethereumParams()).get();

        assertThat(requestedHosts()).containsExactly(PRODUCTION_HOST, PRODUCTION_HOST);
    }

    @Test
    void productionRejectingTheKeyFallsBackToSandboxWithAFreshQueryAndStamp() throws Exception {
        rejectSigningKey(PRODUCTION_HOST);
        respondWithEthereumFixture(SANDBOX_HOST);

        SignedResult result = detectingSigner().signCreateWallet(ethereumParams()).get();

        assertThat(requestedHosts()).containsExactly(PRODUCTION_HOST, SANDBOX_HOST);
        HttpRequest productionRequest = httpClient.requests().get(0);
        HttpRequest sandboxRequest = httpClient.requests().get(1);
        assertThat(queryParameters(sandboxRequest).get("timestampMs"))
                .isNotEqualTo(queryParameters(productionRequest).get("timestampMs"));
        assertThat(sandboxRequest.headers().firstValue("X-Stamp"))
                .isNotEqualTo(productionRequest.headers().firstValue("X-Stamp"));
        assertThat(result.metadata().body()).isEqualTo(UnsignedPayloadFixtures.loadEthereumBody());
    }

    @Test
    void rememberedSandboxIsTheOnlyHostCalledAfterwards() throws Exception {
        rejectSigningKey(PRODUCTION_HOST);
        respondWithEthereumFixture(SANDBOX_HOST);
        LocalSigner signer = detectingSigner();
        signer.signCreateWallet(ethereumParams()).get();

        signer.signCreateWallet(ethereumParams()).get();

        assertThat(requestedHosts()).containsExactly(PRODUCTION_HOST, SANDBOX_HOST, SANDBOX_HOST);
    }

    @Test
    void productionUnauthorizedWithAnotherErrorCodeDoesNotFallBack() {
        respond(
                PRODUCTION_HOST,
                401,
                "{\"errors\":[{\"error_code\":\"auth-4011\",\"error_message\":\"Other\"}]}");

        TesserError.APIError error =
                failureOf(
                        detectingSigner().signCreateWallet(ethereumParams()),
                        TesserError.APIError.class);

        assertThat(error.errorCode()).isEqualTo("auth-4011");
        assertThat(requestedHosts()).containsExactly(PRODUCTION_HOST);
    }

    @Test
    void productionUnauthorizedWithoutAnErrorEnvelopeDoesNotFallBack() {
        respond(PRODUCTION_HOST, 401, "Unauthorized");

        TesserError.APIError error =
                failureOf(
                        detectingSigner().signCreateWallet(ethereumParams()),
                        TesserError.APIError.class);

        assertThat(error.status()).isEqualTo(401);
        assertThat(requestedHosts()).containsExactly(PRODUCTION_HOST);
    }

    @Test
    void productionRejectionWithTheSigningKeyCodeButAnotherStatusDoesNotFallBack() {
        respond(PRODUCTION_HOST, 403, SIGNING_KEY_NOT_ACCEPTED_BODY);

        TesserError.APIError error =
                failureOf(
                        detectingSigner().signCreateWallet(ethereumParams()),
                        TesserError.APIError.class);

        assertThat(error.status()).isEqualTo(403);
        assertThat(requestedHosts()).containsExactly(PRODUCTION_HOST);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502})
    void productionRateLimitOrServerErrorDoesNotFallBack(int status) {
        respond(PRODUCTION_HOST, status, "{}");

        TesserError.APIError error =
                failureOf(
                        detectingSigner().signCreateWallet(ethereumParams()),
                        TesserError.APIError.class);

        assertThat(error.status()).isEqualTo(status);
        assertThat(requestedHosts()).containsExactly(PRODUCTION_HOST);
    }

    @Test
    void productionTimeoutDoesNotFallBack() {
        responsesByHost.put(
                PRODUCTION_HOST,
                request -> CompletableFuture.failedFuture(new HttpTimeoutException("timed out")));

        failureOf(
                detectingSigner().signCreateWallet(ethereumParams()),
                TesserError.TimeoutError.class);

        assertThat(requestedHosts()).containsExactly(PRODUCTION_HOST);
    }

    @Test
    void productionConnectionFailureDoesNotFallBack() {
        responsesByHost.put(
                PRODUCTION_HOST,
                request -> CompletableFuture.failedFuture(new IOException("connection reset")));

        failureOf(
                detectingSigner().signCreateWallet(ethereumParams()),
                TesserError.ConnectionError.class);

        assertThat(requestedHosts()).containsExactly(PRODUCTION_HOST);
    }

    @Test
    void productionBodyThatDoesNotMatchIsRejectedWithoutFallbackAndIsNotRemembered() {
        String otherOrganizationBody =
                UnsignedPayloadFixtures.loadEthereumBody()
                        .replace(UnsignedPayloadFixtures.organizationId(), "other-org");
        respond(
                PRODUCTION_HOST,
                200,
                UnsignedPayloadFixtures.responseWithBody(otherOrganizationBody));
        respondWithEthereumFixture(SANDBOX_HOST);
        LocalSigner signer = detectingSigner();

        failureOf(signer.signCreateWallet(ethereumParams()), TesserError.SigningError.class);
        failureOf(signer.signCreateWallet(ethereumParams()), TesserError.SigningError.class);

        assertThat(requestedHosts()).containsExactly(PRODUCTION_HOST, PRODUCTION_HOST);
    }

    @Test
    void neitherEnvironmentAcceptingTheKeyFailsWithProductionsErrorAndRemembersNothing() {
        respond(
                PRODUCTION_HOST,
                401,
                SIGNING_KEY_NOT_ACCEPTED_BODY,
                Map.of("zp-rid", List.of("production-request")));
        respond(
                SANDBOX_HOST,
                401,
                SIGNING_KEY_NOT_ACCEPTED_BODY,
                Map.of("zp-rid", List.of("sandbox-request")));
        LocalSigner signer = detectingSigner();

        TesserError.APIError error =
                failureOf(signer.signCreateWallet(ethereumParams()), TesserError.APIError.class);
        failureOf(signer.signCreateWallet(ethereumParams()), TesserError.APIError.class);

        assertThat(error.requestId()).isEqualTo("production-request");
        assertThat(error.headers()).containsEntry("zp-rid", List.of("production-request"));
        assertThat(error.getSuppressed()).hasSize(1);
        assertThat(((TesserError.APIError) error.getSuppressed()[0]).requestId())
                .isEqualTo("sandbox-request");
        assertThat(requestedHosts())
                .containsExactly(PRODUCTION_HOST, SANDBOX_HOST, PRODUCTION_HOST, SANDBOX_HOST);
    }

    @Test
    void sandboxFailingOtherwiseAfterTheFallbackFailsWithSandboxsError() {
        rejectSigningKey(PRODUCTION_HOST);
        respond(SANDBOX_HOST, 503, "Service Unavailable");

        TesserError.APIError error =
                failureOf(
                        detectingSigner().signCreateWallet(ethereumParams()),
                        TesserError.APIError.class);

        assertThat(error.status()).isEqualTo(503);
        assertThat(error.getSuppressed()).hasSize(1);
        assertThat(((TesserError.APIError) error.getSuppressed()[0]).status()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.tesser.xyz", "https://api-sandbox.tesser.xyz/v1"})
    void explicitApiBaseUrlIsTheOnlyHostCalledEvenWhenItRejectsTheKey(String apiBaseUrl) {
        rejectSigningKey(PRODUCTION_HOST);
        rejectSigningKey(SANDBOX_HOST);
        LocalSigner signer = new LocalSigner(CFG, apiBaseUrl, httpClient, countingStamp);

        failureOf(signer.signCreateWallet(ethereumParams()), TesserError.APIError.class);

        assertThat(httpClient.requests()).hasSize(1);
    }

    @Test
    void rememberedEnvironmentRejectingTheKeyLaterDoesNotFallBack() throws Exception {
        rejectSigningKey(PRODUCTION_HOST);
        respondWithEthereumFixture(SANDBOX_HOST);
        LocalSigner signer = detectingSigner();
        signer.signCreateWallet(ethereumParams()).get();
        rejectSigningKey(SANDBOX_HOST);
        respondWithEthereumFixture(PRODUCTION_HOST);

        TesserError.APIError error =
                failureOf(signer.signCreateWallet(ethereumParams()), TesserError.APIError.class);

        assertThat(error.status()).isEqualTo(401);
        assertThat(requestedHosts()).containsExactly(PRODUCTION_HOST, SANDBOX_HOST, SANDBOX_HOST);
    }

    @Test
    void concurrentFirstCallsBothSucceedAndRememberSandbox() throws Exception {
        rejectSigningKey(PRODUCTION_HOST);
        List<CompletableFuture<HttpResponse<String>>> pendingSandboxResponses = new ArrayList<>();
        responsesByHost.put(
                SANDBOX_HOST,
                request -> {
                    CompletableFuture<HttpResponse<String>> response = new CompletableFuture<>();
                    pendingSandboxResponses.add(response);
                    return response;
                });
        LocalSigner signer = detectingSigner();

        CompletableFuture<SignedResult> first = signer.signCreateWallet(ethereumParams());
        CompletableFuture<SignedResult> second = signer.signCreateWallet(ethereumParams());
        respondWithEthereumFixture(SANDBOX_HOST);
        pendingSandboxResponses.forEach(
                response ->
                        response.complete(
                                new StubHttpResponse(
                                        200, UnsignedPayloadFixtures.loadEthereumResponse())));

        assertThat(first.get().signature()).isNotBlank();
        assertThat(second.get().signature()).isNotBlank();
        signer.signCreateWallet(ethereumParams()).get();
        assertThat(requestedHosts())
                .containsExactly(
                        PRODUCTION_HOST, SANDBOX_HOST, PRODUCTION_HOST, SANDBOX_HOST, SANDBOX_HOST);
    }

    @Test
    void walletTypesSignedLocallyMakeNoRequest() throws Exception {
        LocalSigner signer = detectingSigner();

        signer.signCreateWallet(new CreateWalletParams("sol", WalletType.STABLECOIN_SOLANA)).get();

        assertThat(httpClient.requests()).isEmpty();
    }
}
