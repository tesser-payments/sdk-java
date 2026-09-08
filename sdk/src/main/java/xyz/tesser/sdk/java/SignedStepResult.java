package xyz.tesser.sdk.java;

import java.util.Objects;

/**
 * Output of {@link LocalSigner#signStep}.
 *
 * @param signature base64-encoded JSON {@code {body, stamp}} where body is the
 *     Turnkey {@code ACTIVITY_TYPE_SIGN_TRANSACTION_V2} request. Submit as
 *     {@code {"signature": ...}} to
 *     {@code POST /v1/treasury/rebalances/{transferId}/steps/{stepId}/sign}.
 *     Tesser forwards the activity to Turnkey on the caller's behalf.
 * @param unsignedTransaction echo of the input's unsignedTransaction, for audit trails
 * @param metadata diagnostic context; not required for the request itself
 */
public record SignedStepResult(
        String signature, String unsignedTransaction, SignedStepResultMetadata metadata) {

    /** Rejects nulls with NPE, matching the Kotlin data class. See {@link CreateWalletParams}. */
    public SignedStepResult {
        Objects.requireNonNull(signature, "SignedStepResult.signature must not be null");
        Objects.requireNonNull(
                unsignedTransaction, "SignedStepResult.unsignedTransaction must not be null");
        Objects.requireNonNull(metadata, "SignedStepResult.metadata must not be null");
    }
}
