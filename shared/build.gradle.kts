plugins {
    kotlin("jvm")
}

dependencies {
    listOf(
        libs.scopeSdk,
        libs.bcProv,
        libs.bundles.jwt,
        libs.bundles.grpc,
    ).forEach(::implementation)

    listOf(
        libs.bundles.testKotlin,
    ).forEach(::testImplementation)
}
