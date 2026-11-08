package xyz.tesser.sdk.java.internal.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import xyz.tesser.sdk.java.CreateWalletParams;
import xyz.tesser.sdk.java.SignedResult;
import xyz.tesser.sdk.java.SigningConfig;
import xyz.tesser.sdk.java.WalletType;
import xyz.tesser.sdk.java.error.TesserError;
import xyz.tesser.sdk.java.internal.util.Json;

class UnsignedPayloadTest {

    private static final long FIXED_TS = 1_700_000_000_000L;

    private final SigningConfig cfg =
            new SigningConfig(
                    "02".repeat(33), "01".repeat(32), UnsignedPayloadFixtures.organizationId());

    private final CreateWalletParams ethereumParams =
            new CreateWalletParams(
                    UnsignedPayloadFixtures.walletName(), WalletType.STABLECOIN_ETHEREUM);

    private final List<String> stampedMessages = new ArrayList<>();

    private final Stamp stamp =
            (keys, body) -> {
                stampedMessages.add(body);
                return CompletableFuture.completedFuture(new StampResult("X-Stamp", "STAMP_VALUE"));
            };

    private static TesserApiConnection connection(HttpClient httpClient) {
        return TesserApiConnection.create("https://api.tesser.xyz", httpClient);
    }

    private static StubHttpClient clientReturningEthereumFixture() {
        return StubHttpClient.respondingWith(
                new StubHttpResponse(200, UnsignedPayloadFixtures.loadEthereumResponse()));
    }

    private static StubHttpClient clientReturningBody(String body) {
        return StubHttpClient.respondingWith(
                new StubHttpResponse(200, UnsignedPayloadFixtures.responseWithBody(body)));
    }

    private SignedResult sign(CreateWalletParams params, HttpClient httpClient) throws Exception {
        return CreateWalletActivity.sign(cfg, params, stamp, () -> FIXED_TS, connection(httpClient))
                .get();
    }

    private <T extends Throwable> T signExpectingFailure(HttpClient httpClient, Class<T> type) {
        CompletableFuture<SignedResult> future =
                CreateWalletActivity.sign(
                        cfg, ethereumParams, stamp, () -> FIXED_TS, connection(httpClient));
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
    void ethereumStampsTheReturnedBodyByteForByte() throws Exception {
        SignedResult result = sign(ethereumParams, clientReturningEthereumFixture());

        String expectedBody = UnsignedPayloadFixtures.loadEthereumBody();
        assertThat(result.metadata().body()).isEqualTo(expectedBody);
        assertThat(stampedMessages).hasSize(2);
        assertThat(stampedMessages.get(1)).isEqualTo(expectedBody);
        JsonNode composite =
                Json.readTree(
                        new String(
                                Base64.getDecoder().decode(result.signature()),
                                StandardCharsets.UTF_8));
        assertThat(composite.get("body").asText()).isEqualTo(expectedBody);
        assertThat(composite.get("stamp").asText()).isEqualTo("STAMP_VALUE");
    }

    @Test
    void ethereumSendsOneStampedGetToTesser() throws Exception {
        StubHttpClient httpClient = clientReturningEthereumFixture();

        sign(ethereumParams, httpClient);

        assertThat(httpClient.requests()).hasSize(1);
        HttpRequest request = httpClient.requests().get(0);
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri().getScheme()).isEqualTo("https");
        assertThat(request.uri().getHost()).isEqualTo("api.tesser.xyz");
        assertThat(request.uri().getPath()).isEqualTo("/v1/accounts/wallets/unsigned-payload");
        assertThat(request.timeout()).contains(Duration.ofSeconds(10));
        assertThat(request.headers().firstValue("X-Stamp")).contains("STAMP_VALUE");
        assertThat(request.headers().firstValue("Accept")).contains("application/json");
        assertThat(request.headers().firstValue("User-Agent").orElseThrow())
                .matches("tesser-sdk-java/\\d+\\.\\d+\\.\\d+.*");
        Map<String, String> query = queryParameters(request);
        assertThat(query)
                .containsExactly(
                        Map.entry("organizationId", UnsignedPayloadFixtures.organizationId()),
                        Map.entry("name", UnsignedPayloadFixtures.walletName()),
                        Map.entry("type", "stablecoin_ethereum"),
                        Map.entry("timestampMs", Long.toString(FIXED_TS)));
    }

