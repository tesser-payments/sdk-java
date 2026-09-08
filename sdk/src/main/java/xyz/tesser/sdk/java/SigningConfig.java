package xyz.tesser.sdk.java;

import java.util.Objects;

/**
 * Signing-key configuration for {@link LocalSigner}.
 *
 * @param publicKey 33-byte compressed P-256 (secp256r1) public key in hex — 66 characters, prefixed
 *     {@code 02} or {@code 03}. The SDK does NOT auto-compress. If your key was registered
 *     uncompressed, fix the registration upstream.
 * @param privateKey raw 32-byte P-256 private scalar in hex (64 characters)
 * @param enclaveId sub-organization ID the API key belongs to
 */
public record SigningConfig(String publicKey, String privateKey, String enclaveId) {

    /** Rejects nulls with NPE, matching the Kotlin data class. See {@link CreateWalletParams}. */
    public SigningConfig {
        Objects.requireNonNull(publicKey, "SigningConfig.publicKey must not be null");
        Objects.requireNonNull(privateKey, "SigningConfig.privateKey must not be null");
        Objects.requireNonNull(enclaveId, "SigningConfig.enclaveId must not be null");
    }
}
