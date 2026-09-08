package xyz.tesser.sdk.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import xyz.tesser.sdk.java.error.TesserError;

class WalletTypeTest {

    @Test
    void wireValueReturnsTheCanonicalStringForEachVariant() {
        assertThat(WalletType.STABLECOIN_ETHEREUM.wireValue()).isEqualTo("stablecoin_ethereum");
        assertThat(WalletType.STABLECOIN_SOLANA.wireValue()).isEqualTo("stablecoin_solana");
        assertThat(WalletType.STABLECOIN_STELLAR.wireValue()).isEqualTo("stablecoin_stellar");
    }

    @Test
    void fromWireValueResolvesCanonicalStrings() {
        assertThat(WalletType.fromWireValue("stablecoin_ethereum"))
                .isEqualTo(WalletType.STABLECOIN_ETHEREUM);
        assertThat(WalletType.fromWireValue("stablecoin_solana"))
                .isEqualTo(WalletType.STABLECOIN_SOLANA);
        assertThat(WalletType.fromWireValue("stablecoin_stellar"))
                .isEqualTo(WalletType.STABLECOIN_STELLAR);
    }

    @Test
    void fromWireValueThrowsConfigErrorForUnknownValues() {
        assertThatThrownBy(() -> WalletType.fromWireValue("stablecoin_dogecoin"))
                .isInstanceOf(TesserError.ConfigError.class)
                .hasMessage("Unknown WalletType: 'stablecoin_dogecoin'");
    }
}
