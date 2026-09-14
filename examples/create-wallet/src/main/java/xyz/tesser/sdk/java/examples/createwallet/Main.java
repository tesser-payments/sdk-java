package xyz.tesser.sdk.java.examples.createwallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import xyz.tesser.sdk.java.CreateWalletParams;
import xyz.tesser.sdk.java.LocalSigner;
import xyz.tesser.sdk.java.SignedResult;
import xyz.tesser.sdk.java.SigningConfig;
import xyz.tesser.sdk.java.WalletType;

/**
 * End-to-end harness for {@code LocalSigner.signCreateWallet}.
 *
 * <p>Reads configuration from environment variables, performs an OAuth client_credentials token
 * exchange directly (the SDK does not absorb OAuth), signs a CreateWallet payload locally, and
 * submits it to Tesser.
 *
 * <pre>
 * API_BASE_URL=https://sandbox.tesserx.co \
 * AUTH_TOKEN_URL=https://dev-awqy75wdabpsnsvu.us.auth0.com/oauth/token \
 * API_CLIENT_ID=&lt;id&gt; API_CLIENT_SECRET=&lt;secret&gt; \
 * SIGNING_PUBLIC_KEY=&lt;hex&gt; SIGNING_PRIVATE_KEY=&lt;hex&gt; SIGNING_ENCLAVE_ID=&lt;org&gt; \
 * CREATE_WALLET_TYPE=stablecoin_ethereum \
 * ./gradlew :examples:create-wallet:run
 * </pre>
 *
 * <p>{@code AUTH_TOKEN_URL} is separate from {@code API_BASE_URL} because Tesser hosts the OAuth
 * endpoint on a different subdomain. Ask Tesser support for the URL matching your environment
 * (sandbox, staging, or production).
 */
public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One shared client with a connect timeout. {@code HttpClient.newHttpClient()} per call builds
     * a new connection pool each time, and neither the client nor {@link HttpRequest} times out by
     * default, so a stalled connection would hang the run indefinitely.
     */
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private Main() {}

    public static void main(String[] args) throws Exception {
        String baseUrl = requireEnv("API_BASE_URL");
        String authTokenUrl = requireEnv("AUTH_TOKEN_URL");
        // The audience defaults to the API base URL if not explicitly set.
        String audience = optionalEnv("API_AUDIENCE", baseUrl);
        String clientId = requireEnv("API_CLIENT_ID");
        String clientSecret = requireEnv("API_CLIENT_SECRET");
        String pubKey = requireEnv("SIGNING_PUBLIC_KEY");
        String privKey = requireEnv("SIGNING_PRIVATE_KEY");
        String enclaveId = requireEnv("SIGNING_ENCLAVE_ID");
        String walletTypeRaw = requireEnv("CREATE_WALLET_TYPE");

        WalletType walletType = WalletType.fromWireValue(walletTypeRaw);
        String walletName = "SDK Java Wallet " + System.currentTimeMillis();

        System.out.printf(
                "Fetching access token from %s (audience=%s) ...%n", authTokenUrl, audience);
        String token = fetchToken(authTokenUrl, clientId, clientSecret, audience);

        LocalSigner signer = new LocalSigner(new SigningConfig(pubKey, privKey, enclaveId));
        System.out.printf(
                "Signing CreateWallet activity for type=%s name=%s ...%n", walletType, walletName);
        SignedResult signed =
                signer.signCreateWallet(new CreateWalletParams(walletName, walletType)).join();

        System.out.printf("Submitting to %s/v1/accounts/wallets ...%n", baseUrl);
        // Built with the mapper rather than concatenated. Both interpolated values
        // happen to be constrained today (the type is enum-validated, the name is
        // generated), but a hand-written JSON string is one edit away from an
        // injection bug and nothing in the code says otherwise.
        ObjectNode payload = JSON.createObjectNode();
        payload.put("signature", signed.signature());
        payload.put("name", walletName);
        payload.put("type", walletTypeRaw);
        payload.put("is_managed", true);
        String body = payload.toString();
        System.out.println(
                "Wallet created. Response: "
                        + postJson(baseUrl + "/v1/accounts/wallets", token, body));
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
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();

        HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() > 299) {
            throw new IllegalStateException(
                    "OAuth token exchange failed: " + resp.statusCode() + " " + resp.body());
        }

        // The token endpoint returns {"access_token":"...","token_type":"Bearer",...}.
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

    private static String postJson(String url, String bearer, String body) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

        HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
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
