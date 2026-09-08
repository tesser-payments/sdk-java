package xyz.tesser.sdk.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import xyz.tesser.sdk.java.error.TesserError;

/**
 * Pins the two rules that make a CompletableFuture-returning API safe, so they
 * cannot regress when real I/O is added to the stamper.
 */
class FutureSemanticsTest {

    private static final SigningConfig CFG =
            new SigningConfig("02".repeat(33), "01".repeat(32), "org_futures");

    @Test
    void unsupportedNetworkFailsTheFutureRatherThanThrowingSynchronously() {
        LocalSigner signer = new LocalSigner(CFG);
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
    void malformedPrivateKeyFailsTheFutureRatherThanThrowingSynchronously() {
        LocalSigner signer =
                new LocalSigner(new SigningConfig("02".repeat(33), "not-hex-zzz", "org"));

        CompletableFuture<SignedResult> future =
                signer.signCreateWallet(new CreateWalletParams("w", WalletType.STABLECOIN_ETHEREUM));

        assertThat(future).isCompletedExceptionally();
        assertThat(future)
                .failsWithin(Duration.ZERO)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(TesserError.SigningError.class);
    }

    @Test
    void workRunsOnTheCallingThreadNotTheCommonPool() throws Exception {
        // No dispatch today: the future is already complete when it is returned,
        // and the work happened on this thread.
        LocalSigner signer = new LocalSigner(CFG);
        Thread callingThread = Thread.currentThread();

        CompletableFuture<SignedResult> future =
                signer.signCreateWallet(new CreateWalletParams("w", WalletType.STABLECOIN_ETHEREUM));

        assertThat(future).isDone();
        assertThat(future.get().signature()).isNotBlank();
        assertThat(Thread.currentThread()).isSameAs(callingThread);
    }

    @Test
    void signerIsReentrantAndSafeToShare() throws Exception {
        LocalSigner signer = new LocalSigner(CFG);
        CompletableFuture<SignedResult> a =
                signer.signCreateWallet(new CreateWalletParams("a", WalletType.STABLECOIN_ETHEREUM));
        CompletableFuture<SignedResult> b =
                signer.signCreateWallet(new CreateWalletParams("b", WalletType.STABLECOIN_ETHEREUM));
        assertThat(a.get().signature()).isNotEqualTo(b.get().signature());
    }
}
