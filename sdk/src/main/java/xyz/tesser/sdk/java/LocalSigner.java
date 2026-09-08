package xyz.tesser.sdk.java;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import xyz.tesser.sdk.java.internal.signing.CreateWalletActivity;
import xyz.tesser.sdk.java.internal.signing.SignStepActivity;
import xyz.tesser.sdk.java.internal.signing.Stamp;
import xyz.tesser.sdk.java.internal.util.Logging;

/**
 * Produces locally-signed activity payloads for Tesser API operations.
 *
 * <p>Construction validates that all three {@link SigningConfig} fields are non-blank.
 *
 * <p><b>Thread-safe and reentrant.</b> Holds no mutable state; a single instance may be shared
 * freely.
 *
 * <p>Every signing method returns a {@link CompletableFuture} and never throws an <i>exception</i>
 * synchronously — failures arrive as a failed future. {@link Error} still propagates from the call
 * site, since catching it to wrap in a future would do more harm than good. Today the work runs on
 * the calling thread; the future type exists so that adding external I/O (an MPC co-signer call in
 * the stamper) stays a non-breaking change.
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

    /** Production constructor. */
    public LocalSigner(SigningConfig signing) {
        this(signing, Stamp.create(), System::currentTimeMillis);
    }

    /** Test seam. */
    LocalSigner(SigningConfig signing, Stamp stamp) {
        this(signing, stamp, System::currentTimeMillis);
    }

    /** Test seam with a deterministic clock. */
    LocalSigner(SigningConfig signing, Stamp stamp, LongSupplier clock) {
        requireNonBlank(signing.publicKey(), "SigningConfig.publicKey must not be blank");
        requireNonBlank(signing.privateKey(), "SigningConfig.privateKey must not be blank");
        requireNonBlank(signing.enclaveId(), "SigningConfig.enclaveId must not be blank");
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
     * Builds and stamps an {@code ACTIVITY_TYPE_CREATE_WALLET} payload locally.
     *
     * <p>The returned {@link SignedResult#signature()} is the exact value to pass into Tesser's
     * wallet-creation request body. No HTTP is performed.
     *
     * <p>The future fails with {@link xyz.tesser.sdk.java.error.TesserError.ConfigError} if the
     * wallet type has no registered account spec, or {@link
     * xyz.tesser.sdk.java.error.TesserError.SigningError} if the stamper fails.
     */
    public CompletableFuture<SignedResult> signCreateWallet(CreateWalletParams params) {
        return CreateWalletActivity.sign(signing, params, stamp, clock);
    }

    /** Equivalent to {@code signStep(step, new SignStepOptions())}. */
    public CompletableFuture<SignedStepResult> signStep(StepForSigning step) {
        return signStep(step, new SignStepOptions());
    }

    /**
     * Builds and stamps an {@code ACTIVITY_TYPE_SIGN_TRANSACTION_V2} payload for a rebalance step.
     *
     * <p>Submit {@link SignedStepResult#signature()} as {@code {"signature": ...}} to {@code POST
     * /v1/treasury/rebalances/{transferId}/steps/{stepId}/sign}. No HTTP is performed here; Tesser
     * forwards the activity to Turnkey.
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
