package xyz.tesser.sdk.java.internal.signing;

import java.util.Map;
import java.util.stream.Collectors;
import xyz.tesser.sdk.java.error.TesserError;

/**
 * Maps a Tesser network identifier ({@code step.from_network} /
 * {@code step.to_network}) to the Turnkey {@code TRANSACTION_TYPE_*} used in
 * {@code parameters.type} of an {@code ACTIVITY_TYPE_SIGN_TRANSACTION_V2}
 * activity.
 *
 * <p>EVM-family networks all map to {@code TRANSACTION_TYPE_ETHEREUM} because
 * Turnkey keys the type by signing scheme, not by chain ID.
 */
public final class NetworkType {

    private static final Map<String, String> NETWORK_TO_TURNKEY_TYPE =
            Map.of(
                    "BASE", "TRANSACTION_TYPE_ETHEREUM",
                    "BASE_SEPOLIA", "TRANSACTION_TYPE_ETHEREUM",
                    "ETHEREUM", "TRANSACTION_TYPE_ETHEREUM",
                    "ETHEREUM_SEPOLIA", "TRANSACTION_TYPE_ETHEREUM",
                    "POLYGON", "TRANSACTION_TYPE_ETHEREUM",
                    "POLYGON_AMOY", "TRANSACTION_TYPE_ETHEREUM",
                    "SOLANA", "TRANSACTION_TYPE_SOLANA");

    private NetworkType() {}

    /**
     * @throws TesserError.ConfigError for unsupported networks
     */
    public static String toTurnkeyType(String network) {
        String type = NETWORK_TO_TURNKEY_TYPE.get(network);
        if (type == null) {
            String supported =
                    NETWORK_TO_TURNKEY_TYPE.keySet().stream()
                            .sorted()
                            .collect(Collectors.joining(", "));
            throw new TesserError.ConfigError(
                    "Unsupported network for step signing: '"
                            + network
                            + "'. Supported networks: "
                            + supported
                            + ".");
        }
        return type;
    }
}
