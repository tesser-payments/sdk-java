package xyz.tesser.sdk.java.internal.signing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import xyz.tesser.sdk.java.CreateWalletParams;
import xyz.tesser.sdk.java.SigningConfig;
import xyz.tesser.sdk.java.error.TesserError;
import xyz.tesser.sdk.java.error.TesserErrorDetail;
import xyz.tesser.sdk.java.internal.util.Json;
import xyz.tesser.sdk.java.internal.util.Logging;
import xyz.tesser.sdk.java.internal.util.SdkVersion;

public final class UnsignedPayload {

    private static final String UNSIGNED_PAYLOAD_PATH = "/v1/accounts/wallets/unsigned-payload";
    private static final String CREATE_WALLET_ACCOUNTS_ACTIVITY_TYPE =
            "ACTIVITY_TYPE_CREATE_WALLET_ACCOUNTS";
    private static final String ETHEREUM_ADDRESS_FORMAT = "ADDRESS_FORMAT_ETHEREUM";

    private static final String SIGNING_KEY_NOT_ACCEPTED_ERROR_CODE = "signing-4010";
    private static final int UNAUTHORIZED_STATUS = 401;

    private static final Logger LOG = Logging.logger("xyz.tesser.sdk.java.LocalSigner");

    private UnsignedPayload() {}

    private record UnsignedPayloadQuery(
            String organizationId, String name, String type, String timestampMs) {}

    private record UnsignedPayloadFetch(
            HttpClient httpClient,
            SigningConfig signing,
            CreateWalletParams params,
            Stamp stamp,
            LongSupplier clock) {}

    public static CompletableFuture<String> fetchCheckedCreateWalletAccountsBody(
            TesserApiConnection api,
            SigningConfig signing,
            CreateWalletParams params,
            Stamp stamp,
            LongSupplier clock) {
        UnsignedPayloadFetch fetch =
                new UnsignedPayloadFetch(api.httpClient(), signing, params, stamp, clock);
        String resolvedBaseUrl = api.resolvedBaseUrl();
        if (resolvedBaseUrl != null) {
            return fetchCheckedBodyFrom(resolvedBaseUrl, fetch);
        }
        return detectEnvironmentAndFetchCheckedBody(api, fetch, 0, null);
    }

    private static CompletableFuture<String> detectEnvironmentAndFetchCheckedBody(
            TesserApiConnection api,
            UnsignedPayloadFetch fetch,
            int candidateIndex,
            Throwable firstRejection) {
        String baseUrl = api.candidateBaseUrls().get(candidateIndex);
        return attemptCheckedBodyFrom(baseUrl, fetch)
                .thenApply(body -> rememberApiBaseUrl(api, baseUrl, body))
                .exceptionallyCompose(
                        failure ->
                                continueDetectionAfterFailure(
                                        api,
                                        fetch,
                                        candidateIndex,
                                        firstRejection,
                                        unwrapCompletionFailure(failure)));
    }

