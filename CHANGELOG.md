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
