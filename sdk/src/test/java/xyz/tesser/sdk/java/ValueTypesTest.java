package xyz.tesser.sdk.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ValueTypesTest {

    @Test
    void signedStepResultExposesSignatureUnsignedTransactionAndMetadata() {
        SignedStepResultMetadata meta =
                new SignedStepResultMetadata("X-Stamp", "STAMP_VALUE", "0xdeadbeef");
        SignedStepResult result = new SignedStepResult("BASE64_SIG", "0xdeadbeef", meta);
        assertThat(result.signature()).isEqualTo("BASE64_SIG");
        assertThat(result.unsignedTransaction()).isEqualTo("0xdeadbeef");
        assertThat(result.metadata()).isEqualTo(meta);
    }

    @Test
    void metadataEqualityIsStructural() {
        SignedStepResultMetadata a = new SignedStepResultMetadata("X-Stamp", "v", "b");
        SignedStepResultMetadata b = new SignedStepResultMetadata("X-Stamp", "v", "b");
        SignedStepResultMetadata c = new SignedStepResultMetadata("X-Stamp", "v", "different");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void stepForSigningEqualityIsStructuralAcrossAllFields() {
        StepForSigning a = new StepForSigning("step_1", "reb_1", "0xaa", "0xb0", "ETHEREUM");
        StepForSigning b = new StepForSigning("step_1", "reb_1", "0xaa", "0xb0", "ETHEREUM");
        StepForSigning c = new StepForSigning("step_1", "reb_1", "0xaa", "0xb0", "BASE_SEPOLIA");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void stepForSigningSupportsFieldOverrideViaWithers() {
        // Kotlin's data-class copy() has no Java equivalent; a record is rebuilt
        // explicitly. This test pins the accessor names the examples rely on.
        StepForSigning step =
                new StepForSigning("step_abc", "reb_123", "0x02ed", "0xb909", "BASE_SEPOLIA");
        StepForSigning renamed =
                new StepForSigning(
                        "step_xyz",
                        step.transferId(),
                        step.unsignedTransaction(),
                        step.signWith(),
                        step.network());
        assertThat(renamed.id()).isEqualTo("step_xyz");
        assertThat(renamed.transferId()).isEqualTo("reb_123");
        assertThat(renamed.network()).isEqualTo("BASE_SEPOLIA");
    }

    @Test
    void signingConfigExposesAllThreeFields() {
        SigningConfig cfg = new SigningConfig("pub", "priv", "org_1");
        assertThat(cfg.publicKey()).isEqualTo("pub");
        assertThat(cfg.privateKey()).isEqualTo("priv");
        assertThat(cfg.enclaveId()).isEqualTo("org_1");
    }

    @Test
    void signedResultExposesSignatureAndMetadata() {
        SignedResultMetadata meta = new SignedResultMetadata("X-Stamp", "v", "{}");
        SignedResult result = new SignedResult("SIG", meta);
        assertThat(result.signature()).isEqualTo("SIG");
        assertThat(result.metadata().body()).isEqualTo("{}");
    }

    @Test
    void recordsRejectNullWithNpeMatchingTheKotlinDataClasses() {
        // Verified against sdk-0.0.4.jar: the Kotlin constructors throw
        // NullPointerException ("Parameter specified as non-null is null"), not
        // IllegalArgumentException. Without these checks a null wallet name would
        // serialize as "walletName":null and get signed.
        assertThatThrownBy(() -> new CreateWalletParams(null, WalletType.STABLECOIN_ETHEREUM))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CreateWalletParams("w", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StepForSigning("i", "t", null, "0xb0", "ETHEREUM"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SigningConfig(null, "priv", "org"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SignedResultMetadata("X-Stamp", "v", null))
                .isInstanceOf(NullPointerException.class);
    }
}
