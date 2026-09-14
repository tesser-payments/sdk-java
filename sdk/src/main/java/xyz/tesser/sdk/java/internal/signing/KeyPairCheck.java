package xyz.tesser.sdk.java.internal.signing;

import java.math.BigInteger;
import org.bouncycastle.math.ec.ECPoint;
import xyz.tesser.sdk.java.SigningConfig;
import xyz.tesser.sdk.java.error.TesserError;

/**
 * Verifies that a {@link SigningConfig}'s {@code publicKey} really is the public point of its
 * {@code privateKey}.
 *
 * <p>Internal. Without this, a mismatched pair produces a perfectly well-formed stamp that verifies
 * nowhere: the signature is made with one key and the envelope advertises another. The failure
 * surfaces as an opaque authentication rejection from Turnkey, several network hops from the typo
 * that caused it. One scalar multiplication at construction turns that into a local error naming
 * the field to fix.
 */
public final class KeyPairCheck {

    private KeyPairCheck() {}

    /**
     * @throws TesserError.ConfigError if either key is malformed, or if they are not a pair
     */
    public static void verify(SigningConfig config) {
        BigInteger scalar;
        try {
            scalar = P256.scalar(config.privateKey());
        } catch (RuntimeException e) {
            // Deliberately does not echo the offending value: this message may
            // end up in a log, and the offending value is the private key.
            throw new TesserError.ConfigError(
                    "SigningConfig.privateKey is not a valid P-256 private scalar in hex: "
                            + e.getMessage(),
                    e);
        }

        ECPoint configured;
        try {
            configured = P256.decodePublicKey(config.publicKey());
        } catch (RuntimeException e) {
            // Also does not echo the value. A field named publicKey normally
            // holds public data, but everything that reaches here is by
            // definition *not* a public key: it failed to decode as a point.
            // A misdirected env var or a key pasted into the wrong slot lands
            // here holding something else, quite possibly another private
            // scalar, and this code cannot tell which. Cheaper to never print it
            // than to be right about what it is.
            //
            // (A plain transposition of the first two constructor arguments does
            // not reach this line: a 33-byte public key read as a scalar is out
            // of range, so the privateKey branch above rejects it first.)
            throw new TesserError.ConfigError(
                    "SigningConfig.publicKey is not a P-256 point in hex: " + e.getMessage(), e);
        }

        if (!P256.publicPoint(scalar).equals(configured)) {
            // Safe to echo here, unlike above: reaching this line means the value
            // decoded as a point on the curve, and a private scalar cannot: it is
            // 32 bytes, while an encoded point is 33 or 65. So this is genuinely a
            // public key, just the wrong one, and naming it is the whole diagnostic.
            //
            // Points are compared, not hex strings, so an uppercase or uncompressed
            // encoding of the correct key still passes; only a different key fails.
            throw new TesserError.ConfigError(
                    "SigningConfig.publicKey ("
                            + config.publicKey()
                            + ") is not the public key for SigningConfig.privateKey. Stamps signed"
                            + " with this pair would be rejected by Turnkey. Check that both came"
                            + " from the same generated API key.");
        }
    }
}
