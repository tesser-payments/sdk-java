package xyz.tesser.sdk.java;

import java.util.Objects;

/**
 * Output of {@link LocalSigner#signCreateWallet}.
 *
 * @param signature base64-encoded JSON {@code {body, stamp}}. Pass straight into
 *     Tesser's wallet-creation request body as the {@code signature} field.
 * @param metadata diagnostic context; not required for the request itself
 */
public record SignedResult(String signature, SignedResultMetadata metadata) {

    /** Rejects nulls with NPE, matching the Kotlin data class. See {@link CreateWalletParams}. */
    public SignedResult {
        Objects.requireNonNull(signature, "SignedResult.signature must not be null");
        Objects.requireNonNull(metadata, "SignedResult.metadata must not be null");
    }
}
