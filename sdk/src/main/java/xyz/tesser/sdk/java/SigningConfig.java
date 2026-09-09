package xyz.tesser.sdk.java;

import java.util.Objects;

/**
 * Signing-key configuration for {@link LocalSigner}.
 *
 * <p><b>Key lifetime.</b> {@code privateKey} is held as a {@code String}, so it is immutable, may
 * be interned by the JVM, and stays resident until garbage collection — it will appear in heap
 * dumps taken while a {@code SigningConfig} is reachable, and the SDK cannot zero it. Treat the
 * process memory of anything holding a {@code SigningConfig} as key material, and prefer
 * short-lived signing processes over long-lived ones that keep the config alive.
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

    /**
     * Masks {@code privateKey}. The record default would print the raw private scalar, which then
     * leaks into anything that stringifies a config incidentally: a framework properties dump, an
     * exception message carrying the config, a debug {@code println}. {@code equals}/{@code
     * hashCode} keep their generated behaviour — only the rendering changes.
     *
     * <p>Declared {@code final} to match the signature of the record-generated method it replaces,
     * so the published API is unchanged.
     */
    @Override
    public final String toString() {
        return "SigningConfig[publicKey="
                + publicKey
                + ", privateKey=***, enclaveId="
                + enclaveId
                + "]";
    }
}
