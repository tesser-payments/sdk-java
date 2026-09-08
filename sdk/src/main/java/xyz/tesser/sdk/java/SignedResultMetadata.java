package xyz.tesser.sdk.java;

import java.util.Objects;

/**
 * Diagnostic metadata attached to a {@link SignedResult}.
 *
 * @param stampHeaderName the stamp header name, typically {@code X-Stamp}
 * @param stampHeaderValue the base64url-encoded stamp value
 * @param body the exact JSON body that was stamped
 */
public record SignedResultMetadata(String stampHeaderName, String stampHeaderValue, String body) {

    /** Rejects nulls with NPE, matching the Kotlin data class. See {@link CreateWalletParams}. */
    public SignedResultMetadata {
        Objects.requireNonNull(
                stampHeaderName, "SignedResultMetadata.stampHeaderName must not be null");
        Objects.requireNonNull(
                stampHeaderValue, "SignedResultMetadata.stampHeaderValue must not be null");
        Objects.requireNonNull(body, "SignedResultMetadata.body must not be null");
    }
}
