plugins {
    application
    alias(libs.plugins.spotless)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

application {
    mainClass.set("xyz.tesser.sdk.java.examples.webhooks.Main")
}

dependencies {
    implementation(project(":sdk"))
    // The Kotlin original parses webhook payloads with kotlinx.serialization;
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
