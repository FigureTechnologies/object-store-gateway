import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    Plugins.SpringBoot.addTo(this)
    Plugins.SpringDependencyManagement.addTo(this)
    kotlin("jvm") version Versions.Kotlin
    kotlin("plugin.spring") version Versions.Kotlin
}

java.sourceCompatibility = JavaVersion.VERSION_11

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

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(project(":proto"))

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    listOf(
        *Dependencies.BouncyCastle.all(),
        *Dependencies.Grpc.all(),
        *Dependencies.Jackson.all(),
        *Dependencies.Kotlin.all(),
        *Dependencies.Logging.all(),
        *Dependencies.OkHttp.all(),
        *Dependencies.Protobuf.all(),
        *Dependencies.Provenance.all(),
        *Dependencies.Scarlet.all(),
        *Dependencies.SpringBoot.all(),
    ).forEach { it.implementation(this) }

    listOf(
        *TestDependencies.Kotlin.all(),
        *TestDependencies.SpringBoot.all()
    ).forEach { it.testImplementation(this) }
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "11"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.bootRun {
    // Use the provided SPRING_PROFILES_ACTIVE env for the bootRun task, otherwise default to a development environment
    args("--spring.profiles.active=${System.getenv("SPRING_PROFILES_ACTIVE") ?: "development"}")
}
