package xyz.tesser.sdk.java;

import java.util.Objects;

/**
 * Diagnostic metadata attached to a {@link SignedStepResult}.
 *
 * @param stampHeaderName the stamp header name, typically {@code X-Stamp}
 * @param stampHeaderValue the base64url-encoded stamp value
 * @param body the exact JSON that was stamped (the Turnkey {@code
 *     ACTIVITY_TYPE_SIGN_TRANSACTION_V2} activity request)
 */
public record SignedStepResultMetadata(
        String stampHeaderName, String stampHeaderValue, String body) {

    /** Rejects nulls with NPE, matching the Kotlin data class. See {@link CreateWalletParams}. */
    public SignedStepResultMetadata {
        Objects.requireNonNull(
                stampHeaderName, "SignedStepResultMetadata.stampHeaderName must not be null");
        Objects.requireNonNull(
                stampHeaderValue, "SignedStepResultMetadata.stampHeaderValue must not be null");
        Objects.requireNonNull(body, "SignedStepResultMetadata.body must not be null");
    }
}
