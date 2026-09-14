package xyz.tesser.sdk.java;

/**
 * A real, matching P-256 key pair for tests.
 *
 * <p>The private scalar is 1, so the public point is the curve generator G, a published constant
 * from FIPS 186-4, not a secret anyone could mistake for one. It has to be a genuine pair because
 * {@code LocalSigner}'s constructor derives the public point and rejects a mismatch; an arbitrary
 * placeholder like {@code "02".repeat(33)} no longer constructs.
 *
 * <p>NEVER use for real signing.
 */
public final class TestKeys {

    /** Compressed encoding of the secp256r1 generator point G. */
    public static final String PUBLIC_KEY =
            "036b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296";

    /** Private scalar 1. */
    public static final String PRIVATE_KEY =
            "0000000000000000000000000000000000000000000000000000000000000001";

    private TestKeys() {}

    /** A config with the test pair and the given enclave id. */
    public static SigningConfig config(String enclaveId) {
        return new SigningConfig(PUBLIC_KEY, PRIVATE_KEY, enclaveId);
    }
}
