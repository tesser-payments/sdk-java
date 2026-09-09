# Changelog

## Unreleased

### Added

- Initial release. `LocalSigner.signCreateWallet` and `LocalSigner.signStep`,
  at parity with the Kotlin SDK's 0.0.4 wire output — verified byte-for-byte by
  `GoldenBodyParityTest` against fixtures generated from the Kotlin SDK.
- Sealed `TesserError` hierarchy with `ConfigError`, `APIError`,
  `ConnectionError`, `TimeoutError` and `SigningError`.
- Three wallet types: `STABLECOIN_ETHEREUM`, `STABLECOIN_SOLANA`,
  `STABLECOIN_STELLAR`. Ethereum is exercised end-to-end against Tesser staging;
  Solana and Stellar are not yet verified against the live API.
- `module-info.java` exporting only `xyz.tesser.sdk.java` and
  `xyz.tesser.sdk.java.error`. `xyz.tesser.sdk.java.internal.*` is now
  unreachable from a modular consumer, not merely undocumented. Replaces the
  `Automatic-Module-Name` manifest attribute, which the JVM ignores once a real
  descriptor is present. The `apiCheck` lockfile now covers the descriptor, so a
  change to `exports` or `requires` shows up as an API diff.
- `SECURITY.md` with a private vulnerability-reporting channel, and a Dependabot
  configuration for Gradle and GitHub Actions.

### Changed

These diverge from the Kotlin SDK deliberately. None of them change the signed
body, so `GoldenBodyParityTest` is unaffected.

- **ECDSA nonces are now RFC 6979 deterministic** rather than random. A verifier
  cannot tell how `k` was derived, so this is invisible on the wire, but it
  removes the failure mode where a weak or misseeded `SecureRandom` leaks the
  private scalar. Stamping the same body with the same key now reproduces the
  same signature.
- **`LocalSigner`'s constructor verifies the key pair.** It derives the public
  point from `privateKey` and compares it to `publicKey`, throwing
  `ConfigError` on a mismatch instead of producing a well-formed stamp that
  authenticates nowhere. Points are compared rather than hex, so uppercase and
  uncompressed encodings of the correct key still pass. A malformed private key
  is now also rejected at construction rather than on the first `sign*` call;
  the "signing methods never throw synchronously" contract is unchanged.
- **`SigningConfig.toString()` masks `privateKey`.** The record-generated
  version printed the raw private scalar, so any incidental stringification — a
  framework properties dump, an exception carrying the config — leaked the key.
  `equals`/`hashCode` are unchanged.
- `signStep(step, null)` now fails the returned future instead of being silently
  accepted.

### Removed

- `internal.util.Redact`. It had no callers, its Javadoc claimed a use that did
  not exist, and its pattern did not match `privateKey` — the one key it would
  most plausibly have been pointed at.

### Fixed

- **`sign-rebalance-step-webhooks` example no longer signs webhook payloads.**
  It required the `step.signature_requested` event to carry this run's
  `rebalance_id`, and it now re-reads the step over an authenticated `GET`
  before signing, so the transaction bytes never originate from an
  unauthenticated POST. Request bodies are capped at 64 KiB and the event queue
  is bounded.
- `sign-rebalance-step-polling` example no longer treats an empty `steps` array
  as fatal; a freshly-created rebalance can briefly return one, which is exactly
  what the polling loop exists to absorb.
- All three examples parse the OAuth `access_token` with Jackson rather than a
  regex, and `create-wallet` builds its request body with `ObjectMapper` rather
  than string concatenation.

### Security

- The Gradle distribution is pinned by SHA-256 in `gradle-wrapper.properties`.
- `release.yml` passes `workflow_dispatch` inputs to the shell through `env:`
  rather than `${{ }}` interpolation.
