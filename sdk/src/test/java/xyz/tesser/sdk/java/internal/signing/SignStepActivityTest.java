package xyz.tesser.sdk.java.internal.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.tesser.sdk.java.SignStepOptions;
import xyz.tesser.sdk.java.SignedStepResult;
import xyz.tesser.sdk.java.SigningConfig;
import xyz.tesser.sdk.java.StepForSigning;
import xyz.tesser.sdk.java.error.TesserError;
import xyz.tesser.sdk.java.internal.util.Json;

class SignStepActivityTest {

    private static final SigningConfig CFG =
            new SigningConfig("02".repeat(33), "01".repeat(32), "org_test_step");

    private static final StepForSigning STEP =
            new StepForSigning(
                    "step_abc",
                    "reb_123",
                    "0x02ed81893a850165a0bc0085012a05f200825208949c4e7f2b1d8a4e6cb3f58a2d6e9b1c4f880de0b6b3a764000080c0",
                    "0xb909cbe4a348754b17b474df9f12ab8842020165",
                    "BASE_SEPOLIA");

    private static final LongSupplier FIXED_CLOCK = () -> 1_700_000_000_000L;

    private static Stamp stubStamp() {
        Stamp stamp = mock(Stamp.class);
        when(stamp.stamp(any(), any()))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new StampResult("X-Stamp", "FAKE_STAMP_VALUE")));
        return stamp;
    }

    @Test
    void stampsATurnkeySignTransactionV2Body() throws Exception {
        Stamp stamp = stubStamp();
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        SignStepActivity.sign(CFG, STEP, new SignStepOptions(), stamp, FIXED_CLOCK).get();

        verify(stamp).stamp(any(), bodyCaptor.capture());
        JsonNode stamped = Json.readTree(bodyCaptor.getValue());
        assertThat(stamped.get("type").asText()).isEqualTo("ACTIVITY_TYPE_SIGN_TRANSACTION_V2");
        assertThat(stamped.get("organizationId").asText()).isEqualTo(CFG.enclaveId());
        JsonNode params = stamped.get("parameters");
        assertThat(params.get("signWith").asText()).isEqualTo(STEP.signWith());
        assertThat(params.get("unsignedTransaction").asText())
                .isEqualTo(STEP.unsignedTransaction());
        assertThat(params.get("type").asText()).isEqualTo("TRANSACTION_TYPE_ETHEREUM");
    }

    @Test
    void returnsABase64CompositeContainingBodyAndStamp() throws Exception {
        SignedStepResult result =
                SignStepActivity.sign(CFG, STEP, new SignStepOptions(), stubStamp(), FIXED_CLOCK)
                        .get();
        String decoded =
                new String(Base64.getDecoder().decode(result.signature()), StandardCharsets.UTF_8);
        JsonNode composite = Json.readTree(decoded);
        assertThat(composite.get("stamp").asText()).isEqualTo("FAKE_STAMP_VALUE");
        JsonNode innerBody = Json.readTree(composite.get("body").asText());
        assertThat(innerBody.get("type").asText()).isEqualTo("ACTIVITY_TYPE_SIGN_TRANSACTION_V2");
    }

    @Test
    void echoesTheUnsignedTransactionOnTheResult() throws Exception {
        SignedStepResult result =
                SignStepActivity.sign(CFG, STEP, new SignStepOptions(), stubStamp(), FIXED_CLOCK)
                        .get();
        assertThat(result.unsignedTransaction()).isEqualTo(STEP.unsignedTransaction());
    }

    @Test
    void metadataThreadsThroughStampValuesAndTheStampedBody() throws Exception {
        SignedStepResult result =
                SignStepActivity.sign(CFG, STEP, new SignStepOptions(), stubStamp(), FIXED_CLOCK)
                        .get();
        assertThat(result.metadata().stampHeaderName()).isEqualTo("X-Stamp");
        assertThat(result.metadata().stampHeaderValue()).isEqualTo("FAKE_STAMP_VALUE");
        assertThat(result.metadata().body())
                .contains("ACTIVITY_TYPE_SIGN_TRANSACTION_V2")
                .contains(STEP.unsignedTransaction());
    }

    @Test
    void solanaNetworkMapsToTransactionTypeSolana() throws Exception {
        StepForSigning solanaStep =
                new StepForSigning(
                        STEP.id(),
                        STEP.transferId(),
                        STEP.unsignedTransaction(),
                        "9wXn6solBase58Address",
                        "SOLANA");
        Stamp stamp = stubStamp();
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        SignStepActivity.sign(CFG, solanaStep, new SignStepOptions(), stamp, FIXED_CLOCK).get();

        verify(stamp).stamp(any(), bodyCaptor.capture());
        JsonNode params = Json.readTree(bodyCaptor.getValue()).get("parameters");
        assertThat(params.get("type").asText()).isEqualTo("TRANSACTION_TYPE_SOLANA");
    }

    @Test
    void ethereumSepoliaMapsToTransactionTypeEthereum() throws Exception {
        StepForSigning sepolia =
                new StepForSigning(
                        STEP.id(),
                        STEP.transferId(),
                        STEP.unsignedTransaction(),
                        STEP.signWith(),
                        "ETHEREUM_SEPOLIA");
        Stamp stamp = stubStamp();
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        SignStepActivity.sign(CFG, sepolia, new SignStepOptions(), stamp, FIXED_CLOCK).get();

        verify(stamp).stamp(any(), bodyCaptor.capture());
        JsonNode params = Json.readTree(bodyCaptor.getValue()).get("parameters");
        assertThat(params.get("type").asText()).isEqualTo("TRANSACTION_TYPE_ETHEREUM");
    }

    @Test
    void unknownNetworkFailsTheFutureBeforeAnyStamping() {
        StepForSigning unknown =
                new StepForSigning(
                        STEP.id(),
                        STEP.transferId(),
                        STEP.unsignedTransaction(),
                        STEP.signWith(),
                        "MARS_TESTNET");
        Stamp stamp = stubStamp();

        CompletableFuture<SignedStepResult> future =
                SignStepActivity.sign(CFG, unknown, new SignStepOptions(), stamp, FIXED_CLOCK);

        assertThat(future).isCompletedExceptionally();
        assertThat(future)
                .failsWithin(java.time.Duration.ZERO)
                .withThrowableOfType(java.util.concurrent.ExecutionException.class)
                .withCauseInstanceOf(TesserError.ConfigError.class);
        org.mockito.Mockito.verifyNoInteractions(stamp);
    }

    @Test
    void keyOrderMatchesTheKotlinSdkExactly() throws Exception {
        SignedStepResult result =
                SignStepActivity.sign(CFG, STEP, new SignStepOptions(), stubStamp(), FIXED_CLOCK)
                        .get();
        assertThat(result.metadata().body())
                .startsWith(
                        "{\"type\":\"ACTIVITY_TYPE_SIGN_TRANSACTION_V2\",\"timestampMs\":\"1700000000000\","
                                + "\"organizationId\":\"org_test_step\",\"parameters\":{\"signWith\":\"");
    }
}
