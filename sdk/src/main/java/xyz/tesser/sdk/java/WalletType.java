package xyz.tesser.sdk.java;

import xyz.tesser.sdk.java.error.TesserError;

/**
 * Wallet types the SDK can sign a wallet-creation request for. Tesser currently supports managed
 * wallets for {@link #STABLECOIN_ETHEREUM} only.
 */
public enum WalletType {
    STABLECOIN_ETHEREUM("stablecoin_ethereum"),
    STABLECOIN_SOLANA("stablecoin_solana"),
    STABLECOIN_STELLAR("stablecoin_stellar");

    private final String wireValue;

    WalletType(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The canonical wire string for this type. */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Resolves a wire-format string to its constant.
     *
     * @throws TesserError.ConfigError for unknown values
     */
    public static WalletType fromWireValue(String value) {
        for (WalletType t : values()) {
            if (t.wireValue.equals(value)) {
                return t;
            }
        }
        throw new TesserError.ConfigError("Unknown WalletType: '" + value + "'");
    }
}
