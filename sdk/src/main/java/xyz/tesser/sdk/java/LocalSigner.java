package xyz.tesser.sdk.java;

import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import xyz.tesser.sdk.java.internal.signing.CreateWalletActivity;
import xyz.tesser.sdk.java.internal.signing.KeyPairCheck;
import xyz.tesser.sdk.java.internal.signing.SignStepActivity;
import xyz.tesser.sdk.java.internal.signing.Stamp;
import xyz.tesser.sdk.java.internal.signing.TesserApiConnection;
import xyz.tesser.sdk.java.internal.util.Logging;

/**
 * Produces locally-signed activity payloads for Tesser API operations.
 *
 * <p>Construction validates that all three {@link SigningConfig} fields are non-blank, that the
 * private key is a well-formed P-256 scalar, that the public key is that scalar's public point, and
 * that {@code apiBaseUrl} is an https URL. A mismatched pair or an invalid {@code apiBaseUrl}
 * throws {@link xyz.tesser.sdk.java.error.TesserError.ConfigError} here rather than becoming an
 * opaque rejection later. The key check is the only cryptographic work the constructor does; it
 * costs one scalar multiplication, once per signer.
 *
 * <p><b>Thread-safe and reentrant.</b> Holds no mutable state; a single instance may be shared
 * freely.
 *
 * <p>Every signing method returns a {@link CompletableFuture} and never throws an <i>exception</i>
 * synchronously; failures arrive as a failed future. {@link Error} still propagates from the call
 * site, since catching it to wrap in a future would do more harm than good. Signing runs on the
 * calling thread. For {@link WalletType#STABLECOIN_ETHEREUM}, {@link
 * #signCreateWallet(CreateWalletParams)} first waits for a request to Tesser, so its future
 * completes on a thread of the {@link HttpClient}.
 *
 * <p>On failure, {@code get()} throws {@link java.util.concurrent.ExecutionException} and {@code
 * join()} throws {@link java.util.concurrent.CompletionException}; in both cases the {@link
 * xyz.tesser.sdk.java.error.TesserError} is {@code getCause()}.
 */
public final class LocalSigner {

    private static final Logger LOG = Logging.logger("xyz.tesser.sdk.java.LocalSigner");

    private final SigningConfig signing;
    private final Stamp stamp;
    private final LongSupplier clock;
    private final TesserApiConnection api;

    /**
     * Creates a signer that uses a default {@link HttpClient} for requests to Tesser.
     *
     * @param signing API-key configuration (public key, private key, enclave ID)
     * @param apiBaseUrl Tesser API base URL: {@code https://api.tesser.xyz} in production, {@code
     *     https://api-sandbox.tesser.xyz} in sandbox. A trailing {@code /} or {@code /v1} is
     *     removed.
     */
    public LocalSigner(SigningConfig signing, String apiBaseUrl) {
        this(signing, apiBaseUrl, TesserApiConnection.createDefaultHttpClient());
    }

    /**
     * Creates a signer that uses {@code httpClient} for requests to Tesser.
     *
     * @param signing API-key configuration (public key, private key, enclave ID)
     * @param apiBaseUrl Tesser API base URL: {@code https://api.tesser.xyz} in production, {@code
     *     https://api-sandbox.tesser.xyz} in sandbox. A trailing {@code /} or {@code /v1} is
     *     removed.
     * @param httpClient HTTP client used for requests to Tesser. Pass one to configure a proxy or
     *     TLS settings.
     */
    public LocalSigner(SigningConfig signing, String apiBaseUrl, HttpClient httpClient) {
        this(signing, apiBaseUrl, httpClient, Stamp.create(), System::currentTimeMillis);
    }

    /** Test seam. */
    LocalSigner(SigningConfig signing, String apiBaseUrl, HttpClient httpClient, Stamp stamp) {
        this(signing, apiBaseUrl, httpClient, stamp, System::currentTimeMillis);
    }

    /** Test seam with a deterministic clock. */
    LocalSigner(
            SigningConfig signing,
            String apiBaseUrl,
            HttpClient httpClient,
            Stamp stamp,
            LongSupplier clock) {
        requireNonBlank(signing.publicKey(), "SigningConfig.publicKey must not be blank");
        requireNonBlank(signing.privateKey(), "SigningConfig.privateKey must not be blank");
        requireNonBlank(signing.enclaveId(), "SigningConfig.enclaveId must not be blank");
        KeyPairCheck.verify(signing);
        this.api = TesserApiConnection.create(apiBaseUrl, httpClient);
        this.signing = signing;
        this.stamp = stamp;
        this.clock = clock;
        LOG.debug("LocalSigner constructed for enclaveId={}", signing.enclaveId());
    }

    /** The configuration this signer was built with. */
    public SigningConfig signing() {
        return signing;
    }

    /**
     * Signs a wallet-creation request.
     *
     * <p>For {@link WalletType#STABLECOIN_ETHEREUM} the SDK first fetches the request to sign from
     * the Tesser API and checks that it matches {@code params}. Other wallet types are signed
     * locally without a request to Tesser.
     *
     * <p>The returned {@link SignedResult#signature()} is the exact value to pass into Tesser's
     * wallet-creation request body. The private key never leaves the JVM.
     *
     * <p>The future fails with:
     *
     * <ul>
     *   <li>{@link xyz.tesser.sdk.java.error.TesserError.ConfigError} if the wallet type is not
     *       supported;
     *   <li>{@link xyz.tesser.sdk.java.error.TesserError.SigningError} if signing fails or the
     *       request fetched from Tesser does not match {@code params}; nothing is signed;
     *   <li>{@link xyz.tesser.sdk.java.error.TesserError.APIError} if Tesser answers with a non-2xx
     *       status. Safe to retry for status 429 or 5xx; other 4xx statuses fail the same way on
     *       retry;
     *   <li>{@link xyz.tesser.sdk.java.error.TesserError.ConnectionError} if the request to Tesser
     *       fails at the transport level. Safe to retry;
     *   <li>{@link xyz.tesser.sdk.java.error.TesserError.TimeoutError} if the request to Tesser
     *       times out. Safe to retry.
     * </ul>
     */
    public CompletableFuture<SignedResult> signCreateWallet(CreateWalletParams params) {
        return CreateWalletActivity.sign(signing, params, stamp, clock, api);
    }

    /** Equivalent to {@code signStep(step, new SignStepOptions())}. */
    public CompletableFuture<SignedStepResult> signStep(StepForSigning step) {
        return signStep(step, new SignStepOptions());
    }

    /**
     * Authorizes a rebalance step's transaction with your API key, without changing it.
     *
     * <p>Submit {@link SignedStepResult#signature()} as {@code {"signature": ...}} to {@code POST
     * /v1/treasury/rebalances/{transferId}/steps/{stepId}/sign}. No HTTP is performed here.
     *
     * <p>The future fails with {@link xyz.tesser.sdk.java.error.TesserError.ConfigError} for an
     * unsupported network, or {@link xyz.tesser.sdk.java.error.TesserError.SigningError} if the
     * stamper fails.
     */
    public CompletableFuture<SignedStepResult> signStep(StepForSigning step, SignStepOptions opts) {
        return SignStepActivity.sign(signing, step, opts, stamp, clock);
    }

    private static void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
