package xyz.tesser.sdk.java.examples.webhooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import xyz.tesser.sdk.java.LocalSigner;
import xyz.tesser.sdk.java.SignedStepResult;
import xyz.tesser.sdk.java.SigningConfig;
import xyz.tesser.sdk.java.StepForSigning;

/**
 * End-to-end harness for {@code LocalSigner.signStep} against the Tesser rebalance flow.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Authenticate via OAuth {@code client_credentials} against {@code AUTH_TOKEN_URL}.
 *   <li>Start a local HTTP server on {@code WEBHOOK_PORT} to receive Tesser webhook callbacks. The
 *       handler accepts POSTs at any path so the same listener works behind cloudflared, ngrok,
 *       localhost.run, whcli, etc. without caring about path-rewriting. Register the tunnel's
 *       public URL in the Tesser dashboard subscribed to all {@code step.*} events (we filter by
 *       {@code data.object.status} rather than envelope type, so the example doesn't care which
 *       specific event type carries the terminal state).
 *   <li>POST {@code /v1/treasury/rebalances} with the rebalance request body built from env.
 *   <li>Wait for the {@code step.signature_requested} webhook event.
 *   <li>Sign the step locally with {@code LocalSigner.signStep}.
 *   <li>POST the signature to {@code /v1/treasury/rebalances/{id}/steps/{stepId}/sign}.
 *   <li>Wait for a step event carrying {@code data.object.status == "completed"}.
 *   <li>Print the final step summary (using {@code completed_at}) and shut down.
 * </ol>
 *
 * <pre>
 * cp .env.example .env.local &amp;&amp; $EDITOR .env.local
 * set -a &amp;&amp; source .env.local &amp;&amp; set +a
 * ./gradlew :examples:sign-rebalance-step-webhooks:run
 * </pre>
 *
 * <p>Webhook signature verification is intentionally not implemented yet (the verification
 * algorithm hasn't been documented in the public Tesser docs at the time of writing); never run
 * this against production until that path is added.
 */
