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
