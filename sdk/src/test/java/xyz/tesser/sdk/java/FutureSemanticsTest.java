package xyz.tesser.sdk.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import xyz.tesser.sdk.java.error.TesserError;
import xyz.tesser.sdk.java.internal.signing.Stamp;
import xyz.tesser.sdk.java.internal.signing.StubHttpClient;
import xyz.tesser.sdk.java.internal.signing.StubHttpResponse;
import xyz.tesser.sdk.java.internal.signing.UnsignedPayloadFixtures;

/**
 * Pins the two rules that make a CompletableFuture-returning API safe, so they cannot regress when
 * real I/O is added to the stamper.
 */
class FutureSemanticsTest {

    private static final SigningConfig CFG =
            TestKeys.config(UnsignedPayloadFixtures.organizationId());

    private static final String API_BASE_URL = "https://api.tesser.xyz";

    private static LocalSigner signerReturningTheEthereumFixture() {
        return new LocalSigner(
                CFG,
                API_BASE_URL,
                StubHttpClient.respondingWith(
                        new StubHttpResponse(200, UnsignedPayloadFixtures.loadEthereumResponse())));
    }

    private static CreateWalletParams ethereumParams() {
        return new CreateWalletParams(
                UnsignedPayloadFixtures.walletName(), WalletType.STABLECOIN_ETHEREUM);
    }

    @Test
    void unsupportedNetworkFailsTheFutureRatherThanThrowingSynchronously() {
        LocalSigner signer = new LocalSigner(CFG, API_BASE_URL);
        StepForSigning bad = new StepForSigning("s", "t", "0x00", "0xabc", "MARS_TESTNET");

        // The call itself must not throw.
        CompletableFuture<SignedStepResult> future = signer.signStep(bad);

        assertThat(future).isCompletedExceptionally();
        assertThat(future)
                .failsWithin(Duration.ZERO)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(TesserError.ConfigError.class);
    }

    @Test
    void stamperFailureFailsTheFutureRatherThanThrowingSynchronously() {
        // A malformed private key can no longer reach this path (LocalSigner's
        // constructor rejects it), so the stamper failure is injected directly.
        // This is the case that matters once the stamper does real I/O.
        Stamp exploding = mock(Stamp.class);
        when(exploding.stamp(any(), any()))
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new TesserError.SigningError("co-signer unreachable")));
        LocalSigner signer = new LocalSigner(CFG, API_BASE_URL, StubHttpClient.unused(), exploding);

        CompletableFuture<SignedResult> future = signer.signCreateWallet(ethereumParams());

        assertThat(future).isCompletedExceptionally();
        assertThat(future)
                .failsWithin(Duration.ZERO)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(TesserError.SigningError.class);
    }

    @Test
    void aMalformedPrivateKeyIsRejectedAtConstructionInstead() {
        assertThatThrownBy(
                        () ->
                                new LocalSigner(
                                        new SigningConfig(
                                                TestKeys.PUBLIC_KEY, "not-hex-zzz", "org"),
                                        API_BASE_URL))
                .isInstanceOf(TesserError.ConfigError.class);
    }

    @Test
    void workRunsOnTheCallingThreadNotTheCommonPool() throws Exception {
        // No dispatch today: the future is already complete when it is returned,
        // and the work happened on this thread.
        LocalSigner signer = new LocalSigner(CFG, API_BASE_URL, StubHttpClient.unused());
        Thread callingThread = Thread.currentThread();

        CompletableFuture<SignedResult> future =
                signer.signCreateWallet(new CreateWalletParams("w", WalletType.STABLECOIN_SOLANA));

        assertThat(future).isDone();
        assertThat(future.get().signature()).isNotBlank();
        assertThat(Thread.currentThread()).isSameAs(callingThread);
    }

    @Test
    void signerIsReentrantAndSafeToShare() throws Exception {
        LocalSigner signer = signerReturningTheEthereumFixture();
        CompletableFuture<SignedResult> a = signer.signCreateWallet(ethereumParams());
        CompletableFuture<SignedResult> b =
                signer.signCreateWallet(new CreateWalletParams("b", WalletType.STABLECOIN_SOLANA));
        assertThat(a.get().signature()).isNotEqualTo(b.get().signature());
    }

    @Test
    void ethereumRequestFailureFailsTheFutureRatherThanThrowingSynchronously() {
        LocalSigner signer =
                new LocalSigner(
                        CFG,
                        API_BASE_URL,
                        StubHttpClient.failingWith(new IOException("connection reset")));

        CompletableFuture<SignedResult> future = signer.signCreateWallet(ethereumParams());

        assertThat(future)
                .failsWithin(Duration.ZERO)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(TesserError.ConnectionError.class);
    }

    @Test
    void failureAfterFallingBackToSandboxReachesWhenCompleteAsCompletionException()
            throws Exception {
        String signingKeyNotAccepted =
                "{\"errors\":[{\"error_code\":\"signing-4010\","
                        + "\"error_message\":\"Signing key not accepted\"}]}";
        LocalSigner signer =
                new LocalSigner(
                        CFG,
                        StubHttpClient.respondingWith(
                                new StubHttpResponse(401, signingKeyNotAccepted)));
        CompletableFuture<Throwable> observed = new CompletableFuture<>();

        signer.signCreateWallet(ethereumParams())
                .whenComplete((result, failure) -> observed.complete(failure));

        assertThat(observed.get())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(TesserError.APIError.class);
    }
}
