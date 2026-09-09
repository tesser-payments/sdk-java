package xyz.tesser.sdk.java.internal.signing;

import java.math.BigInteger;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import xyz.tesser.sdk.java.internal.util.Hex;

/**
 * secp256r1 (P-256) curve parameters and key-material parsing.
 *
 * <p>Internal. Shared by the stamper and the key-pair check so both agree on what a valid scalar
 * is. The spec is cached: {@link ECNamedCurveTable} hands back objects backed by the same shared
 * curve on every lookup, and reusing one instance lets Bouncy Castle keep its point-multiplication
 * precomputation for G rather than rebuilding it per signature.
 */
public final class P256 {

    private static final ECNamedCurveParameterSpec SPEC =
            ECNamedCurveTable.getParameterSpec("secp256r1");

    private P256() {}

    /** Domain parameters for signing and verification. */
    public static ECDomainParameters domain() {
        return new ECDomainParameters(SPEC.getCurve(), SPEC.getG(), SPEC.getN(), SPEC.getH());
    }

    /**
     * Parses a raw private scalar from hex and range-checks it.
     *
     * @throws IllegalArgumentException if the hex is malformed or the scalar is outside [1, n-1]
     */
    public static BigInteger scalar(String privateKeyHex) {
        BigInteger scalar = new BigInteger(1, Hex.decode(privateKeyHex));
        if (scalar.compareTo(BigInteger.ONE) < 0 || scalar.compareTo(SPEC.getN()) >= 0) {
            throw new IllegalArgumentException(
                    "Private key scalar is out of the valid range [1, n-1]");
        }
        return scalar;
    }

    /** The public point for {@code scalar}, i.e. {@code G * scalar}. */
    public static ECPoint publicPoint(BigInteger scalar) {
        return SPEC.getG().multiply(scalar).normalize();
    }

    /**
     * Decodes a public key from hex.
     *
     * <p>Accepts whatever encodings the curve does — compressed ({@code 02}/{@code 03}) and
     * uncompressed ({@code 04}) — because the caller only needs the <i>point</i> to compare against
     * a derived one. Rejecting an uncompressed key here would turn a comparison into an encoding
     * opinion, and the encoding opinion already lives in {@code SigningConfig}'s contract.
     *
     * @throws IllegalArgumentException if the hex is malformed or is not a point on the curve
     */
    public static ECPoint decodePublicKey(String publicKeyHex) {
        return SPEC.getCurve().decodePoint(Hex.decode(publicKeyHex)).normalize();
    }
}
