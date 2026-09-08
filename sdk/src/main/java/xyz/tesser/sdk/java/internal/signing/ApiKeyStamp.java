package xyz.tesser.sdk.java.internal.signing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DLSequence;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import xyz.tesser.sdk.java.SigningConfig;
import xyz.tesser.sdk.java.error.TesserError;
import xyz.tesser.sdk.java.internal.util.Json;

/**
 * Turnkey API-key stamper for the JVM.
 *
 * <p>Produces a stamp identical in shape to the vendor SDK's wire format:
 *
 * <pre>
 * base64url(JSON({
 *   publicKey: &lt;hex&gt;,
 *   signature: &lt;DER ECDSA(SHA-256(body)) hex&gt;,
 *   scheme:    "SIGNATURE_SCHEME_TK_API_P256",
 * }))
 * </pre>
 *
 * <p>Returned under the header name {@code X-Stamp}. Key loading, ECDSA signing and DER encoding
 * all use Bouncy Castle; only the JSON envelope and base64url wrap are written here.
 */
final class ApiKeyStamp implements Stamp {

    @Override
    public CompletableFuture<StampResult> stamp(SigningConfig keys, String body) {
        try {
            byte[] sigDer = signDer(keys.privateKey(), body);

            ObjectNode stampJson = Json.newObject();
            stampJson.put("publicKey", keys.publicKey());
            stampJson.put("signature", bytesToHex(sigDer));
            stampJson.put("scheme", "SIGNATURE_SCHEME_TK_API_P256");

            String encoded =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(Json.write(stampJson).getBytes(StandardCharsets.UTF_8));

            return CompletableFuture.completedFuture(new StampResult("X-Stamp", encoded));
        } catch (RuntimeException e) {
            // Never throw synchronously from a future-returning method.
            return CompletableFuture.failedFuture(e);
        }
    }

    private static byte[] signDer(String privateKeyHex, String body) {
        try {
            byte[] privBytes = hexToBytes(privateKeyHex);
            BigInteger scalar = new BigInteger(1, privBytes);
            ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256r1");
            if (scalar.compareTo(BigInteger.ONE) < 0 || scalar.compareTo(spec.getN()) >= 0) {
                throw new IllegalArgumentException(
                        "Private key scalar is out of the valid range [1, n-1]");
            }
            ECDomainParameters domain =
                    new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH());

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            byte[] hash = new byte[32];
            SHA256Digest digest = new SHA256Digest();
            digest.update(bodyBytes, 0, bodyBytes.length);
            digest.doFinal(hash, 0);

            ECDSASigner signer = new ECDSASigner();
            signer.init(true, new ECPrivateKeyParameters(scalar, domain));
            BigInteger[] rs = signer.generateSignature(hash);

            return new DLSequence(
                            new ASN1Integer[] {new ASN1Integer(rs[0]), new ASN1Integer(rs[1])})
                    .getEncoded();
        } catch (IOException | RuntimeException e) {
            throw new TesserError.SigningError("Signing failed: " + e.getMessage(), e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((hexDigit(hex.charAt(i * 2)) << 4) | hexDigit(hex.charAt(i * 2 + 1)));
        }
        return out;
    }

    /**
     * ASCII-only hex, deliberately not {@link Character#digit}.
     *
     * <p>{@code Character.digit} accepts Unicode digits and letters from other blocks: it returns 5
     * for U+0665 (Arabic-Indic five) and 10 for U+FF21 (fullwidth A). The Kotlin SDK validates with
     * {@code it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'}, so it rejects those. Using
     * Character.digit here would make the Java SDK silently accept a key the Kotlin SDK refuses and
     * sign with a different scalar than the caller intended — precisely the wrong-signature failure
     * this SDK is built to avoid.
     */
    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        throw new IllegalArgumentException("Hex string contains non-hex characters");
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