    private static CompletableFuture<String> attemptCheckedBodyFrom(
            String baseUrl, UnsignedPayloadFetch fetch) {
        try {
            return fetchCheckedBodyFrom(baseUrl, fetch);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static String rememberApiBaseUrl(TesserApiConnection api, String baseUrl, String body) {
        api.remember(baseUrl);
        LOG.debug("Using Tesser API {} for this signer", baseUrl);
        return body;
    }

    private static CompletableFuture<String> continueDetectionAfterFailure(
            TesserApiConnection api,
            UnsignedPayloadFetch fetch,
            int candidateIndex,
            Throwable firstRejection,
            Throwable failure) {
        boolean signingKeyNotAccepted = isSigningKeyNotAccepted(failure);
        int nextCandidateIndex = candidateIndex + 1;
        if (signingKeyNotAccepted && hasCandidate(api, nextCandidateIndex)) {
            LOG.debug(
                    "Tesser API {} did not accept the signing key; trying {}",
                    api.candidateBaseUrls().get(candidateIndex),
                    api.candidateBaseUrls().get(nextCandidateIndex));
            return detectEnvironmentAndFetchCheckedBody(
                    api, fetch, nextCandidateIndex, firstRejectionOr(firstRejection, failure));
        }
        return CompletableFuture.failedFuture(
                new CompletionException(
                        selectDetectionFailure(firstRejection, failure, signingKeyNotAccepted)));
    }

    private static boolean hasCandidate(TesserApiConnection api, int candidateIndex) {
        return candidateIndex < api.candidateBaseUrls().size();
    }

    private static Throwable firstRejectionOr(Throwable firstRejection, Throwable failure) {
        if (firstRejection != null) {
            return firstRejection;
        }
        return failure;
    }

    private static Throwable selectDetectionFailure(
            Throwable firstRejection, Throwable failure, boolean signingKeyNotAccepted) {
        if (firstRejection == null) {
            return failure;
        }
        if (signingKeyNotAccepted) {
            firstRejection.addSuppressed(failure);
            return firstRejection;
        }
        failure.addSuppressed(firstRejection);
        return failure;
    }

    private static boolean isSigningKeyNotAccepted(Throwable failure) {
        if (!(failure instanceof TesserError.APIError apiError)) {
            return false;
        }
        return apiError.status() == UNAUTHORIZED_STATUS
                && apiError.hasCode(SIGNING_KEY_NOT_ACCEPTED_ERROR_CODE);
    }

    private static CompletableFuture<String> fetchCheckedBodyFrom(
            String baseUrl, UnsignedPayloadFetch fetch) {
        UnsignedPayloadQuery query =
                buildUnsignedPayloadQuery(fetch.signing(), fetch.params(), fetch.clock());
        return fetch.stamp()
                .stamp(fetch.signing(), buildCanonicalUnsignedPayloadMessage(query))
                .thenCompose(
                        stamped ->
                                sendUnsignedPayloadRequest(
                                        fetch.httpClient(),
                                        buildUnsignedPayloadRequest(baseUrl, query, stamped)))
                .thenApply(UnsignedPayload::parseUnsignedPayloadBody)
                .thenApply(
                        body ->
                                assertUnsignedPayloadMatchesRequest(
                                        body, fetch.signing(), fetch.params()));
    }

    private static UnsignedPayloadQuery buildUnsignedPayloadQuery(
            SigningConfig signing, CreateWalletParams params, LongSupplier clock) {
        return new UnsignedPayloadQuery(
                signing.enclaveId(),
                params.name(),
                params.type().wireValue(),
                Long.toString(clock.getAsLong()));
    }

    private static String buildCanonicalUnsignedPayloadMessage(UnsignedPayloadQuery query) {
        ObjectNode message = Json.newObject();
        message.put("organizationId", query.organizationId());
        message.put("name", query.name());
        message.put("type", query.type());
        message.put("timestampMs", query.timestampMs());
        return Json.write(message);
    }

    private static HttpRequest buildUnsignedPayloadRequest(
            String baseUrl, UnsignedPayloadQuery query, StampResult stamped) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + UNSIGNED_PAYLOAD_PATH + "?" + buildQueryString(query)))
                .timeout(TesserApiConnection.REQUEST_TIMEOUT)
                .header(stamped.stampHeaderName(), stamped.stampHeaderValue())
                .header("User-Agent", "tesser-sdk-java/" + SdkVersion.VALUE)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private static String buildQueryString(UnsignedPayloadQuery query) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("organizationId", query.organizationId());
        parameters.put("name", query.name());
        parameters.put("type", query.type());
        parameters.put("timestampMs", query.timestampMs());
        return parameters.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + encodeQueryValue(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static CompletableFuture<HttpResponse<String>> sendUnsignedPayloadRequest(
            HttpClient httpClient, HttpRequest request) {
        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle(UnsignedPayload::requireUnsignedPayloadResponse);
    }

    private static HttpResponse<String> requireUnsignedPayloadResponse(
            HttpResponse<String> response, Throwable failure) {
        if (failure == null) {
            return response;
        }
        throw translateTransportFailure(unwrapCompletionFailure(failure));
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static RuntimeException translateTransportFailure(Throwable failure) {
        if (failure instanceof HttpTimeoutException) {
            return new TesserError.TimeoutError(
                    "Unsigned payload request timed out: " + describeTransportFailure(failure),
                    failure);
        }
        if (failure instanceof IOException) {
            return new TesserError.ConnectionError(
                    "Unsigned payload request failed: " + describeTransportFailure(failure),
                    failure);
        }
        return new CompletionException(failure);
    }

    private static String describeTransportFailure(Throwable failure) {
        Throwable current = failure;
        Throwable root = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
            root = current;
            current = current.getCause();
        }
        return root.getClass().getSimpleName();
    }

    private static String parseUnsignedPayloadBody(HttpResponse<String> response) {
        if (!isSuccessStatus(response.statusCode())) {
            throw buildUnsignedPayloadApiError(response);
        }
        JsonNode body = readUnsignedPayloadResponse(response).path("data").path("body");
        if (!body.isTextual()) {
            throw buildInvalidUnsignedPayloadResponseError(response, "data.body is missing", null);
        }
        return body.asText();
    }

    private static boolean isSuccessStatus(int status) {
        return status >= 200 && status <= 299;
    }

    private static JsonNode readUnsignedPayloadResponse(HttpResponse<String> response) {
        try {
            return Json.readTree(response.body());
        } catch (IllegalArgumentException e) {
            throw buildInvalidUnsignedPayloadResponseError(response, e.getMessage(), e);
        }
    }

    private static TesserError.APIError buildInvalidUnsignedPayloadResponseError(
            HttpResponse<String> response, String reason, Throwable cause) {
        return new TesserError.APIError(
                "Unsigned payload response is not valid: " + reason,
                response.statusCode(),
                response.headers().map(),
                readGatewayRequestId(response),
                List.of(),
                cause);
    }

    private static TesserError.APIError buildUnsignedPayloadApiError(
            HttpResponse<String> response) {
        return new TesserError.APIError(
                "Unsigned payload request returned HTTP "
                        + response.statusCode()
                        + ": "
                        + response.body(),
                response.statusCode(),
                response.headers().map(),
                readGatewayRequestId(response),
                parseErrorDetails(response.body()),
                null);
    }

    private static String readGatewayRequestId(HttpResponse<String> response) {
        return response.headers()
                .firstValue("zp-rid")
                .or(() -> response.headers().firstValue("x-request-id"))
                .orElse(null);
    }

    private static List<TesserErrorDetail> parseErrorDetails(String responseBody) {
        JsonNode errors = readErrorEnvelope(responseBody).path("errors");
        if (!errors.isArray()) {
            return List.of();
        }
        List<TesserErrorDetail> details = new ArrayList<>();
        for (JsonNode entry : errors) {
            if (isErrorDetail(entry)) {
                details.add(toErrorDetail(entry));
            }
        }
        return details;
    }

    private static JsonNode readErrorEnvelope(String responseBody) {
        try {
            return Json.readTree(responseBody);
        } catch (IllegalArgumentException e) {
            return Json.newObject();
        }
    }

    private static boolean isErrorDetail(JsonNode entry) {
        return entry.path("error_code").isTextual() && entry.path("error_message").isTextual();
    }

    private static TesserErrorDetail toErrorDetail(JsonNode entry) {
        return new TesserErrorDetail(
                entry.get("error_code").asText(),
                entry.get("error_message").asText(),
                readString(entry, "ui_message"));
    }

    private static String assertUnsignedPayloadMatchesRequest(
            String body, SigningConfig signing, CreateWalletParams params) {
        JsonNode payload = parseUnsignedPayloadJson(body);
        if (!CREATE_WALLET_ACCOUNTS_ACTIVITY_TYPE.equals(readString(payload, "type"))) {
            throw rejectUnsignedPayload("type is not " + CREATE_WALLET_ACCOUNTS_ACTIVITY_TYPE);
        }
        if (!signing.enclaveId().equals(readString(payload, "organizationId"))) {
            throw rejectUnsignedPayload("organizationId does not match the signing enclaveId");
        }
        JsonNode account = readSingleAccount(payload);
        if (!params.name().equals(readString(account, "name"))) {
            throw rejectUnsignedPayload("account name does not match the requested wallet name");
        }
        if (!ETHEREUM_ADDRESS_FORMAT.equals(readString(account, "addressFormat"))) {
            throw rejectUnsignedPayload("account addressFormat is not " + ETHEREUM_ADDRESS_FORMAT);
        }
        return body;
    }

    private static JsonNode parseUnsignedPayloadJson(String body) {
        JsonNode payload = readUnsignedPayloadJson(body);
        if (!payload.isObject()) {
            throw rejectUnsignedPayload("body is not a JSON object");
        }
        return payload;
    }

    private static JsonNode readUnsignedPayloadJson(String body) {
        try {
            return Json.readTree(body);
        } catch (IllegalArgumentException e) {
            throw rejectUnsignedPayload("body is not a JSON object");
        }
    }

    private static JsonNode readSingleAccount(JsonNode payload) {
        JsonNode parameters = payload.path("parameters");
        if (!parameters.isObject()) {
            throw rejectUnsignedPayload("parameters is missing");
        }
        JsonNode accounts = parameters.path("accounts");
        if (!accounts.isArray()) {
            throw rejectUnsignedPayload("parameters.accounts is missing");
        }
        if (accounts.size() != 1) {
            throw rejectUnsignedPayload("expected exactly one account, got " + accounts.size());
        }
        JsonNode account = accounts.get(0);
        if (!account.isObject()) {
            throw rejectUnsignedPayload("account is not a JSON object");
        }
        return account;
    }

    private static String readString(JsonNode source, String key) {
        JsonNode value = source.path(key);
        if (!value.isTextual()) {
            return null;
        }
        return value.asText();
    }

    private static TesserError.SigningError rejectUnsignedPayload(String reason) {
        return new TesserError.SigningError("Unsigned payload rejected: " + reason);
    }
}
