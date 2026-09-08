package xyz.tesser.sdk.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import xyz.tesser.sdk.java.internal.signing.Stamp;
import xyz.tesser.sdk.java.internal.signing.StampResult;
import xyz.tesser.sdk.java.internal.util.Json;

class LocalSignerTest {

    private static final SigningConfig CFG =
            new SigningConfig("02".repeat(33), "01".repeat(32), "org_local_signer_test");

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
