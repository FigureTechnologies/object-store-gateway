import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

java.sourceCompatibility = JavaVersion.VERSION_17

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "17"
    }
}

dependencies {
    implementation(projects.client)

    listOf(
        libs.asset.model,
        libs.bouncycastle,
        libs.bundles.provenance,
    ).forEach(::implementation)
}
