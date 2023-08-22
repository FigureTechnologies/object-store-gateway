plugins {
    kotlin("jvm")
}

dependencies {
    listOf(
        libs.bouncycastle,
        libs.java.jwt,
        libs.provenance.scope.sdk,
        libs.bundles.grpc,
    ).forEach(::implementation)

    listOf(
        libs.bundles.test.kotlin,
        libs.kotlin.logging,
    ).forEach(::testImplementation)
}
