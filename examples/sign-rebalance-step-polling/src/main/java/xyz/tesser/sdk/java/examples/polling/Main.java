package xyz.tesser.sdk.java.examples.polling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import xyz.tesser.sdk.java.LocalSigner;
import xyz.tesser.sdk.java.SignedStepResult;
import xyz.tesser.sdk.java.SigningConfig;
import xyz.tesser.sdk.java.StepForSigning;

/**
 * End-to-end harness for {@code LocalSigner.signStep} against the Tesser rebalance flow —
 * <b>polling variant</b>. Use this when webhook delivery is unreliable; the example simply polls
 * {@code GET /v1/treasury/rebalances/{id}} for state transitions.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Authenticate via OAuth {@code client_credentials} against {@code AUTH_TOKEN_URL}.
 *   <li>POST {@code /v1/treasury/rebalances} with the rebalance request body built from env. Read
 *       the rebalance id from the response.
 *   <li>Poll the rebalance until the first step's {@code status} is {@code signature_requested} and
 *       {@code unsigned_transaction} is populated.
 *   <li>Sign the step locally with {@code LocalSigner.signStep}.
 *   <li>POST the signature to {@code /v1/treasury/rebalances/{id}/steps/{stepId}/sign}.
 *   <li>Poll the rebalance until the step's {@code status} is {@code completed} (or fail loudly if
 *       any step reports {@code failed_at}).
 *   <li>Print the final step summary and shut down.
 * </ol>
 *
 * <pre>
 * cp .env.example .env.local &amp;&amp; $EDITOR .env.local
 * set -a &amp;&amp; source .env.local &amp;&amp; set +a
 * ./gradlew :examples:sign-rebalance-step-polling:run
 * </pre>
 *
 * <p>No tunnel, no webhook subscription, no public URL — everything is driven by the polling loop.
 */