    @Test
    void ethereumStampsTheCanonicalMessageWithTheQueryValues() throws Exception {
        sign(ethereumParams, clientReturningEthereumFixture());

        assertThat(stampedMessages.get(0))
                .isEqualTo(
                        "{\"organizationId\":\""
                                + UnsignedPayloadFixtures.organizationId()
                                + "\",\"name\":\""
                                + UnsignedPayloadFixtures.walletName()
                                + "\",\"type\":\"stablecoin_ethereum\""
                                + ",\"timestampMs\":\""
                                + FIXED_TS
                                + "\"}");
    }

    @Test
    void queryValuesArePercentEncodedWithSpacesAsPercent20() throws Exception {
        String name = "my wallet+1";
        String body =
                UnsignedPayloadFixtures.loadEthereumBody()
                        .replace(UnsignedPayloadFixtures.walletName(), name);
        StubHttpClient httpClient = clientReturningBody(body);

        sign(new CreateWalletParams(name, WalletType.STABLECOIN_ETHEREUM), httpClient);

        assertThat(httpClient.requests().get(0).uri().getRawQuery())
                .contains("name=my%20wallet%2B1");
    }

    @Test
    void payloadWithAnotherActivityTypeIsRejectedBeforeStamping() {
        assertBodyRejected(
                UnsignedPayloadFixtures.loadEthereumBody()
                        .replace(
                                "ACTIVITY_TYPE_CREATE_WALLET_ACCOUNTS",
                                "ACTIVITY_TYPE_CREATE_WALLET"));
    }

    @Test
    void payloadForAnotherOrganizationIsRejectedBeforeStamping() {
        assertBodyRejected(
                UnsignedPayloadFixtures.loadEthereumBody()
                        .replace(UnsignedPayloadFixtures.organizationId(), "other-org"));
    }

    @Test
    void payloadWithAnotherAccountNameIsRejectedBeforeStamping() {
        assertBodyRejected(
                UnsignedPayloadFixtures.loadEthereumBody()
                        .replace(UnsignedPayloadFixtures.walletName(), "other-name"));
    }

    @Test
    void payloadWithANonEthereumAddressFormatIsRejectedBeforeStamping() {
        assertBodyRejected(
                UnsignedPayloadFixtures.loadEthereumBody()
                        .replace("ADDRESS_FORMAT_ETHEREUM", "ADDRESS_FORMAT_SOLANA"));
    }

    @Test
    void payloadWithTwoAccountsIsRejectedBeforeStamping() {
        String body = UnsignedPayloadFixtures.loadEthereumBody();
        String account = body.substring(body.indexOf('[') + 1, body.lastIndexOf(']'));
        assertBodyRejected(body.replace(account, account + "," + account));
    }

    @Test
    void payloadWithNoAccountsIsRejectedBeforeStamping() {
        String body = UnsignedPayloadFixtures.loadEthereumBody();
        String account = body.substring(body.indexOf('[') + 1, body.lastIndexOf(']'));
        assertBodyRejected(body.replace(account, ""));
    }

    @Test
    void payloadThatIsNotJsonIsRejectedBeforeStamping() {
        assertBodyRejected("not json");
    }

    @Test
    void payloadWithTrailingContentIsRejectedBeforeStamping() {
        assertBodyRejected(UnsignedPayloadFixtures.loadEthereumBody() + "{}");
    }

    private void assertBodyRejected(String body) {
        TesserError.SigningError error =
                signExpectingFailure(clientReturningBody(body), TesserError.SigningError.class);
        assertThat(error).hasMessageContaining("Unsigned payload rejected");
        assertThat(stampedMessages).hasSize(1);
    }

    @Test
    void non2xxResponseBecomesApiErrorWithStatusRequestIdAndErrorCodes() {
        StubHttpResponse response =
                new StubHttpResponse(
                        401,
                        "{\"errors\":[{\"error_code\":\"signing-4010\","
                                + "\"error_message\":\"Invalid stamp\"}]}",
                        Map.of("x-request-id", List.of("req_123")));

        TesserError.APIError error =
                signExpectingFailure(
                        StubHttpClient.respondingWith(response), TesserError.APIError.class);

        assertThat(error.status()).isEqualTo(401);
        assertThat(error.requestId()).isEqualTo("req_123");
        assertThat(error.errorCode()).isEqualTo("signing-4010");
        assertThat(error.errors().get(0).errorMessage()).isEqualTo("Invalid stamp");
        assertThat(error.headers()).containsKey("x-request-id");
        assertThat(error).hasMessageContaining("Invalid stamp");
        assertThat(stampedMessages).hasSize(1);
    }

