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
import xyz.tesser.sdk.java.internal.util.Hex;
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
        // plus 2 header bytes each. r and s can encode short when their leading
        // bytes are zero, so this stays a range rather than an exact length even
        // though k is now deterministic; the range is a property of DER, not of
        // the nonce. The Kotlin test's floor of 138 is widened to 136 because both
        // r and s encoding at 31 bytes is reachable.
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
    void stampIsByteIdenticalForTheSameKeyAndBody() throws Exception {
        // RFC 6979 derives k from the key and the message digest, so repeating a
        // stamp reproduces it exactly. Under the previous random-k calculator this
        // assertion was inverted.
        StampResult a = new ApiKeyStamp().stamp(TEST_CFG, "{}").get();
        StampResult b = new ApiKeyStamp().stamp(TEST_CFG, "{}").get();
        assertThat(a.stampHeaderName()).isEqualTo(b.stampHeaderName());
        assertThat(a.stampHeaderValue()).isEqualTo(b.stampHeaderValue());
    }

    @Test
    void stampStillDiffersWhenTheBodyDiffers() throws Exception {
        // Determinism must come from the input, not from a constant signature.
        StampResult a = new ApiKeyStamp().stamp(TEST_CFG, "{\"a\":1}").get();
        StampResult b = new ApiKeyStamp().stamp(TEST_CFG, "{\"a\":2}").get();
        assertThat(a.stampHeaderValue()).isNotEqualTo(b.stampHeaderValue());
    }

    @Test
    void deterministicNonceMatchesTheRfc6979TestVectorForP256WithSha256() throws Exception {
        // RFC 6979 A.2.5: key x = C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721,
        // message "sample", SHA-256 => the r and s below. Signing that message
        // through the stamper must reproduce them exactly; if Bouncy Castle's
        // k calculator were ever swapped back to random, this fails immediately.
        String x = "C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721";
        // The stamper does not check the pair (LocalSigner does), but deriving the
        // public key keeps the fixture honest rather than carrying a copied constant.
        String pub = Hex.encode(P256.publicPoint(new BigInteger(x, 16)).getEncoded(true));
        SigningConfig cfg = new SigningConfig(pub, x, "org_rfc6979");
        StampResult result = new ApiKeyStamp().stamp(cfg, "sample").get();
        JsonNode obj =
                Json.readTree(
                        new String(
                                Base64.getUrlDecoder().decode(result.stampHeaderValue()),
                                StandardCharsets.UTF_8));
        byte[] sigBytes = hexToBytes(obj.get("signature").asText());
        try (ASN1InputStream in = new ASN1InputStream(sigBytes)) {
            ASN1Sequence seq = (ASN1Sequence) in.readObject();
            assertThat(((ASN1Integer) seq.getObjectAt(0)).getValue())
                    .isEqualTo(
                            new BigInteger(
                                    "EFD48B2AACB6A8FD1140DD9CD45E81D69D2C877B56AAF991C34D0EA84EAF3716",
                                    16));
            assertThat(((ASN1Integer) seq.getObjectAt(1)).getValue())
                    .isEqualTo(
                            new BigInteger(
                                    "F7CB1C942D657C41D436C7A1B6E29F65F3E900DBB9AFF4064DC4AB2F843ACDA8",
                                    16));
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
