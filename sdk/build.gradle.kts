plugins {
    `java-library`
    alias(libs.plugins.spotless)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.bouncycastle)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testRuntimeOnly(libs.slf4j.simple)
}

spotless {
    java {
        googleJavaFormat().aosp() // 4-space indent, matching sdk-kotlin's style
        target("src/**/*.java")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "xyz.tesser.sdk.java")
    }
}
