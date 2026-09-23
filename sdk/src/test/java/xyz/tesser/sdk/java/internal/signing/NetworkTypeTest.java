package xyz.tesser.sdk.java.internal.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import xyz.tesser.sdk.java.error.TesserError;

class NetworkTypeTest {

    @ParameterizedTest
    @CsvSource({
        "BASE,             TRANSACTION_TYPE_ETHEREUM",
        "BASE_SEPOLIA,     TRANSACTION_TYPE_ETHEREUM",
        "ETHEREUM,         TRANSACTION_TYPE_ETHEREUM",
        "ETHEREUM_SEPOLIA, TRANSACTION_TYPE_ETHEREUM",
        "POLYGON,          TRANSACTION_TYPE_ETHEREUM",
        "POLYGON_AMOY,     TRANSACTION_TYPE_ETHEREUM",
        "SOLANA,           TRANSACTION_TYPE_SOLANA",
        "TEMPO,            TRANSACTION_TYPE_ETHEREUM"
    })
    void mapsEverySupportedNetwork(String network, String expected) {
        assertThat(NetworkType.toTurnkeyType(network)).isEqualTo(expected);
    }

    @Test
    void unknownNetworkThrowsConfigErrorListingSupportedNetworksSorted() {
        assertThatThrownBy(() -> NetworkType.toTurnkeyType("MARS_TESTNET"))
                .isInstanceOf(TesserError.ConfigError.class)
                .hasMessageContaining("MARS_TESTNET")
                .hasMessageContaining(
                        "BASE, BASE_SEPOLIA, ETHEREUM, ETHEREUM_SEPOLIA, POLYGON, POLYGON_AMOY,"
                                + " SOLANA, TEMPO");
    }

    @Test
    void lookupIsCaseSensitiveMatchingTheKotlinSdk() {
        assertThatThrownBy(() -> NetworkType.toTurnkeyType("base_sepolia"))
                .isInstanceOf(TesserError.ConfigError.class);
    }
}
