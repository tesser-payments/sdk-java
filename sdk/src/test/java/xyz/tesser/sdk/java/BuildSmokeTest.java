package xyz.tesser.sdk.java;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildSmokeTest {
    @Test
    void toolchainIsJava17OrLater() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(17);
    }

    @Test
    void sealedClassesAreAvailable() {
        // Java 17 finalised sealed classes; this is the language level the SDK needs.
        assertThat(Object.class.getPermittedSubclasses()).isNull();
    }
}
