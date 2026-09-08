plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "tesser-sdk-java"

include(":sdk")
include(":fixtures")
include(":examples:create-wallet")
include(":examples:sign-rebalance-step-webhooks")
include(":examples:sign-rebalance-step-polling")
