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
    listOf(
        projects.proto,
        projects.shared,
        libs.bouncycastle,
        libs.java.jwt,
        libs.coroutines.core.jvm,
        libs.coroutines.jdk8,
        libs.bundles.grpc,
        libs.bundles.protobuf,
        libs.bundles.provenance,
    ).forEach(::api)
}
