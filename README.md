# Tesser Java SDK

Java SDK for the [Tesser API](https://docs.tesser.xyz). Produces locally-signed
payloads (wallet creation, rebalance step signing) ready to submit to the
Tesser API. Targets Java 17+.

## Install

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("xyz.tesser:sdk-java:0.1.0")
}
```

**Maven:**

```xml
<dependency>
    <groupId>xyz.tesser</groupId>
    <artifactId>sdk-java</artifactId>
    <version>0.1.0</version>
</dependency>
```

Published on [Maven Central](https://central.sonatype.com/artifact/xyz.tesser/sdk-java).

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

For runnable end-to-end scripts, see [`examples/create-wallet`](./examples#create-wallet)
(wallet creation), [`examples/sign-rebalance-step-webhooks`](./examples/sign-rebalance-step-webhooks)
(rebalance step signing driven by Tesser webhooks), and
[`examples/sign-rebalance-step-polling`](./examples/sign-rebalance-step-polling)
(same flow over polling; use this while webhook delivery is unreliable).
Env setup, the runtime sequence, and troubleshooting are covered per example:
`create-wallet` in the [examples README](./examples/README.md), the two
rebalance flows in their own READMEs.

## What's included

- `new LocalSigner(signing)` works with production and sandbox signing keys.
  Without `apiBaseUrl`, the first Ethereum `signCreateWallet` tries production
  and, if production does not accept your signing key, sandbox, and remembers
  it for later calls. Set `apiBaseUrl` (`new LocalSigner(signing, apiBaseUrl)`)
  if your network allows only one Tesser host, or if you create a new signer
  per request (each new signer detects again). When set, only this URL is
  used: `https://api.tesser.xyz` in production,
  `https://api-sandbox.tesser.xyz` in sandbox. Send your own API requests to
  the environment of your signing key. An optional `java.net.http.HttpClient`
  can be passed to configure a proxy.
- `LocalSigner.signCreateWallet(...)` signs a wallet-creation request. For
  `STABLECOIN_ETHEREUM`, it first fetches the request to sign from the Tesser
  API and checks that it matches your parameters. The private key never leaves
  the JVM.
- `LocalSigner.signStep(...)` authorizes the transaction with your API key,
  without changing it. The transaction is the one the Tesser API delivers via
  a `step.signature_requested` webhook event. The resulting signature is the
  value submitted back to
  `POST /v1/treasury/rebalances/{transferId}/steps/{stepId}/sign`.
- Three wallet types: `STABLECOIN_ETHEREUM`, `STABLECOIN_SOLANA`, and
  `STABLECOIN_STELLAR`. Tesser currently supports managed wallets for
  `STABLECOIN_ETHEREUM` only.
- Sealed `TesserError` hierarchy. The signer reports `ConfigError` for bad
  input (a mismatched key pair, an invalid `apiBaseUrl`, if set, an unknown
  wallet type) and `SigningError` for any signing failure or a fetched request that
  does not match your parameters. Fetching the request can fail with
  `APIError`, `ConnectionError`, or `TimeoutError`. `ConnectionError`,
  `TimeoutError`, and an `APIError` with status 429 or 5xx are safe to retry;
  an `APIError` with any other 4xx status fails the same way on retry.
  Without `apiBaseUrl`, if neither environment accepts your signing key, the
  `APIError` from production is thrown.
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
key twice produces the same signature. This is invisible on the wire: a
verifier checks `(r, s)` against the public key and cannot tell how `k` was
derived. Deterministic nonces remove the failure mode where a weak or
misseeded `SecureRandom` would leak the private scalar.

## Future semantics

Every signing method returns a `CompletableFuture`. Three properties are worth
knowing, and all three are pinned by tests so they cannot regress:

- **Signing runs on the calling thread.** Nothing is dispatched to the common
  `ForkJoinPool`. `signStep` and `signCreateWallet` for Solana and Stellar
  return a future that is already complete. `signCreateWallet` for
  `STABLECOIN_ETHEREUM` first waits for a request to Tesser, so its future
  completes later, on a thread of the `HttpClient`.
- **Failures arrive as a failed future, not a synchronous throw.** This holds
  for *exceptions*. `Error` and its subclasses (a `NoClassDefFoundError` from a
  missing Bouncy Castle, an `OutOfMemoryError`) still propagate from the call
  site, because catching them to stuff into a future would do more harm than
  good. The one deliberate exception is `LocalSigner`'s constructor, which
  validates eagerly: `IllegalArgumentException` for a blank field,
  `TesserError.ConfigError` for a malformed or mismatched key pair or an
  invalid `apiBaseUrl`, if set. Construction
  is not a future-returning operation, and a configuration error is worth
  finding once at startup rather than on every call.
- **`LocalSigner` is thread-safe and reentrant.** The only state is the
  environment remembered after the first Ethereum wallet request; a single
  `LocalSigner` may be shared across concurrent calls.

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

## Tempo transfers

1. Pass the Tempo transaction Tesser provides (`0x76...`), your wallet
   address, and `TEMPO` or `TEMPO_MODERATO` in `StepForSigning` to
   `LocalSigner.signStep(...)`.
2. The SDK authorizes the transaction with your API key, without changing
   it. Submit the returned `signature` to the Tesser signing endpoint.
3. Tesser completes the transfer, broadcasts it, and pays the network fee.

Unknown networks fail with `TesserError.ConfigError` before anything is signed.

## Reporting a vulnerability

See [SECURITY.md](./SECURITY.md). Please do not open a public issue for a
security report.

## Contributing

Building, testing, lint, binary compatibility checks, and the release runbook,
including the GitHub Actions secrets required to publish, live in
[CONTRIBUTING.md](./CONTRIBUTING.md).

## License

[Apache 2.0](./LICENSE).
