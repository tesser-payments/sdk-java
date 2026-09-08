plugins {
    application
    alias(libs.plugins.spotless)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

application {
    mainClass.set("xyz.tesser.sdk.java.examples.polling.Main")
}

dependencies {
    implementation(project(":sdk"))
    // The Kotlin original parses rebalance payloads with kotlinx.serialization;
    // Jackson is the Java equivalent.
    implementation(libs.jackson.databind)
    runtimeOnly(libs.slf4j.simple) // so the SDK's debug logs go somewhere
}

spotless {
    java {
        googleJavaFormat().aosp()
        target("src/**/*.java")
    }
}
