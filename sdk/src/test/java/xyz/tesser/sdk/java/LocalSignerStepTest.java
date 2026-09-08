package xyz.tesser.sdk.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import xyz.tesser.sdk.java.internal.signing.Stamp;
import xyz.tesser.sdk.java.internal.signing.StampResult;
import xyz.tesser.sdk.java.internal.util.Json;

class LocalSignerStepTest {

    private static final SigningConfig CFG =
            new SigningConfig("02".repeat(33), "01".repeat(32), "org_local_signer_step_test");

    private static final StepForSigning STEP =
            new StepForSigning(
                    "step_abc",
                    "reb_123",
                    "0x02ed81893a850165a0bc",
                    "0xb909cbe4a348754b17b474df9f12ab8842020165",
                    "BASE_SEPOLIA");

    private static Stamp stubStamp() {
        Stamp stamp = mock(Stamp.class);
        when(stamp.stamp(any(), any()))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new StampResult("X-Stamp", "STAMP_VALUE")));
        return stamp;
    }

    @Test
    void signStepReturnsANonEmptyBase64Signature() throws Exception {
        SignedStepResult result = new LocalSigner(CFG, stubStamp()).signStep(STEP).get();
        assertThat(result.signature()).isNotBlank();
        assertThat(result.metadata().stampHeaderValue()).isEqualTo("STAMP_VALUE");
    }

    @Test
    void signStepBodyReferencesUnsignedTransactionAndSignWith() throws Exception {
        SignedStepResult result = new LocalSigner(CFG, stubStamp()).signStep(STEP).get();
        String composite =
                new String(Base64.getDecoder().decode(result.signature()), StandardCharsets.UTF_8);
        JsonNode body = Json.readTree(composite);
        assertThat(body.get("body").asText())
                .contains("ACTIVITY_TYPE_SIGN_TRANSACTION_V2")
                .contains(STEP.unsignedTransaction())
                .contains(STEP.signWith());
    }

    @Test
    void signStepEchoesTheUnsignedTransaction() throws Exception {
        SignedStepResult result = new LocalSigner(CFG, stubStamp()).signStep(STEP).get();
        assertThat(result.unsignedTransaction()).isEqualTo(STEP.unsignedTransaction());
    }

    @Test
    void signStepAcceptsExplicitOptionsAndTheOneArgOverloadEquivalently() throws Exception {
        // Deterministic clock is essential here, not incidental: bodies embed
        // timestampMs, so with the real clock this comparison fails whenever the
        // millisecond happens to flip between the two calls.
        LocalSigner signer = new LocalSigner(CFG, stubStamp(), () -> 1_700_000_000_000L);
        SignedStepResult withDefault = signer.signStep(STEP).get();
        SignedStepResult withExplicit = signer.signStep(STEP, new SignStepOptions()).get();
        assertThat(withDefault.metadata().body()).isEqualTo(withExplicit.metadata().body());
    }
}
