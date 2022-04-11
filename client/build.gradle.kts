import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

buildscript {
    repositories {
        mavenCentral()
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(projects.proto)
    listOf(
        libs.bundles.grpc,
        libs.bundles.protobuf,
        libs.bundles.bouncyCastle,
        libs.bundles.provenance,
    ).forEach(::implementation)
}
