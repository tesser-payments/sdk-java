plugins {
    application
    alias(libs.plugins.spotless)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

application {
    mainClass.set("xyz.tesser.sdk.java.examples.createwallet.Main")
}

dependencies {
    implementation(project(":sdk"))
    // For the OAuth response and the wallet request body. The SDK keeps Jackson
    // as an `implementation` dependency, so it is not on this project's compile
    // classpath transitively.
    implementation(libs.jackson.databind)
    runtimeOnly(libs.slf4j.simple) // so the SDK's debug logs go somewhere
}

// The examples are part of the deliverable and CI only compiles them; without
// this they would be the one unformatted corner of the repo. Applying the plugin
// per-example means a root `./gradlew spotlessCheck` covers all three.
// :fixtures is deliberately excluded -- it is throwaway Kotlin test support.
spotless {
    java {
        googleJavaFormat().aosp()
        target("src/**/*.java")
    }
}
