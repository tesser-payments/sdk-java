package xyz.tesser.sdk.java.internal.signing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.tesser.sdk.java.WalletType;

class WalletTypeAccountsTest {

    @Test
    void everyWalletTypeHasAtLeastOneAccountSpec() {
        for (WalletType wt : WalletType.values()) {
            assertThat(WalletTypeAccounts.forType(wt))
                    .as("specs for %s", wt)
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    @Test
    void ethereumSpecUsesSecp256k1AndEthereumAddressFormat() {
        List<AccountSpec> specs = WalletTypeAccounts.forType(WalletType.STABLECOIN_ETHEREUM);
        assertThat(specs).hasSize(1);
        AccountSpec spec = specs.get(0);
        assertThat(spec.curve()).isEqualTo("CURVE_SECP256K1");
        assertThat(spec.pathFormat()).isEqualTo("PATH_FORMAT_BIP32");
        assertThat(spec.path()).isEqualTo("m/44'/60'/0'/0/0");
        assertThat(spec.addressFormat()).isEqualTo("ADDRESS_FORMAT_ETHEREUM");
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
