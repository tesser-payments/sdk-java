package xyz.tesser.sdk.java.internal.signing;

import java.util.List;
import java.util.Map;
import xyz.tesser.sdk.java.WalletType;

/**
 * Wallet type to account-spec lookup for the locally-built {@code ACTIVITY_TYPE_CREATE_WALLET}
 * body. Ethereum has no entry: its body is built by Tesser (see {@link UnsignedPayload}).
 *
 * <p>The Solana and Stellar specs are NOT yet verified against the live API; if either fails for a
 * spec-related reason, file the API response so the spec can be adjusted. This caveat is carried
 * over verbatim from the Kotlin SDK.
 */
public final class WalletTypeAccounts {

    private static final Map<WalletType, List<AccountSpec>> SPECS =
            Map.of(
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
