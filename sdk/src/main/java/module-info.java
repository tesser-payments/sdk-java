/**
 * Tesser Java SDK.
 *
 * <p>Only the two packages below are exported. {@code xyz.tesser.sdk.java.internal.*} is {@code
 * public} in the Java-language sense purely because {@link xyz.tesser.sdk.java.LocalSigner} lives
 * in a different package and Java has no {@code internal} keyword; those types are not API, they
 * are excluded from the {@code apiCheck} lockfile, and this descriptor is what actually makes them
 * unreachable from a consumer's module rather than merely undocumented.
 *
 * <p>Jackson, Bouncy Castle and SLF4J are non-transitive: they are implementation details that no
 * exported signature mentions, so they must not leak onto a consumer's module graph. {@code
 * java.net.http} is transitive because a {@link xyz.tesser.sdk.java.LocalSigner} constructor takes
 * an {@link java.net.http.HttpClient}.
 */
module xyz.tesser.sdk.java {
    requires com.fasterxml.jackson.databind;
    requires transitive java.net.http;
    requires org.bouncycastle.provider;
    requires org.slf4j;

    exports xyz.tesser.sdk.java;
    exports xyz.tesser.sdk.java.error;
}
