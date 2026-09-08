package xyz.tesser.sdk.java.internal.signing;

import java.util.List;
import java.util.Map;
import xyz.tesser.sdk.java.WalletType;

/**
 * Wallet type to account-spec lookup.
 *
 * <p>The Ethereum spec is verified end-to-end against Tesser staging. The Solana and Stellar specs
 * are NOT yet verified against the live API; if either fails for a spec-related reason, file the
 * API response so the spec can be adjusted. This caveat is carried over verbatim from the Kotlin
 * SDK.
 */
public final class WalletTypeAccounts {

    private static final Map<WalletType, List<AccountSpec>> SPECS =
            Map.of(
                    WalletType.STABLECOIN_ETHEREUM,
                    List.of(
                            new AccountSpec(
                                    "CURVE_SECP256K1",
                                    "PATH_FORMAT_BIP32",
                                    "m/44'/60'/0'/0/0",
                                    "ADDRESS_FORMAT_ETHEREUM")),
                    WalletType.STABLECOIN_SOLANA,
                    List.of(
                            new AccountSpec(
                                    "CURVE_ED25519",
                                    "PATH_FORMAT_BIP32",
                                    "m/44'/501'/0'/0'",
                                    "ADDRESS_FORMAT_SOLANA")),
                    WalletType.STABLECOIN_STELLAR,
                    List.of(
                            new AccountSpec(
                                    "CURVE_ED25519",
                                    "PATH_FORMAT_BIP32",
                                    "m/44'/148'/0'",
                                    "ADDRESS_FORMAT_XLM")));

    private WalletTypeAccounts() {}

    /** Returns the specs for {@code type}, or null when none are registered. */
    public static List<AccountSpec> forType(WalletType type) {
        return SPECS.get(type);
    }
}
