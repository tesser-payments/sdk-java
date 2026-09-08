# Tesser Java SDK

Java SDK for the [Tesser API](https://docs.tesser.xyz). Produces locally-signed
payloads (wallet creation, rebalance step signing) ready to submit to the
Tesser API. Targets Java 17+.

## Install

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("xyz.tesser:sdk-java:0.0.1")
}
```

**Maven:**

```xml
<dependency>
    <groupId>xyz.tesser</groupId>
    <artifactId>sdk-java</artifactId>
    <version>0.0.1</version>
</dependency>
```

Published on Maven Central once the first release is cut.

## Quick start

```java
import java.util.concurrent.CompletableFuture;
import xyz.tesser.sdk.java.*;

public class Example {
    public static void main(String[] args) {
        LocalSigner signer = new LocalSigner(new SigningConfig(
            System.getenv("SIGNING_PUBLIC_KEY"),
            System.getenv("SIGNING_PRIVATE_KEY"),
            System.getenv("SIGNING_ENCLAVE_ID")));

        CompletableFuture<SignedResult> future = signer.signCreateWallet(
            new CreateWalletParams("My wallet", WalletType.STABLECOIN_ETHEREUM));

        // Pass signature straight into POST /v1/accounts/wallets:
        //   { "signature": ..., "name": "My wallet",
        //     "type": "stablecoin_ethereum", "is_managed": true }
        System.out.println(future.join().signature());
    }
}
```

For runnable end-to-end scripts, see [`examples/create-wallet`](./examples/create-wallet)
(wallet creation), [`examples/sign-rebalance-step-webhooks`](./examples/sign-rebalance-step-webhooks)
(rebalance step signing driven by Tesser webhooks), and
[`examples/sign-rebalance-step-polling`](./examples/sign-rebalance-step-polling)
(same flow, polling-based — use this while webhook delivery is unreliable).
Each example's README walks through env setup, the runtime sequence, and
troubleshooting.

## What's included

- `LocalSigner.signCreateWallet(...)` builds and signs an
  `ACTIVITY_TYPE_CREATE_WALLET` payload locally. No network calls; the private
  key never leaves the JVM.
- `LocalSigner.signStep(...)` signs the unsigned transaction bytes that the
  Tesser API delivers via a `step.signature_requested` webhook event. The
  resulting signature is the value submitted back to
  `POST /v1/treasury/rebalances/{transferId}/steps/{stepId}/sign`.
- Three wallet types: `STABLECOIN_ETHEREUM`, `STABLECOIN_SOLANA`, and
  `STABLECOIN_STELLAR`. Ethereum is exercised end-to-end against Tesser
  staging. Solana and Stellar are not yet verified against the live API.
- Sealed `TesserError` hierarchy. The signer reports `ConfigError` for bad
  input (blank keys, unknown wallet type) and `SigningError` for any
  cryptographic failure.

## Future semantics

Every signing method returns a `CompletableFuture`. Three properties are worth
knowing, and all three are pinned by tests so they cannot regress:

- **Work runs on the calling thread.** Nothing is dispatched to the common
  `ForkJoinPool`; the returned future is already complete. The future type
  exists so that adding external I/O later (an MPC co-signer call inside the
  stamper) is not a breaking change. If that happens, an `Executor` overload
  will appear alongside the current methods.
- **Failures arrive as a failed future, not a synchronous throw.** This holds
  for *exceptions*. `Error` and its subclasses — a `NoClassDefFoundError` from a
  missing Bouncy Castle, an `OutOfMemoryError` — still propagate from the call
  site, because catching them to stuff into a future would do more harm than
  good. The one deliberate exception is `LocalSigner`'s constructor, which
  throws `IllegalArgumentException` eagerly for a blank key: construction is not
  a future-returning operation.
- **`LocalSigner` is thread-safe and reentrant.** It holds no mutable state, so
  a single instance can be shared freely.

### Unwrapping errors

This is the one place the API is less pleasant than the Kotlin SDK, where
callers write `catch (e: TesserError.ConfigError)` directly.

> On failure, `get()` throws `ExecutionException` and `join()` throws
> `CompletionException`; in both cases the `TesserError` is `getCause()`.
> Completion callbacks (`whenComplete`, `exceptionally`) also receive the
> `CompletionException` wrapper, not the `TesserError` itself.

```java
try {
    signer.signStep(step).join();
} catch (CompletionException e) {
    if (e.getCause() instanceof TesserError.ConfigError ce) { /* handle */ }
}
```

## Relationship to the Kotlin SDK

This SDK produces the **same wire output** as
[`xyz.tesser:sdk`](https://github.com/tesser-payments/sdk-kotlin), the Kotlin
SDK. That is not an aspiration: `GoldenBodyParityTest` asserts the assembled
activity body is byte-identical to what the Kotlin SDK emits, across seven
wallet cases and all seven supported networks, against fixtures generated from
the real Kotlin SDK. `JsonEscapingParityTest` does the same for the JSON writer
across a corpus of hostile strings. Byte-identity is the whole claim, because
the signature is base64 of that exact text.

The package root is `xyz.tesser.sdk.java`, not `xyz.tesser.sdk`, so both SDKs
can sit on one classpath without collision.

## Contributing

Building, testing, lint, binary compatibility checks, and the release runbook
live in [CONTRIBUTING.md](./CONTRIBUTING.md).

## License

[Apache 2.0](./LICENSE).
