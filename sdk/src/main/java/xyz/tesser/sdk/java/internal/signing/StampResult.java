package xyz.tesser.sdk.java.internal.signing;

/**
 * Result of an API-key stamp operation.
 *
 * @param stampHeaderName the header name, typically {@code X-Stamp}
 * @param stampHeaderValue base64url-encoded JSON {@code {publicKey, signature, scheme}}
 */
public record StampResult(String stampHeaderName, String stampHeaderValue) {}
