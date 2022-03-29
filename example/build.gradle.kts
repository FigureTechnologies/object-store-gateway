import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version Versions.Kotlin
}

java.sourceCompatibility = JavaVersion.VERSION_11

repositories {
    mavenLocal()
    mavenCentral()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":proto"))

    listOf(
        *Dependencies.Provenance.all(),
        *Dependencies.BouncyCastle.all(),
    ).forEach {
        it.implementation(this)
    }
    implementation("io.provenance.model:metadata-asset-model:0.1.2")
    implementation("io.grpc:grpc-netty:1.24.0")
}
