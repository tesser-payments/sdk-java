package xyz.tesser.sdk.java.examples.webhooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
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
 *   <li>Wait for a {@code step.signature_requested} webhook event <i>for the rebalance this run
 *       created</i>.
 *   <li>Re-read the step over an authenticated {@code GET /v1/treasury/rebalances/{id}} and sign
 *       <i>that</i> with {@code LocalSigner.signStep}.
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
 * <h2>Why the webhook payload is never signed</h2>
 *
 * <p>Webhook signature verification is intentionally not implemented yet — the verification
 * algorithm is not documented in the public Tesser docs at the time of writing — so every POST that
 * reaches this listener is untrusted, and the listener is reachable by anyone who finds the tunnel
 * URL. The event is therefore used only as a <i>trigger</i>: it tells the example when to look, and
 * which rebalance and step to look for, and its {@code rebalance_id} is checked against the id
 * returned by {@code createRebalance} so a forged or stale event is skipped. The bytes that
 * actually get signed always come back from an authenticated GET, exactly as in the polling
 * example. A forged webhook can at worst make this program perform a redundant GET.
 *
 * <p>That still leaves the listener unauthenticated. Add signature verification before running
 * anything like this against production.
 */
public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Largest webhook body accepted. A step event is a few hundred bytes. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    /** How many unconsumed events to hold before shedding load. */
    private static final int EVENT_QUEUE_CAPACITY = 256;

    /** How long to let the authenticated read catch up with a webhook event. */
    private static final Duration STEP_READ_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration STEP_READ_POLL_INTERVAL = Duration.ofSeconds(1);

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
                    listener.awaitEventWhere(
                            Duration.ofSeconds(60),
                            "type=step.signature_requested rebalance_id=" + rebalanceId,
                            envelope ->
                                    "step.signature_requested"
                                                    .equals(envelope.path("type").asText(null))
                                            // Binding the event to this run's rebalance is what
                                            // makes a forged or left-over event harmless: without
                                            // it, the first signature_requested event of *any*
                                            // rebalance would be accepted.
                                            && rebalanceId.equals(
                                                    envelope.path("data")
                                                            .path("object")
                                                            .path("rebalance_id")
                                                            .asText(null)));
            String stepId = requireString(signatureRequested.path("data").path("object"), "id");
            StepForSigning step = fetchStepForSigning(config, token, rebalanceId, stepId);

            System.out.printf(
                    "Signing step %s for transfer %s (signWith=%s, network=%s) ...%n",
                    step.id(), step.transferId(), step.signWith(), step.network());
            SignedStepResult signed = signer.signStep(step).join();
            System.out.println(
                    "Local signature produced (" + signed.signature().length() + " chars)");

            submitSignature(config, token, step, signed);

            System.out.println(
                    "Waiting for step " + step.id() + " to reach `status=completed` ...");
            listener.awaitEventWhere(
                    Duration.ofMinutes(5),
                    "step " + step.id() + " status=completed",
                    envelope -> {
                        JsonNode stepObject = envelope.path("data").path("object");
                        return step.id().equals(stepObject.path("id").asText(null))
                                && "completed".equals(stepObject.path("status").asText(null));
                    });
            // Same rule as for signing: the event says when to look, the API says
            // what is true. Printing "complete" straight off the webhook would let
            // a forged event report a success that never happened — and a single
            // unchecked GET would do the same thing whenever the read lags the
            // event, so this waits for the API to actually report `completed`.
            printCompletedStep(
                    awaitStep(
                            config.tesserBaseUrl(),
                            token,
                            rebalanceId,
                            step.id(),
                            Main::isCompleted,
                            "to report `status=completed`"));
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
     * Buffers incoming webhook envelopes onto a queue so callers can pull events by {@code type} in
     * arrival order. Buffered rather than handed straight to the waiter so events that arrive
     * between awaits (e.g. {@code step.submitted} and {@code step.confirmed} while we're waiting
     * for {@code step.completed}) aren't dropped.
     *
     * <p>The Kotlin original uses an unlimited coroutine {@code Channel} plus {@code withTimeout}.
     * {@link LinkedBlockingQueue} is the direct equivalent of the former, except that the capacity
     * is bounded here: the Kotlin example's channel is fed by a private listener, whereas this one
     * is reachable from the public internet through the tunnel, where "unlimited" is a
     * memory-growth primitive. The timeout becomes a timed {@link LinkedBlockingQueue#poll} against
     * a deadline computed once, so a non-matching event does not restart the full timeout.
     */
    private static final class WebhookListener implements AutoCloseable {

        private final HttpServer server;
        private final LinkedBlockingQueue<JsonNode> events;

        private WebhookListener(HttpServer server, LinkedBlockingQueue<JsonNode> events) {
            this.server = server;
            this.events = events;
        }

        static WebhookListener start(int port) throws IOException {
            LinkedBlockingQueue<JsonNode> events = new LinkedBlockingQueue<>(EVENT_QUEUE_CAPACITY);
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
                // Bounded read. This endpoint is public by design (that is what the
                // tunnel is for), so an unbounded readAllBytes() into an unbounded
                // queue lets any POST storm grow the heap without limit. A step
                // event is a few hundred bytes; 64 KiB is generous.
                byte[] raw = readBounded(exchange, MAX_BODY_BYTES);
                if (raw == null) {
                    System.out.println(
                            "  webhook rejected: body over " + MAX_BODY_BYTES + " bytes");
                    exchange.sendResponseHeaders(413, -1);
                    return;
                }
                String body = new String(raw, StandardCharsets.UTF_8);
                // TODO: verify the webhook signature header before trusting the payload.
                //   The exact header name and algorithm aren't currently documented in
                //   the public Tesser docs; until verification lands, this handler accepts
                //   every incoming POST. Nothing from the payload is ever signed --
                //   see the class Javadoc -- but the listener is still unauthenticated.
                //   Do NOT run against production.
                JsonNode envelope = JSON.readTree(body);
                System.out.println(
                        "  webhook received: path="
                                + path
                                + " type="
                                + envelope.path("type").asText(null)
                                + " id="
                                + envelope.path("id").asText(null));
                if (!events.offer(envelope)) {
                    // Bounded queue: a flood of junk POSTs is dropped rather than
                    // retained. Real events are consumed within seconds, so a full
                    // queue means something is spamming the tunnel.
                    System.out.println("  webhook dropped: event queue full");
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
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

        /**
         * Reads at most {@code max} bytes of the request body, or returns null if the body is
         * larger.
         *
         * <p>{@code Content-Length} is not trusted as the limit — it is attacker-controlled and may
         * be absent under chunked encoding — so the read itself is capped and one extra byte is
         * probed to tell "exactly max" from "over max".
         */
        private static byte[] readBounded(HttpExchange exchange, int max) throws IOException {
            try (InputStream in = exchange.getRequestBody()) {
                byte[] body = in.readNBytes(max);
                if (body.length == max && in.read() != -1) {
                    return null;
                }
                return body;
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

    /**
     * Builds the step to sign from an authenticated {@code GET}, using the webhook only to know
     * which rebalance and step to fetch.
     *
     * <p>Nothing that ends up inside the signature originates from the webhook body. Taking {@code
     * unsigned_transaction} straight off the POST would mean this program signs whatever bytes an
     * unauthenticated caller put there, with the enclave key, and then submits the result to the
     * real API — the transaction is the one thing that must not come from an untrusted source.
     */
    private static StepForSigning fetchStepForSigning(
            ExampleConfig config, String token, String rebalanceId, String stepId)
            throws Exception {
        JsonNode stepDto =
                awaitStep(
                        config.tesserBaseUrl(),
                        token,
                        rebalanceId,
                        stepId,
                        Main::hasUnsignedTransaction,
                        "to carry an `unsigned_transaction`");
        // Use the user-facing account/network we sent on the rebalance request,
        // not the step DTO's `from_account_id` / `from_network`. The step's
        // `from_account_id` is Tesser's internal wallet-account id which
        // doesn't resolve via `GET /v1/accounts/{id}`.
        String fromAccountId = config.rebalance().fromAccountId();
        String network = config.rebalance().fromNetwork();
        String signWith = fetchCryptoWalletAddress(config.tesserBaseUrl(), token, fromAccountId);
        return new StepForSigning(
                requireString(stepDto, "id"),
                parentRebalanceId(stepDto),
                requireString(stepDto, "unsigned_transaction"),
                signWith,
                network);
    }

    /**
     * Polls the authenticated API until the step satisfies {@code condition}.
     *
     * <p>A single GET is not enough, because a webhook can beat the read-your-writes window in both
     * directions: just after {@code step.signature_requested} the step may exist with no {@code
     * unsigned_transaction} yet, and just after a {@code completed} event it may still report the
     * previous status. Both callers need the same retry and differ only in what they are waiting
     * for, so the condition is a parameter rather than a second copy of this loop.
     *
     * <p>A step the read cannot see at all counts as an unsatisfied condition, not an error. That
     * is the same lag one step further along, so letting it escape would abort precisely the case
     * this loop exists to absorb; the deadline decides instead.
     */
    private static JsonNode awaitStep(
            String baseUrl,
            String token,
            String rebalanceId,
            String stepId,
            Predicate<JsonNode> condition,
            String waitingFor)
            throws Exception {
        long deadlineNanos = System.nanoTime() + STEP_READ_TIMEOUT.toNanos();
        while (true) {
            JsonNode candidate = findStepById(baseUrl, token, rebalanceId, stepId);
            if (candidate != null && condition.test(candidate)) {
                return candidate;
            }
            if (System.nanoTime() - deadlineNanos >= 0) {
                throw new IllegalStateException(
                        "Timed out after "
                                + STEP_READ_TIMEOUT
                                + " waiting for step "
                                + stepId
                                + " of rebalance "
                                + rebalanceId
                                + " "
                                + (candidate == null
                                        ? "to appear in the rebalance at all. It was never "
                                                + "returned by GET /v1/treasury/rebalances/"
                                                + rebalanceId
                                        : waitingFor + ". Last read: " + candidate));
            }
            Thread.sleep(STEP_READ_POLL_INTERVAL.toMillis());
        }
    }

    private static boolean hasUnsignedTransaction(JsonNode step) {
        String unsignedTx = step.path("unsigned_transaction").asText(null);
        return unsignedTx != null && !unsignedTx.isBlank();
    }

    private static boolean isCompleted(JsonNode step) {
        return "completed".equals(step.path("status").asText(null));
    }

    /**
     * Reads one step of a rebalance over the authenticated API, or null if the rebalance does not
     * (yet) list a step with that id. Null rather than an exception because the only caller is a
     * retry loop, for which "not there yet" is an ordinary intermediate state.
     */
    private static JsonNode findStepById(
            String baseUrl, String token, String rebalanceId, String stepId) throws Exception {
        String response = getJson(baseUrl + "/v1/treasury/rebalances/" + rebalanceId, token);
        for (JsonNode candidate : JSON.readTree(response).path("data").path("steps")) {
            if (stepId.equals(candidate.path("id").asText(null))) {
                return candidate;
            }
        }
        return null;
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

    /** Takes the step DTO from the API, not the webhook envelope. */
    private static void printCompletedStep(JsonNode stepDto) {
        System.out.printf(
                "Rebalance complete. step.id=%s status=%s completed_at=%s%n",
                stepDto.path("id").asText(null),
                stepDto.path("status").asText(null),
                stepDto.path("completed_at").asText(null));
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

    /**
     * The parent rebalance id, which becomes the {@code {transferId}} path segment of the sign URL.
     * The step's parent-id field is named after the resource that owns it: rebalance steps carry
     * {@code rebalance_id} (payments {@code payment_id}, withdrawals {@code withdrawal_id}). Older
     * API responses used {@code transfer_id} for all of them, so it is accepted as a fallback.
     */
    private static String parentRebalanceId(JsonNode stepDto) {
        for (String field : new String[] {"rebalance_id", "transfer_id"}) {
            JsonNode value = stepDto.path(field);
            if (value.isTextual()) {
                return value.asText();
            }
        }
        throw new IllegalStateException(
                "Missing required field `rebalance_id` (or legacy `transfer_id`) in: " + stepDto);
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