    @Test
    void apiErrorRequestIdPrefersTheGatewayZpRidHeader() {
        StubHttpResponse response =
                new StubHttpResponse(
                        401,
                        "{\"errors\":[{\"error_code\":\"signing-4010\","
                                + "\"error_message\":\"Invalid stamp\"}]}",
                        Map.of(
                                "zp-rid", List.of("622a8b5e-c713-4564-894f-a160e321e6f3"),
                                "x-request-id", List.of("req_123")));

        TesserError.APIError error =
                signExpectingFailure(
                        StubHttpClient.respondingWith(response), TesserError.APIError.class);

        assertThat(error.requestId()).isEqualTo("622a8b5e-c713-4564-894f-a160e321e6f3");
    }

    @Test
    void nonJsonErrorResponseBecomesApiErrorWithoutErrorCodes() {
        TesserError.APIError error =
                signExpectingFailure(
                        StubHttpClient.respondingWith(new StubHttpResponse(502, "Bad Gateway")),
                        TesserError.APIError.class);

        assertThat(error.status()).isEqualTo(502);
        assertThat(error.errors()).isEmpty();
        assertThat(error.errorCode()).isNull();
        assertThat(error.requestId()).isNull();
    }

    @Test
    void successResponseWithoutABodyFieldBecomesApiError() {
        StubHttpResponse response =
                new StubHttpResponse(200, "{\"data\":{\"organization_id\":\"x\"}}");

        TesserError.APIError error =
                signExpectingFailure(
                        StubHttpClient.respondingWith(response), TesserError.APIError.class);

        assertThat(error.status()).isEqualTo(200);
        assertThat(stampedMessages).hasSize(1);
    }

    @Test
    void timeoutBecomesTimeoutError() {
        TesserError.TimeoutError error =
                signExpectingFailure(
                        StubHttpClient.failingWith(new HttpTimeoutException("request timed out")),
                        TesserError.TimeoutError.class);

        assertThat(error).hasMessageContaining("request timed out");
        assertThat(stampedMessages).hasSize(1);
    }

    @Test
    void transportFailureBecomesConnectionError() {
        TesserError.ConnectionError error =
                signExpectingFailure(
                        StubHttpClient.failingWith(new IOException("connection reset")),
                        TesserError.ConnectionError.class);

        assertThat(error).hasMessage("Unsigned payload request failed: connection reset");
        assertThat(stampedMessages).hasSize(1);
    }

    @Test
    void transportFailureWithoutAMessageNamesTheUnderlyingFailure() {
        ConnectException unresolvedHost = new ConnectException();
        unresolvedHost.initCause(new UnresolvedAddressException());

        TesserError.ConnectionError error =
                signExpectingFailure(
                        StubHttpClient.failingWith(unresolvedHost),
                        TesserError.ConnectionError.class);

        assertThat(error).hasMessage("Unsigned payload request failed: UnresolvedAddressException");
    }

    @Test
    void transportFailureUsesTheFirstMessageInTheCauseChain() {
        ConnectException wrapper = new ConnectException();
        wrapper.initCause(new IOException("api.example.invalid: nodename nor servname provided"));

        TesserError.ConnectionError error =
                signExpectingFailure(
                        StubHttpClient.failingWith(wrapper), TesserError.ConnectionError.class);

        assertThat(error)
                .hasMessage(
                        "Unsigned payload request failed: api.example.invalid: nodename nor"
                                + " servname provided");
    }

    @Test
    void solanaBuildsItsBodyLocallyWithoutARequestToTesser() throws Exception {
        StubHttpClient httpClient = clientReturningEthereumFixture();

        SignedResult result =
                sign(new CreateWalletParams("sol", WalletType.STABLECOIN_SOLANA), httpClient);

        assertThat(httpClient.requests()).isEmpty();
        assertThat(stampedMessages).hasSize(1);
        assertThat(result.metadata().body())
                .contains("\"type\":\"ACTIVITY_TYPE_CREATE_WALLET\"")
                .contains("ADDRESS_FORMAT_SOLANA");
    }

    @Test
    void stellarBuildsItsBodyLocallyWithoutARequestToTesser() throws Exception {
        StubHttpClient httpClient = clientReturningEthereumFixture();

        SignedResult result =
                sign(new CreateWalletParams("xlm", WalletType.STABLECOIN_STELLAR), httpClient);

        assertThat(httpClient.requests()).isEmpty();
        assertThat(result.metadata().body()).contains("ADDRESS_FORMAT_XLM");
    }

    @Test
    void ethereumRequestFailureFailsTheFutureRatherThanThrowing() {
        CompletableFuture<SignedResult> future =
                CreateWalletActivity.sign(
                        cfg,
                        ethereumParams,
                        stamp,
                        () -> FIXED_TS,
                        connection(StubHttpClient.failingWith(new IOException("down"))));

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::join).hasCauseInstanceOf(TesserError.ConnectionError.class);
    }
}