public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern ACCESS_TOKEN =
            Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

    private Main() {}

    public static void main(String[] args) throws Exception {
        ExampleConfig config = ExampleConfig.fromEnv();
        LocalSigner signer = new LocalSigner(config.signing());
        System.out.println("Signer ready for enclave=" + signer.signing().enclaveId());

        try (WebhookListener listener = WebhookListener.start(config.webhookPort())) {
            String token = fetchAccessToken(config);
            String rebalanceId = createRebalance(config, token);

            System.out.println(
                    "Waiting for `step.signature_requested` event for rebalance "
                            + rebalanceId
                            + " ...");
            JsonNode signatureRequested =
                    listener.awaitEventOfType("step.signature_requested", Duration.ofSeconds(60));
            StepForSigning step = buildStepForSigning(config, token, signatureRequested);

            System.out.printf(
                    "Signing step %s for transfer %s (signWith=%s, network=%s) ...%n",
                    step.id(), step.transferId(), step.signWith(), step.network());
            SignedStepResult signed = signer.signStep(step).join();
            System.out.println(
                    "Local signature produced (" + signed.signature().length() + " chars)");

            submitSignature(config, token, step, signed);

            System.out.println(
                    "Waiting for step " + step.id() + " to reach `status=completed` ...");
            JsonNode completed =
                    listener.awaitEventWhere(
                            Duration.ofMinutes(5),
                            "step " + step.id() + " status=completed",
                            envelope -> {
                                JsonNode stepObject = envelope.path("data").path("object");
                                return step.id().equals(stepObject.path("id").asText(null))
                                        && "completed"
                                                .equals(stepObject.path("status").asText(null));
                            });
            printCompletedStep(completed);
        }
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
            int webhookPort,
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
                    Integer.parseInt(optionalEnv("WEBHOOK_PORT", "8787")),
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
    // Webhook listener
    // =========================================================================

    /**
     * Buffers incoming webhook envelopes onto an unbounded queue so callers can pull events by
     * {@code type} in arrival order. Unbounded so events that arrive between awaits (e.g. {@code
     * step.submitted} and {@code step.confirmed} while we're waiting for {@code step.completed})
     * aren't dropped.
     *
     * <p>The Kotlin original uses an unlimited coroutine {@code Channel} plus {@code withTimeout}.
     * {@link LinkedBlockingQueue} is the direct equivalent of the former; the latter becomes a
     * timed {@link LinkedBlockingQueue#poll} against a deadline computed once, so a non-matching
     * event does not restart the full timeout.
     */
    private static final class WebhookListener implements AutoCloseable {

        private final HttpServer server;
        private final LinkedBlockingQueue<JsonNode> events;

        private WebhookListener(HttpServer server, LinkedBlockingQueue<JsonNode> events) {
            this.server = server;
            this.events = events;
        }

        static WebhookListener start(int port) throws IOException {
            LinkedBlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            // Register at "/" so any incoming path works (whcli, ngrok, and
            // similar tunnels typically forward to the bare target URL with no
            // extra path component). HttpServer routes by longest-prefix
            // match — without other contexts, every request lands here.
            server.createContext("/", exchange -> handle(exchange, events));
            server.start();
            System.out.println(
                    "Webhook listener started on http://0.0.0.0:" + port + "/ (any path)");
            return new WebhookListener(server, events);
        }

        private static void handle(HttpExchange exchange, LinkedBlockingQueue<JsonNode> events) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            try {
                if (!"POST".equals(method)) {
                    // Reject non-POST quietly so health checks / browser visits
                    // don't crash the parser.
                    System.out.println(
                            "  webhook received: method="
                                    + method
                                    + " path="
                                    + path
                                    + " (ignored, expected POST)");
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                String body =
                        new String(
                                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                // TODO: verify the webhook signature header before trusting the payload.
                //   The exact header name and algorithm aren't currently documented in
                //   the public Tesser docs; until verification lands, this handler accepts
                //   every incoming POST. Do NOT run against production.
                JsonNode envelope = JSON.readTree(body);
                System.out.println(
                        "  webhook received: path="
                                + path
                                + " type="
                                + envelope.path("type").asText(null)
                                + " id="
                                + envelope.path("id").asText(null));
                events.offer(envelope);
                exchange.sendResponseHeaders(204, -1);
            } catch (Exception e) {
                System.out.println(
                        "Webhook handler error on " + method + " " + path + ": " + e.getMessage());
                try {
                    exchange.sendResponseHeaders(500, -1);
                } catch (IOException ignored) {
                    // The exchange is already broken; nothing useful to do.
                }
            } finally {
                exchange.close();
            }
        }

        /**
         * Takes events off the queue, discarding any whose {@code type} doesn't match. Throws if
         * the timeout elapses before a matching event arrives.
         */
        JsonNode awaitEventOfType(String type, Duration timeout) throws InterruptedException {
            return awaitEventWhere(
                    timeout,
                    "type=" + type,
                    envelope -> type.equals(envelope.path("type").asText(null)));
        }

        /**
         * Takes events off the queue, discarding any that don't satisfy {@code predicate}. Throws
         * if the timeout elapses before a matching event arrives. {@code label} is used only in
         * skip logging so the run output makes sense to someone reading along.
         */
        JsonNode awaitEventWhere(Duration timeout, String label, Predicate<JsonNode> predicate)
                throws InterruptedException {
            // Deadline computed once: a skipped event must not restart the clock.
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            while (true) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException(
                            "Timed out after " + timeout + " waiting for webhook event: " + label);
                }
                JsonNode envelope = events.poll(remaining, TimeUnit.NANOSECONDS);
                if (envelope == null) {
                    // poll() reports a timeout by returning null rather than
                    // throwing, so this branch is what replaces withTimeout's
                    // TimeoutCancellationException. Without it the next line
                    // would NPE instead of reporting the real problem.
                    throw new IllegalStateException(
                            "Timed out after " + timeout + " waiting for webhook event: " + label);
                }
                if (predicate.test(envelope)) {
                    return envelope;
                }
                System.out.println(
                        "  (skipping webhook event — "
                                + label
                                + " not satisfied; type="
                                + envelope.path("type").asText(null)
                                + " id="
                                + envelope.path("id").asText(null)
                                + ")");
            }
        }

        @Override
        public void close() {
            server.stop(0);
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
            ExampleConfig config, String token, JsonNode event) throws Exception {
        JsonNode stepDto = event.path("data").path("object");
        if (stepDto.isMissingNode() || stepDto.isNull()) {
            throw new IllegalStateException("Webhook event missing data.object: " + event);
        }
        // Use the user-facing account/network we sent on the rebalance request,
        // not the step DTO's `from_account_id` / `from_network`. The webhook
        // step's `from_account_id` is Tesser's internal wallet-account id which
        // doesn't resolve via `GET /v1/accounts/{id}`.
        String fromAccountId = config.rebalance().fromAccountId();
        String network = config.rebalance().fromNetwork();
        String signWith = fetchCryptoWalletAddress(config.tesserBaseUrl(), token, fromAccountId);
        return new StepForSigning(
                requireString(stepDto, "id"),
                // Webhook step DTO uses `rebalance_id` for the parent UUID; the
                // GET response uses `transfer_id` for the same value. Read
                // `rebalance_id` here since this code is fed by the webhook event.
                requireString(stepDto, "rebalance_id"),
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

    private static void printCompletedStep(JsonNode event) {
        JsonNode stepObject = event.path("data").path("object");
        if (stepObject.isMissingNode() || stepObject.isNull()) {
            throw new IllegalStateException("Completion event missing data.object: " + event);
        }
        System.out.printf(
                "Rebalance complete. step.id=%s status=%s completed_at=%s%n",
                stepObject.path("id").asText(null),
                stepObject.path("status").asText(null),
                stepObject.path("completed_at").asText(null));
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
        Matcher m = ACCESS_TOKEN.matcher(resp.body());
        if (!m.find()) {
            throw new IllegalStateException(
                    "OAuth response did not contain access_token: " + resp.body());
        }
        return m.group(1);
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
