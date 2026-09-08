package xyz.tesser.sdk.java;

import java.util.Objects;

/**
 * Parameters for {@link LocalSigner#signCreateWallet}.
 *
 * @param name human-readable wallet name surfaced in the Tesser dashboard. No constraints beyond
 *     what Tesser's API enforces.
 * @param type which wallet type (curve, path, addressFormat) to derive
 */
public record CreateWalletParams(String name, WalletType type) {

    /**
     * Rejects nulls to match what a Java caller sees from the Kotlin SDK.
     *
     * <p>Kotlin data-class constructors emit {@code Intrinsics.checkNotNullParameter}, which throws
     * {@link NullPointerException} for a null argument (Kotlin 1.4+ switched parameter null-checks
     * from IAE to NPE — verified against {@code sdk-0.0.4.jar}, which reports "Parameter specified
     * as non-null is null"). A bare record would instead accept null and serialize {@code
     * "walletName":null}, producing a well-formed body that gets signed and then rejected upstream.
     * {@code requireNonNull} reproduces both the behaviour and the exception type.
     */
    public CreateWalletParams {
        Objects.requireNonNull(name, "CreateWalletParams.name must not be null");
        Objects.requireNonNull(type, "CreateWalletParams.type must not be null");
    }
}
