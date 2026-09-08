package xyz.tesser.sdk.java.internal.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.junit.jupiter.api.Test;
import xyz.tesser.sdk.java.SigningConfig;
import xyz.tesser.sdk.java.error.TesserError;
import xyz.tesser.sdk.java.internal.util.Json;

class ApiKeyStampTest {

    // Throwaway test-only P-256 scalar (private key = 1). NEVER use for real signing.
    private static final SigningConfig TEST_CFG =
            new SigningConfig(
                    "036b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
                    "0000000000000000000000000000000000000000000000000000000000000001",
                    "org_test");

    @Test
    void stampReturnsTheXStampHeaderName() throws Exception {
        StampResult result = new ApiKeyStamp().stamp(TEST_CFG, "{\"hello\":\"world\"}").get();
        assertThat(result.stampHeaderName()).isEqualTo("X-Stamp");
    }

    @Test
    void stampValueDecodesToTheCanonicalTurnkeyEnvelope() throws Exception {
        StampResult result = new ApiKeyStamp().stamp(TEST_CFG, "{\"hello\":\"world\"}").get();
        String decoded =
                new String(
                        Base64.getUrlDecoder().decode(result.stampHeaderValue()),
                        StandardCharsets.UTF_8);
        JsonNode obj = Json.readTree(decoded);

        assertThat(obj.get("publicKey").asText()).isEqualTo(TEST_CFG.publicKey());
        assertThat(obj.get("scheme").asText()).isEqualTo("SIGNATURE_SCHEME_TK_API_P256");

        String sigHex = obj.get("signature").asText();
        assertThat(sigHex).matches("^[0-9a-f]+$");
        // P-256 DER ECDSA: SEQUENCE header (2) + two INTEGERs of 1-33 value bytes
        // plus 2 header bytes each. ECDSA k is random, so r and s occasionally
        // encode short. The Kotlin test uses 138 as the floor; 136 is reachable
        // (both r and s 31 bytes, roughly 1 in 65,000 signatures) and the Java
        // suite generates more signatures than the Kotlin one does. Widened so a
        // genuine one-in-many-thousands run is not misread as a regression.
        assertThat(sigHex.length()).isBetween(136, 144);
    }

    @Test
    void stampRejectsNonAsciiHexDigitsExactlyAsKotlinDoes() {
        // Character.digit would accept both of these: it returns 5 for U+0665
        // (Arabic-Indic five) and 10 for U+FF21 (fullwidth A). Kotlin's ASCII-range
        // check rejects them, and so must this.
        for (String badKey : new String[] {"٥".repeat(64), "Ａ".repeat(64)}) {
            SigningConfig cfg =
                    new SigningConfig(TEST_CFG.publicKey(), badKey, TEST_CFG.enclaveId());
            assertThat(new ApiKeyStamp().stamp(cfg, "{}"))
                    .as("non-ASCII hex key %s must be rejected", badKey.charAt(0) + "...")
                    .isCompletedExceptionally();
        }
    }

    @Test
    void stampWrapsMalformedPrivateKeyAsSigningError() {
        SigningConfig malformed =
                new SigningConfig(TEST_CFG.publicKey(), "not-hex-zzz", TEST_CFG.enclaveId());
        assertThatThrownBy(() -> new ApiKeyStamp().stamp(malformed, "{}").get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TesserError.SigningError.class);
    }

    @Test
    void stampNeverThrowsSynchronously() {
        SigningConfig malformed =
                new SigningConfig(TEST_CFG.publicKey(), "not-hex-zzz", TEST_CFG.enclaveId());
        // Must return a failed future, not throw at the call site.
        assertThat(new ApiKeyStamp().stamp(malformed, "{}")).isCompletedExceptionally();
    }

    @Test
    void stampProducesASignatureThatVerifiesAgainstBodyAndPublicKey() throws Exception {
        String body = "{\"hello\":\"world\"}";
        StampResult result = new ApiKeyStamp().stamp(TEST_CFG, body).get();

        String decoded =
                new String(
                        Base64.getUrlDecoder().decode(result.stampHeaderValue()),
                        StandardCharsets.UTF_8);
        JsonNode obj = Json.readTree(decoded);
        byte[] sigBytes = hexToBytes(obj.get("signature").asText());
        byte[] pubBytes = hexToBytes(obj.get("publicKey").asText());

        BigInteger r;
        BigInteger s;
        try (ASN1InputStream in = new ASN1InputStream(sigBytes)) {
            ASN1Sequence seq = (ASN1Sequence) in.readObject();
            r = ((ASN1Integer) seq.getObjectAt(0)).getValue();
            s = ((ASN1Integer) seq.getObjectAt(1)).getValue();
        }

        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] hash = new byte[32];
        SHA256Digest digest = new SHA256Digest();
        digest.update(bodyBytes, 0, bodyBytes.length);
        digest.doFinal(hash, 0);

        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256r1");
        ECDomainParameters domain =
                new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH());
        ECPoint q = spec.getCurve().decodePoint(pubBytes);
        ECDSASigner verifier = new ECDSASigner();
        verifier.init(false, new ECPublicKeyParameters(q, domain));

        assertThat(verifier.verifySignature(hash, r, s)).isTrue();
    }

    @Test
    void stampIsDeterministicallyShapedAcrossRepeatedCalls() throws Exception {
        // ECDSA k is random, so signatures differ; the envelope shape must not.
        StampResult a = new ApiKeyStamp().stamp(TEST_CFG, "{}").get();
        StampResult b = new ApiKeyStamp().stamp(TEST_CFG, "{}").get();
        assertThat(a.stampHeaderName()).isEqualTo(b.stampHeaderName());
        assertThat(a.stampHeaderValue()).isNotEqualTo(b.stampHeaderValue());
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
