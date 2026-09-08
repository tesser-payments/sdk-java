package xyz.tesser.sdk.java;

import java.util.Objects;

/**
 * A rebalance step needing a local signature before Tesser can execute it.
 * Values come from the {@code data.object} field of a
 * {@code step.signature_requested} webhook event, or from
 * {@code GET /v1/treasury/rebalances/{id}}. See
 * https://docs.tesser.xyz/how-tos/rebalance-funds.
 *
 * @param id step ID; the {@code {stepId}} path component on the /sign endpoint
 * @param transferId parent transfer ID; the {@code {transferId}} path component
 * @param unsignedTransaction hex-encoded raw transaction bytes prepared by
 *     Tesser, for example an EIP-1559 transaction beginning {@code 0x02}
 * @param signWith on-chain address that must sign; from
 *     {@code GET /v1/accounts/{from_account_id}} field {@code crypto_wallet_address}
 * @param network Tesser network identifier from {@code step.from_network}, e.g.
 *     {@code BASE_SEPOLIA}. Unsupported values fail at sign time with
 *     {@link xyz.tesser.sdk.java.error.TesserError.ConfigError}.
 */
public record StepForSigning(
        String id, String transferId, String unsignedTransaction, String signWith, String network) {

    /** Rejects nulls with NPE, matching the Kotlin data class. See {@link CreateWalletParams}. */
    public StepForSigning {
        Objects.requireNonNull(id, "StepForSigning.id must not be null");
        Objects.requireNonNull(transferId, "StepForSigning.transferId must not be null");
        Objects.requireNonNull(
                unsignedTransaction, "StepForSigning.unsignedTransaction must not be null");
        Objects.requireNonNull(signWith, "StepForSigning.signWith must not be null");
        Objects.requireNonNull(network, "StepForSigning.network must not be null");
    }
}