public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private Main() {}

    public static void main(String[] args) throws Exception {
        ExampleConfig config = ExampleConfig.fromEnv();
        LocalSigner signer = new LocalSigner(config.signing());
        System.out.println("Signer ready for enclave=" + signer.signing().enclaveId());

        String token = fetchAccessToken(config);
        String rebalanceId = createRebalance(config, token);

        System.out.println(
                "Polling rebalance " + rebalanceId + " until step is `signature_requested` ...");
        JsonNode readyStep =
                pollUntilSignatureRequested(config.tesserBaseUrl(), token, rebalanceId);
        StepForSigning step = buildStepForSigning(config, token, readyStep);

        System.out.printf(
                "Signing step %s for transfer %s (signWith=%s, network=%s) ...%n",
                step.id(), step.transferId(), step.signWith(), step.network());
        SignedStepResult signed = signer.signStep(step).join();
        System.out.println("Local signature produced (" + signed.signature().length() + " chars)");

        submitSignature(config, token, step, signed);

        System.out.println("Polling rebalance until the step's status is `completed` ...");
        JsonNode finalStep =
                pollUntilStepCompleted(config.tesserBaseUrl(), token, rebalanceId, step.id());
        printFinalStep(finalStep);
    }

    // =========================================================================
    // Config
    // =========================================================================

    private record ExampleConfig(
            String tesserBaseUrl,
            String authTokenUrl,
            String audience,
            String clientId,
            String clientSecret,
            SigningConfig signing,
            RebalanceParams rebalance) {

        static ExampleConfig fromEnv() {
            String baseUrl = requireEnv("API_BASE_URL");
            return new ExampleConfig(
                    baseUrl,
                    requireEnv("AUTH_TOKEN_URL"),
                    optionalEnv("API_AUDIENCE", baseUrl),
                    requireEnv("API_CLIENT_ID"),
                    requireEnv("API_CLIENT_SECRET"),
                    new SigningConfig(
                            requireEnv("SIGNING_PUBLIC_KEY"),
                            requireEnv("SIGNING_PRIVATE_KEY"),
                            requireEnv("SIGNING_ENCLAVE_ID")),
                    RebalanceParams.fromEnv());
        }
    }

    private record RebalanceParams(
            String fromAccountId,
            String fromAmount,
            String fromCurrency,
            String fromNetwork,
            String toAccountId,
            String toCurrency,
            String toNetwork) {

        static RebalanceParams fromEnv() {
            return new RebalanceParams(
                    requireEnv("FROM_ACCOUNT_ID"),
                    optionalEnv("FROM_AMOUNT", "0.000001"),
                    optionalEnv("FROM_CURRENCY", "USDC"),
                    optionalEnv("FROM_NETWORK", "BASE_SEPOLIA"),
                    requireEnv("TO_ACCOUNT_ID"),
                    optionalEnv("TO_CURRENCY", "USDC"),
                    optionalEnv("TO_NETWORK", "BASE_SEPOLIA"));
        }
    }

    // =========================================================================
    // Pipeline steps
    // =========================================================================

    private static String fetchAccessToken(ExampleConfig config) throws Exception {
        System.out.printf(
                "Fetching access token from %s (audience=%s) ...%n",
                config.authTokenUrl(), config.audience());
        return fetchToken(
                config.authTokenUrl(), config.clientId(), config.clientSecret(), config.audience());
    }

    private static String createRebalance(ExampleConfig config, String token) throws Exception {
        RebalanceParams p = config.rebalance();
        ObjectNode root = JSON.createObjectNode();
        ObjectNode desired = root.putObject("desired");
        ObjectNode from = desired.putObject("from");
        from.put("account_id", p.fromAccountId());
        from.put("amount", p.fromAmount());
        from.put("currency", p.fromCurrency());
        from.put("network", p.fromNetwork());
        ObjectNode to = desired.putObject("to");
        to.put("account_id", p.toAccountId());
        to.put("currency", p.toCurrency());
        to.put("network", p.toNetwork());
        String body = root.toString();

        System.out.println(
                "Creating rebalance: POST " + config.tesserBaseUrl() + "/v1/treasury/rebalances");
        System.out.println("Request payload: " + body);
        String response = postJson(config.tesserBaseUrl() + "/v1/treasury/rebalances", token, body);
        String rebalanceId =
                dataField(response, "id", "Rebalance response missing `data.id`: " + response);
        System.out.println("Rebalance created: id=" + rebalanceId);
        return rebalanceId;
    }

    private static StepForSigning buildStepForSigning(
            ExampleConfig config, String token, JsonNode stepDto) throws Exception {
        // Use the user-facing account/network we sent on the rebalance request,
        // not the step DTO's `from_account_id` / `from_network`. The step's
        // `from_account_id` is Tesser's internal wallet-account id which doesn't
        // resolve via `GET /v1/accounts/{id}`.
        String fromAccountId = config.rebalance().fromAccountId();
        String network = config.rebalance().fromNetwork();
        String signWith = fetchCryptoWalletAddress(config.tesserBaseUrl(), token, fromAccountId);
        return new StepForSigning(
                requireString(stepDto, "id"),
                // The GET response uses `transfer_id` for the parent UUID; the
                // webhook step DTO calls the same value `rebalance_id`. This
                // code is fed by the GET, so read `transfer_id`.
                requireString(stepDto, "transfer_id"),
                requireString(stepDto, "unsigned_transaction"),
                signWith,
                network);
    }

    private static String fetchCryptoWalletAddress(String baseUrl, String token, String accountId)
            throws Exception {
        String response = getJson(baseUrl + "/v1/accounts/" + accountId, token);
        return dataField(
                response,
                "crypto_wallet_address",
                "Account " + accountId + " has no crypto_wallet_address: " + response);
    }

    private static String submitSignature(
            ExampleConfig config, String token, StepForSigning step, SignedStepResult signed)
            throws Exception {
        String url =
                config.tesserBaseUrl()
                        + "/v1/treasury/rebalances/"
                        + step.transferId()
                        + "/steps/"
                        + step.id()
                        + "/sign";
        System.out.println("Submitting signature: POST " + url);
        ObjectNode payload = JSON.createObjectNode();
        payload.put("signature", signed.signature());
        String response = postJson(url, token, payload.toString());
        System.out.println("Step submitted. API response: " + response);
        return response;
    }

    private static void printFinalStep(JsonNode stepDto) {
        System.out.printf(
                "Rebalance complete. step.id=%s status=%s completed_at=%s%n",
                stepDto.path("id").asText(null),
                stepDto.path("status").asText(null),
                stepDto.path("completed_at").asText(null));
    }

    // =========================================================================
    // Polling helpers
    //
    // The Kotlin original wraps each loop in `withTimeout` and sleeps with
    // `delay`. Here the deadline is computed once with System.nanoTime() and
    // checked at the top of each iteration, and `delay` becomes Thread.sleep --
    // this is a plain blocking program with no coroutine scope to cancel.
    // =========================================================================

    /**
     * Polls the rebalance until the first step is in {@code signature_requested} status with a
     * populated {@code unsigned_transaction}. Returns the step DTO. Throws on timeout or if any
     * step reports {@code failed_at} before reaching the ready state.
     */
    private static JsonNode pollUntilSignatureRequested(
            String baseUrl, String token, String rebalanceId) throws Exception {
        Duration timeout = Duration.ofMinutes(2);
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        String lastReportedStatus = null;
        boolean reportedNoStepsYet = false;

        while (true) {
            JsonNode rebalance = getRebalanceData(baseUrl, token, rebalanceId);
            JsonNode steps = rebalance.path("steps");
            if (!steps.isArray() || steps.isEmpty()) {
                // A freshly-created rebalance can return an empty `steps` array
                // for the first poll or two while planning finishes. That is the
                // exact race a polling loop exists to absorb, so wait rather than
                // treating it as terminal; the deadline below still bounds it.
                if (!reportedNoStepsYet) {
                    System.out.println("  no steps yet; waiting for the rebalance to be planned");
                    reportedNoStepsYet = true;
                }
                sleepUntilNextPoll(deadlineNanos, timeout, "the rebalance to produce a step");
                continue;
            }
            JsonNode step = steps.get(0);
            String status = step.path("status").asText(null);
            String unsignedTx = step.path("unsigned_transaction").asText(null);
            String failedAt = step.path("failed_at").asText(null);

            if (!Objects.equals(status, lastReportedStatus)) {
                System.out.println(
                        "  step status="
                                + status
                                + " (unsigned_transaction="
                                + (unsignedTx == null || unsignedTx.isBlank() ? "null" : "present")
                                + ")");
                lastReportedStatus = status;
            }
            if (failedAt != null) {
                throw new IllegalStateException(
                        "Step "
                                + step.path("id").asText(null)
                                + " failed_at="
                                + failedAt
                                + " status_reasons="
                                + statusReasons(step));
            }
            if ("signature_requested".equals(status)
                    && unsignedTx != null
                    && !unsignedTx.isBlank()) {
                return step;
            }
            sleepUntilNextPoll(deadlineNanos, timeout, "step to reach `signature_requested`");
        }
    }

    /**
     * Polls the rebalance until the step matching {@code stepId} has {@code status == "completed"}.
     * Throws on timeout or if the step reports {@code failed_at}.
     */
    private static JsonNode pollUntilStepCompleted(
            String baseUrl, String token, String rebalanceId, String stepId) throws Exception {
        Duration timeout = Duration.ofMinutes(5);
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        String lastReportedStatus = null;

        while (true) {
            JsonNode rebalance = getRebalanceData(baseUrl, token, rebalanceId);
            JsonNode steps = rebalance.path("steps");
            if (!steps.isArray()) {
                throw new IllegalStateException(
                        "Rebalance response missing `data.steps`: " + rebalance);
            }
            JsonNode step = null;
            for (JsonNode candidate : steps) {
                if (stepId.equals(candidate.path("id").asText(null))) {
                    step = candidate;
                    break;
                }
            }
            if (step == null) {
                throw new IllegalStateException(
                        "Rebalance has no step with id " + stepId + ": " + rebalance);
            }

            String status = step.path("status").asText(null);
            String completedAt = step.path("completed_at").asText(null);
            String failedAt = step.path("failed_at").asText(null);

            if (!Objects.equals(status, lastReportedStatus)) {
                System.out.println(
                        "  step status="
                                + status
                                + " completed_at="
                                + completedAt
                                + " failed_at="
                                + failedAt);
                lastReportedStatus = status;
            }
            if (failedAt != null) {
                throw new IllegalStateException(
                        "Step "
                                + stepId
                                + " failed_at="
                                + failedAt
                                + " status_reasons="
                                + statusReasons(step));
            }
            if ("completed".equals(status)) {
                return step;
            }
            sleepUntilNextPoll(deadlineNanos, timeout, "step " + stepId + " to reach `completed`");
        }
    }

    /**
     * Sleeps one poll interval, or throws if the deadline has passed. Checking before sleeping
     * means the timeout is honoured even when the interval is longer than the time remaining.
     */
    private static void sleepUntilNextPoll(long deadlineNanos, Duration timeout, String waitingFor)
            throws InterruptedException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new IllegalStateException(
                    "Timed out after " + timeout + " waiting for " + waitingFor);
        }
        Thread.sleep(Math.min(POLL_INTERVAL.toMillis(), Math.max(1, remaining / 1_000_000)));
    }

    private static String statusReasons(JsonNode step) {
        JsonNode reasons = step.path("status_reasons");
        return reasons.isMissingNode() ? "[]" : reasons.toString();
    }

    private static JsonNode getRebalanceData(String baseUrl, String token, String rebalanceId)
            throws Exception {
        String response = getJson(baseUrl + "/v1/treasury/rebalances/" + rebalanceId, token);
        JsonNode data = JSON.readTree(response).path("data");
        if (!data.isObject()) {
            throw new IllegalStateException("Rebalance GET missing `data` envelope: " + response);
        }
        return data;
    }

    // =========================================================================
    // JSON / env helpers
    // =========================================================================

    /** Pull a required string field out of {@code data.<field>} of a Tesser API response body. */
    private static String dataField(String responseBody, String field, String onMissing)
            throws IOException {
        JsonNode value = JSON.readTree(responseBody).path("data").path(field);
        if (!value.isTextual()) {
            throw new IllegalStateException(onMissing);
        }
        return value.asText();
    }

    /** Pull a required string field directly out of a JSON object (no {@code data} envelope). */
    private static String requireString(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            throw new IllegalStateException("Missing required field `" + field + "` in: " + node);
        }
        return value.asText();
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    private static String optionalEnv(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    // =========================================================================
    // HTTP helpers
    // =========================================================================

    private static String fetchToken(
            String authTokenUrl, String clientId, String clientSecret, String audience)
            throws Exception {
        String form =
                "grant_type=client_credentials"
                        + "&client_id="
                        + urlEncode(clientId)
                        + "&client_secret="
                        + urlEncode(clientSecret)
                        + "&audience="
                        + urlEncode(audience);

        HttpRequest request =
                HttpRequest.newBuilder(URI.create(authTokenUrl))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();

        HttpResponse<String> resp =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() > 299) {
            throw new IllegalStateException(
                    "OAuth token exchange failed: " + resp.statusCode() + " " + resp.body());
        }
        // Parsed, not regexed: a regex over the raw body mis-handles any escape
        // sequence inside the token and can match the literal text "access_token"
        // somewhere else in the response.
        JsonNode token = JSON.readTree(resp.body()).path("access_token");
        if (!token.isTextual() || token.asText().isBlank()) {
            throw new IllegalStateException(
                    "OAuth response did not contain access_token: " + resp.body());
        }
        return token.asText();
    }

    private static String getJson(String url, String bearer) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + bearer)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
        HttpResponse<String> resp =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() > 299) {
            throw new IllegalStateException(
                    "GET " + url + " failed: " + resp.statusCode() + " " + resp.body());
        }
        return resp.body();
    }

    private static String postJson(String url, String bearer, String body) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        HttpResponse<String> resp =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() > 299) {
            throw new IllegalStateException(
                    "POST " + url + " failed: " + resp.statusCode() + " " + resp.body());
        }
        return resp.body();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
