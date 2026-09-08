package xyz.tesser.sdk.java.internal.signing;

import java.util.concurrent.CompletableFuture;
import xyz.tesser.sdk.java.SigningConfig;

/**
 * SDK-internal abstraction over the API-key stamper. The single implementation is {@link
 * ApiKeyStamp}; the interface exists to keep the signing pipeline testable.
 *
 * <p>Returns a future rather than a value because this is the seam where an external MPC co-signer
 * call would land. Implementations must complete the future exceptionally rather than throwing.
 */
public interface Stamp {

    CompletableFuture<StampResult> stamp(SigningConfig keys, String body);

    /** Returns the concrete implementation. */
    static Stamp create() {
        return new ApiKeyStamp();
    }
}
