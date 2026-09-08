package xyz.tesser.sdk.java;

/**
 * Options tuning a {@link LocalSigner#signStep} call.
 *
 * <p>Deliberately empty in this release. Reserved as a public type so future fields (cancellation,
 * alternate signature schemes, gas overrides) can be added without breaking callers using {@code
 * new SignStepOptions()}.
 */
public final class SignStepOptions {}
