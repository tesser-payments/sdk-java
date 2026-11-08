package xyz.tesser.sdk.java.internal.signing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.tesser.sdk.java.WalletType;

class WalletTypeAccountsTest {

    @Test
    void everyLocallyBuiltWalletTypeHasExactlyOneAccountSpec() {
        for (WalletType wt : List.of(WalletType.STABLECOIN_SOLANA, WalletType.STABLECOIN_STELLAR)) {
            assertThat(WalletTypeAccounts.forType(wt)).as("specs for %s", wt).hasSize(1);
        }
    }

    @Test
    void ethereumHasNoLocalAccountSpec() {
        assertThat(WalletTypeAccounts.forType(WalletType.STABLECOIN_ETHEREUM)).isNull();
    }

    @Test
    void solanaSpecUsesEd25519AndSolanaAddressFormat() {
        AccountSpec spec = WalletTypeAccounts.forType(WalletType.STABLECOIN_SOLANA).get(0);
        assertThat(spec.curve()).isEqualTo("CURVE_ED25519");
        assertThat(spec.path()).isEqualTo("m/44'/501'/0'/0'");
        assertThat(spec.addressFormat()).isEqualTo("ADDRESS_FORMAT_SOLANA");
    }

    @Test
    void stellarSpecUsesEd25519AndXlmAddressFormat() {
        AccountSpec spec = WalletTypeAccounts.forType(WalletType.STABLECOIN_STELLAR).get(0);
        assertThat(spec.curve()).isEqualTo("CURVE_ED25519");
        assertThat(spec.path()).isEqualTo("m/44'/148'/0'");
        assertThat(spec.addressFormat()).isEqualTo("ADDRESS_FORMAT_XLM");
    }
}
