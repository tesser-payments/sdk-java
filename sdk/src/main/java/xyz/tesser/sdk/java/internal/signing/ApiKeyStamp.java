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
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import xyz.tesser.sdk.java.SigningConfig;
import xyz.tesser.sdk.java.error.TesserError;
import xyz.tesser.sdk.java.internal.util.Hex;
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
 *
 * <p>Nonces are RFC 6979 deterministic, so stamping the same body with the same key twice yields
 * the same signature. That is a Java-SDK-only property (the Kotlin SDK and the vendor's WebCrypto
 * implementation use random k), and it is invisible on the wire: a verifier checks {@code (r, s)}
 * against the public key and cannot tell how k was derived.
 */
final class ApiKeyStamp implements Stamp {

    @Override
    public CompletableFuture<StampResult> stamp(SigningConfig keys, String body) {
        try {
            byte[] sigDer = signDer(keys.privateKey(), body);

            ObjectNode stampJson = Json.newObject();
            stampJson.put("publicKey", keys.publicKey());
            stampJson.put("signature", Hex.encode(sigDer));
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
            BigInteger scalar = P256.scalar(privateKeyHex);
            ECDomainParameters domain = P256.domain();

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            byte[] hash = new byte[32];
            SHA256Digest digest = new SHA256Digest();
            digest.update(bodyBytes, 0, bodyBytes.length);
            digest.doFinal(hash, 0);

            // RFC 6979 deterministic k rather than Bouncy Castle's default
            // RandomDSAKCalculator. Both are interoperable with the vendor SDK
            // (a verifier cannot tell how k was chosen), but a weak or misseeded
            // SecureRandom under random-k leaks the private scalar outright,
            // while deterministic k derives it from the key and the message
            // digest and so has no RNG dependency at all.
            ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
            signer.init(true, new ECPrivateKeyParameters(scalar, domain));
            BigInteger[] rs = signer.generateSignature(hash);

            return new DLSequence(
                            new ASN1Integer[] {new ASN1Integer(rs[0]), new ASN1Integer(rs[1])})
                    .getEncoded();
        } catch (IOException | RuntimeException e) {
            throw new TesserError.SigningError("Signing failed: " + e.getMessage(), e);
        }
    }
}
