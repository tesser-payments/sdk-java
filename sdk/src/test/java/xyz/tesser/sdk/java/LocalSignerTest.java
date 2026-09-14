package xyz.tesser.sdk.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import xyz.tesser.sdk.java.error.TesserError;
import xyz.tesser.sdk.java.internal.signing.Stamp;
import xyz.tesser.sdk.java.internal.signing.StampResult;
import xyz.tesser.sdk.java.internal.util.Json;

class LocalSignerTest {

    private static final SigningConfig CFG = TestKeys.config("org_local_signer_test");

    private static Stamp stubStamp() {
        Stamp stamp = mock(Stamp.class);
        when(stamp.stamp(any(), any()))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new StampResult("X-Stamp", "STAMP_VALUE")));
        return stamp;
    }

    @Test
    void constructorAcceptsAFullyPopulatedSigningConfig() {
        assertThat(new LocalSigner(CFG).signing()).isEqualTo(CFG);
    }

    @Test
    void constructorRejectsBlankPublicKey() {
        assertThatThrownBy(() -> new LocalSigner(new SigningConfig("", "priv", "org")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publicKey");
    }

    @Test
    void constructorRejectsBlankPrivateKey() {
        assertThatThrownBy(() -> new LocalSigner(new SigningConfig("pub", "  ", "org")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("privateKey");
    }

    @Test
    void constructorRejectsBlankEnclaveId() {
        assertThatThrownBy(() -> new LocalSigner(new SigningConfig("pub", "priv", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enclaveId");
    }

    @Test
    void constructorRejectsAPublicKeyThatIsNotTheKeysPrivateScalarsPoint() {
        // A well-formed but unrelated point: G doubled, i.e. the public key for
        // private scalar 2 rather than 1.
        String otherKey = "03" + "7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978";
        assertThatThrownBy(
                        () ->
                                new LocalSigner(
                                        new SigningConfig(otherKey, TestKeys.PRIVATE_KEY, "org")))
                .isInstanceOf(TesserError.ConfigError.class)
                .hasMessageContaining("is not the public key for");
    }

    @Test
    void constructorRejectsAMalformedPrivateKeyBeforeAnySigningIsAttempted() {
        assertThatThrownBy(
                        () ->
                                new LocalSigner(
                                        new SigningConfig(
                                                TestKeys.PUBLIC_KEY, "not-hex-zzz", "org")))
                .isInstanceOf(TesserError.ConfigError.class)
                .hasMessageContaining("privateKey");
    }

    @Test
    void constructorRejectsAPrivateScalarOutsideTheCurveOrder() {
        assertThatThrownBy(
                        () ->
                                new LocalSigner(
                                        new SigningConfig(
                                                TestKeys.PUBLIC_KEY, "00".repeat(32), "org")))
                .isInstanceOf(TesserError.ConfigError.class)
                .hasMessageContaining("[1, n-1]");
    }

    @Test
    void constructorErrorForAMalformedPrivateKeyDoesNotEchoTheKey() {
        // The message can end up in a log; the offending value is key material.
        assertThatThrownBy(
                        () ->
                                new LocalSigner(
                                        new SigningConfig(TestKeys.PUBLIC_KEY, "abcdefzz", "org")))
                .isInstanceOf(TesserError.ConfigError.class)
                .hasMessageNotContaining("abcdefzz");
    }

    @Test
    void constructorErrorForAnUnparseablePublicKeyDoesNotEchoTheKey() {
        // Anything reaching the publicKey branch failed to decode as a curve
        // point, so it is not a public key; a misdirected env var can land
        // another private scalar here, and the message must not carry it into a
        // log. A 32-byte scalar never decodes (a point is 33 or 65 bytes).
        String strayScalar = "c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721";
        assertThatThrownBy(
                        () ->
                                new LocalSigner(
                                        new SigningConfig(
                                                strayScalar, TestKeys.PRIVATE_KEY, "org")))
                .isInstanceOf(TesserError.ConfigError.class)
                .hasMessageContaining("publicKey")
                .hasMessageNotContaining(strayScalar);
    }

    @Test
    void transposedKeyArgumentsAreRejectedByThePrivateKeyCheckWithoutEchoingEither() {
        // SigningConfig(publicKey, privateKey, ...) is easy to get backwards. A
        // 33-byte public key read as a scalar is out of [1, n-1], so this fails
        // on the privateKey branch before the publicKey branch is reached.
        assertThatThrownBy(
                        () ->
                                new LocalSigner(
                                        new SigningConfig(
                                                TestKeys.PRIVATE_KEY, TestKeys.PUBLIC_KEY, "org")))
                .isInstanceOf(TesserError.ConfigError.class)
                .hasMessageContaining("privateKey")
                .hasMessageNotContaining(TestKeys.PUBLIC_KEY)
                .hasMessageNotContaining(TestKeys.PRIVATE_KEY);
    }

    @Test
    void constructorComparesPointsNotHexSoOtherEncodingsOfTheSameKeyPass() {
        // Uppercase compressed, and the uncompressed (04 || x || y) form of the
        // same generator point. Both are the correct key, just written differently,
        // and the check must not turn into an opinion about encoding.
        String uppercase = TestKeys.PUBLIC_KEY.toUpperCase(java.util.Locale.ROOT);
        String uncompressed =
                "04"
                        + "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
                        + "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5";
        assertThat(new LocalSigner(new SigningConfig(uppercase, TestKeys.PRIVATE_KEY, "org")))
                .isNotNull();
        assertThat(new LocalSigner(new SigningConfig(uncompressed, TestKeys.PRIVATE_KEY, "org")))
                .isNotNull();
    }

    @Test
    void signStepRejectsNullOptionsAsAFailedFutureRatherThanThrowing() {
        LocalSigner signer = new LocalSigner(CFG, stubStamp());
        StepForSigning step = new StepForSigning("s", "t", "0x00", "0xabc", "BASE_SEPOLIA");

        // NullPointerException, not a TesserError: null arguments are a
        // programming error, and every other null in this SDK is rejected the
        // same way (see recordsRejectNullWithNpeMatchingTheKotlinDataClasses).
        // It still arrives as a failed future rather than a synchronous throw.
        assertThat(signer.signStep(step, null))
                .failsWithin(Duration.ZERO)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(NullPointerException.class);
    }

    @Test
    void signCreateWalletReturnsANonEmptySignature() throws Exception {
        LocalSigner signer = new LocalSigner(CFG, stubStamp());
        SignedResult result =
                signer.signCreateWallet(
                                new CreateWalletParams("my wallet", WalletType.STABLECOIN_ETHEREUM))
                        .get();
        assertThat(result.signature()).isNotBlank();
        assertThat(result.metadata().stampHeaderValue()).isEqualTo("STAMP_VALUE");
    }

    @Test
    void signCreateWalletIncludesTheWalletNameVerbatim() throws Exception {
        LocalSigner signer = new LocalSigner(CFG, stubStamp());
        SignedResult result =
                signer.signCreateWallet(
                                new CreateWalletParams(
                                        "verbatim-name-123", WalletType.STABLECOIN_ETHEREUM))
                        .get();
        String composite =
                new String(Base64.getDecoder().decode(result.signature()), StandardCharsets.UTF_8);
        JsonNode body = Json.readTree(composite);
        assertThat(body.get("body").asText()).contains("verbatim-name-123");
    }

    @Test
    void signCreateWalletForSolanaUsesEd25519() throws Exception {
        LocalSigner signer = new LocalSigner(CFG, stubStamp());
        SignedResult result =
                signer.signCreateWallet(new CreateWalletParams("sol", WalletType.STABLECOIN_SOLANA))
                        .get();
        assertThat(result.metadata().body())
                .contains("CURVE_ED25519")
                .contains("ADDRESS_FORMAT_SOLANA");
    }
}
