package xyz.tesser.sdk.java.internal.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.tesser.sdk.java.CreateWalletParams;
import xyz.tesser.sdk.java.SignedResult;
import xyz.tesser.sdk.java.SigningConfig;
import xyz.tesser.sdk.java.WalletType;
import xyz.tesser.sdk.java.internal.util.Json;

class CreateWalletActivityTest {

    private static final SigningConfig CFG =
            new SigningConfig("02".repeat(33), "01".repeat(32), "org_test_123");

    private static final LongSupplier FIXED_CLOCK = () -> 1_700_000_000_000L;

    private static Stamp mockStamp() {
        Stamp stamp = mock(Stamp.class);
        when(stamp.stamp(any(), any()))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new StampResult("X-Stamp", "FAKE_STAMP_VALUE")));
        return stamp;
    }

    @Test
    void buildsAnActivityTypeCreateWalletPayload() throws Exception {
        SignedResult result =
                CreateWalletActivity.sign(
                                CFG,
                                new CreateWalletParams("test wallet", WalletType.STABLECOIN_ETHEREUM),
                                mockStamp(),
                                FIXED_CLOCK)
                        .get();

        JsonNode body = Json.readTree(result.metadata().body());
        assertThat(body.get("type").asText()).isEqualTo("ACTIVITY_TYPE_CREATE_WALLET");
        assertThat(body.get("organizationId").asText()).isEqualTo("org_test_123");
        assertThat(body.get("timestampMs").asText()).isEqualTo("1700000000000");
    }

    @Test
    void parametersCarryWalletNameAndAccounts() throws Exception {
        SignedResult result =
                CreateWalletActivity.sign(
                                CFG,
                                new CreateWalletParams("alpha", WalletType.STABLECOIN_ETHEREUM),
                                mockStamp(),
                                FIXED_CLOCK)
                        .get();

        JsonNode params = Json.readTree(result.metadata().body()).get("parameters");
        assertThat(params.get("walletName").asText()).isEqualTo("alpha");
        JsonNode accounts = params.get("accounts");
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).get("curve").asText()).isEqualTo("CURVE_SECP256K1");
        assertThat(accounts.get(0).get("pathFormat").asText()).isEqualTo("PATH_FORMAT_BIP32");
        assertThat(accounts.get(0).get("path").asText()).isEqualTo("m/44'/60'/0'/0/0");
        assertThat(accounts.get(0).get("addressFormat").asText())
                .isEqualTo("ADDRESS_FORMAT_ETHEREUM");
    }

    @Test
    void returnsBase64CompositeSignatureContainingBodyAndStamp() throws Exception {
        SignedResult result =
                CreateWalletActivity.sign(
                                CFG,
                                new CreateWalletParams("alpha", WalletType.STABLECOIN_ETHEREUM),
                                mockStamp(),
                                FIXED_CLOCK)
                        .get();

        String decoded =
                new String(Base64.getDecoder().decode(result.signature()), StandardCharsets.UTF_8);
        JsonNode composite = Json.readTree(decoded);
        assertThat(composite.get("body").asText()).contains("ACTIVITY_TYPE_CREATE_WALLET");
        assertThat(composite.get("stamp").asText()).isEqualTo("FAKE_STAMP_VALUE");
    }

    @Test
    void metadataThreadsThroughStampHeaderValues() throws Exception {
        SignedResult result =
                CreateWalletActivity.sign(
                                CFG,
                                new CreateWalletParams("alpha", WalletType.STABLECOIN_ETHEREUM),
                                mockStamp(),
                                FIXED_CLOCK)
                        .get();
        assertThat(result.metadata().stampHeaderName()).isEqualTo("X-Stamp");
        assertThat(result.metadata().stampHeaderValue()).isEqualTo("FAKE_STAMP_VALUE");
    }

    @Test
    void theExactBodyIsWhatGetsStamped() throws Exception {
        Stamp stamp = mockStamp();
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        SignedResult result =
                CreateWalletActivity.sign(
                                CFG,
                                new CreateWalletParams("alpha", WalletType.STABLECOIN_ETHEREUM),
                                stamp,
                                FIXED_CLOCK)
                        .get();

        org.mockito.Mockito.verify(stamp).stamp(any(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).isEqualTo(result.metadata().body());
    }

    @Test
    void solanaUsesEd25519CurveInThePayload() throws Exception {
        SignedResult result =
                CreateWalletActivity.sign(
                                CFG,
                                new CreateWalletParams("sol", WalletType.STABLECOIN_SOLANA),
                                mockStamp(),
                                FIXED_CLOCK)
                        .get();
        assertThat(result.metadata().body())
                .contains("CURVE_ED25519")
                .contains("ADDRESS_FORMAT_SOLANA");
    }

    @Test
    void keyOrderMatchesTheKotlinSdkExactly() throws Exception {
        // Byte-identical output depends on insertion order, not just content.
        SignedResult result =
                CreateWalletActivity.sign(
                                CFG,
                                new CreateWalletParams("a", WalletType.STABLECOIN_ETHEREUM),
                                mockStamp(),
                                FIXED_CLOCK)
                        .get();
        assertThat(result.metadata().body())
                .startsWith(
                        "{\"type\":\"ACTIVITY_TYPE_CREATE_WALLET\",\"timestampMs\":\"1700000000000\","
                                + "\"organizationId\":\"org_test_123\",\"parameters\":{\"walletName\":\"a\","
                                + "\"accounts\":[{\"curve\":\"CURVE_SECP256K1\",\"pathFormat\":\"PATH_FORMAT_BIP32\",");
    }
}
