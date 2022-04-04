import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        mavenCentral()
    }
}

plugins {
    kotlin("jvm") version "1.6.10"
}

repositories {
    mavenCentral()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(project(":proto"))
    listOf(
        *Dependencies.Grpc.all(),
        *Dependencies.Protobuf.all(),
        *Dependencies.BouncyCastle.all(),
        *Dependencies.Provenance.all(),
    ).forEach { it.implementation(this) }
}
