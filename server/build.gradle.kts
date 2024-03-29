import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

java.sourceCompatibility = JavaVersion.VERSION_17

configurations.all {
    // CVE-2021-44228 mitigation requires log4j to be greater than 2.15.0 (NOTE gradle 8 will remove VersionNumber but 7.3.x should have a builtin fix for this issue)
    exclude(group = "log4j") // ancient versions of log4j use this group
    val requiredVersion = net.swiftzer.semver.SemVer.parse("2.15.0")
    resolutionStrategy.eachDependency {
        if (requested.group == "org.apache.logging.log4j") {
            requested.version
                ?.let(net.swiftzer.semver.SemVer::parse)
                ?.takeIf { it < requiredVersion }
                ?.also {
                    useVersion("2.15.0")
                    because("CVE-2021-44228")
                }
        }
    }
}

dependencies {
    implementation(projects.proto)
    implementation(projects.shared)

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    listOf(
        libs.bouncycastle,
        libs.java.jwt,
        libs.okhttp3,
        libs.provenance.blockapi.client,
        libs.grpc.springboot.starter,
        libs.bundles.database,
        libs.bundles.eventstream,
        libs.bundles.grpc,
        libs.bundles.jackson,
        libs.bundles.kotlin,
        libs.bundles.logging,
        libs.bundles.protobuf,
        libs.bundles.provenance,
        libs.bundles.scarlet,
        libs.bundles.springboot,
    ).forEach(::implementation)

    listOf(
        libs.coroutines.test,
        libs.springboot.starter.test,
        libs.bundles.test.kotlin,
        libs.bundles.testcontainers,
    ).forEach(::testImplementation)
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "17"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Force tests to re-run on every test invocation
    outputs.upToDateWhen { false }
}

tasks.bootRun {
    // Use the provided SPRING_PROFILES_ACTIVE env for the bootRun task, otherwise default to a development environment
    args("--spring.profiles.active=${System.getenv("SPRING_PROFILES_ACTIVE") ?: "development"}")
}
