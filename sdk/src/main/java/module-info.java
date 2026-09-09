/**
 * Tesser Java SDK.
 *
 * <p>Only the two packages below are exported. {@code xyz.tesser.sdk.java.internal.*} is {@code
 * public} in the Java-language sense purely because {@link xyz.tesser.sdk.java.LocalSigner} lives
 * in a different package and Java has no {@code internal} keyword — those types are not API, they
 * are excluded from the {@code apiCheck} lockfile, and this descriptor is what actually makes them
 * unreachable from a consumer's module rather than merely undocumented.
 *
 * <p>All three requirements are non-transitive: Jackson, Bouncy Castle and SLF4J are implementation
 * details that no exported signature mentions, so they must not leak onto a consumer's module
 * graph.
 */
module xyz.tesser.sdk.java {
    requires com.fasterxml.jackson.databind;
    requires org.bouncycastle.provider;
    requires org.slf4j;

    exports xyz.tesser.sdk.java;
    exports xyz.tesser.sdk.java.error;
}
