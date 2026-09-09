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
  input (blank keys, a mismatched key pair, an unknown wallet type) and
  `SigningError` for any cryptographic failure.
- A JPMS module descriptor. The published jar is the named module
  `xyz.tesser.sdk.java` and exports only `xyz.tesser.sdk.java` and
  `xyz.tesser.sdk.java.error`; `xyz.tesser.sdk.java.internal.*` is `public` only
  because Java has no `internal` keyword, and the descriptor makes it genuinely
  unreachable from a modular consumer.

## Key handling

Two things are worth knowing before you construct a `SigningConfig`:

- **The key pair is checked at construction.** `LocalSigner` derives the public
  point from `privateKey` and compares it to `publicKey`, failing with
  `ConfigError` if they are not a pair. Without that check a mismatch produces a
  perfectly well-formed stamp that authenticates nowhere, and you find out from
  an opaque rejection several hops away. Points are compared, not hex, so an
  uppercase or uncompressed encoding of the correct key is fine.
- **The private key stays in the heap.** `SigningConfig.privateKey` is a
  `String`: immutable, possibly interned, and visible in a heap dump for as long
  as the config is reachable. `toString()` masks it, so an incidental log line
  or properties dump will not leak it, but the SDK cannot erase the value
  itself. Treat any component holding a `SigningConfig` as holding key material.

ECDSA nonces are RFC 6979 deterministic, so signing the same body with the same
key twice produces the same signature. This is invisible on the wire — a
verifier checks `(r, s)` against the public key and cannot tell how `k` was
derived — and it removes the failure mode where a weak or misseeded
`SecureRandom` would leak the private scalar.

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
  validates eagerly — `IllegalArgumentException` for a blank field,
  `TesserError.ConfigError` for a malformed or mismatched key pair. Construction
  is not a future-returning operation, and a configuration error is worth
  finding once at startup rather than on every call.
- **`LocalSigner` is thread-safe and reentrant.** It holds no mutable state, so
  a single instance can be shared freely.

### Unwrapping errors

`CompletableFuture` wraps every failure, so you cannot catch a `TesserError`
subtype directly at the call site.

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

## Reporting a vulnerability

See [SECURITY.md](./SECURITY.md). Please do not open a public issue for a
security report.

## Contributing

Building, testing, lint, binary compatibility checks, and the release runbook
live in [CONTRIBUTING.md](./CONTRIBUTING.md).

## License

[Apache 2.0](./LICENSE).
