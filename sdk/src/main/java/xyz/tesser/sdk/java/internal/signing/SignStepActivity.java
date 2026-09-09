package xyz.tesser.sdk.java.internal.signing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import xyz.tesser.sdk.java.SignStepOptions;
import xyz.tesser.sdk.java.SignedStepResult;
import xyz.tesser.sdk.java.SignedStepResultMetadata;
import xyz.tesser.sdk.java.SigningConfig;
import xyz.tesser.sdk.java.StepForSigning;
import xyz.tesser.sdk.java.internal.util.Json;

/**
 * Builds an {@code ACTIVITY_TYPE_SIGN_TRANSACTION_V2} Turnkey activity for a rebalance step, stamps
 * it, and returns the composite {@code base64({body, stamp})} envelope consumed by {@code
 * /v1/treasury/rebalances/{transferId}/steps/{stepId}/sign}. Tesser forwards the activity to
 * Turnkey on the caller's behalf.
 *
 * <p>Internal. Public callers go through {@link xyz.tesser.sdk.java.LocalSigner}.
 */
public final class SignStepActivity {

    private SignStepActivity() {}

    /**
     * @param opts reserved for future per-call tuning; no field is read yet, but it is still
     *     null-checked so a null cannot sit undetected in caller code until the first option lands
     * @param clock injected for deterministic bodies in tests
     */
    public static CompletableFuture<SignedStepResult> sign(
            SigningConfig signing,
            StepForSigning step,
            SignStepOptions opts,
            Stamp stamp,
            LongSupplier clock) {
        try {
            // Inside the try, so it fails the future rather than throwing at the
            // call site — the contract LocalSigner documents and FutureSemanticsTest pins.
            Objects.requireNonNull(opts, "SignStepOptions must not be null");
            String turnkeyType = NetworkType.toTurnkeyType(step.network());

            ObjectNode body = Json.newObject();
            body.put("type", "ACTIVITY_TYPE_SIGN_TRANSACTION_V2");
            body.put("timestampMs", Long.toString(clock.getAsLong()));
            body.put("organizationId", signing.enclaveId());

            ObjectNode parameters = body.putObject("parameters");
            parameters.put("signWith", step.signWith());
            parameters.put("unsignedTransaction", step.unsignedTransaction());
            parameters.put("type", turnkeyType);

            String bodyJson = Json.write(body);

            return stamp.stamp(signing, bodyJson)
                    .thenApply(
                            stamped -> {
                                ObjectNode composite = Json.newObject();
                                composite.put("body", bodyJson);
                                composite.put("stamp", stamped.stampHeaderValue());

                                String signature =
                                        Base64.getEncoder()
                                                .encodeToString(
                                                        Json.write(composite)
                                                                .getBytes(StandardCharsets.UTF_8));

                                return new SignedStepResult(
                                        signature,
                                        step.unsignedTransaction(),
                                        new SignedStepResultMetadata(
                                                stamped.stampHeaderName(),
                                                stamped.stampHeaderValue(),
                                                bodyJson));
                            });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
