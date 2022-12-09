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
    implementation(projects.shared)
    listOf(
        libs.bouncycastle,
        libs.java.jwt,
        libs.bundles.grpc,
        libs.bundles.protobuf,
        libs.bundles.provenance,
    ).forEach(::implementation)
}
