plugins {
    kotlin("jvm")
}

dependencies {
    listOf(
        libs.bundles.grpc,
    ).forEach(::implementation)
}
