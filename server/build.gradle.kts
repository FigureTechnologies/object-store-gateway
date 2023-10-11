import com.google.cloud.tools.jib.gradle.JibTask
import com.google.cloud.tools.jib.gradle.extension.layerfilter.Configuration
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    dependencies {
        classpath("com.google.cloud.tools:jib-layer-filter-extension-gradle:0.3.0")
    }
}

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("com.google.cloud.tools.jib")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

java.sourceCompatibility = JavaVersion.VERSION_17

configurations.all {
    // CVE-2021-44228 mitigation requires log4j to be greater than 2.17.0 (NOTE gradle 8 will remove VersionNumber but 7.3.x should have a builtin fix for this issue)
    exclude(group = "log4j") // ancient versions of log4j use this group
    val requiredVersion = net.swiftzer.semver.SemVer.parse("2.17.0")
    resolutionStrategy.eachDependency {
        if (requested.group == "org.apache.logging.log4j") {
            requested.version
                ?.let(net.swiftzer.semver.SemVer::parse)
                ?.takeIf { it < requiredVersion }
                ?.also {
                    useVersion("2.17.0")
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

val latestTag: String = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { grn -> grn.endsWith("main") }
    ?.let { "latest" }
    ?: ""

jib {
    // Allow for forcefully disabling CI build.  GITHUB_ACTIONS is automatically set when running GHA, so it cannot be trusted.
    // Targeted enabling of CI allows for proper container builds during integration tests
    val isCI = System.getenv("ENABLE_JIB_CI") == "true"
    val (jibUser, jibPass) = if (isCI) {
        println("Building CI for server docker publish")
        Pair(
            System.getenv("JIB_AUTH_USERNAME")?.takeIf { it.isNotBlank() } ?: "_json_key".also { println("WARNING: JIB USERNAME NOT SET") },
            System.getenv("JIB_AUTH_PASSWORD")?.takeIf { it.isNotBlank() } ?: "nopass".also { println("WARNING: JIB PASSWORD NOT SET") },
        )
    } else {
        println("Building local server docker container")
        "_json_key" to "nopass"
    }
    from {
        if (isCI) {
            auth {
                username = jibUser
                password = jibPass
            }
        }
        image = "us-east1-docker.pkg.dev/figure-shared-services/figure-shared-services-docker/java-jre:17-alpine"
    }
    to {
        if (isCI) {
            auth {
                username = jibUser
                password = jibPass
            }
        }
        image = System.getenv("OVERRIDE_SERVER_IMAGE_NAME")
            ?: System.getenv("DOCKER_IMAGE_NAME")
            ?: "figuretechnologies/object-store-gateway"
        tags = setOf(rootProject.version.toString())
        if (latestTag.isNotEmpty()) {
            tags = tags + latestTag
        }
    }
    pluginExtensions {
        pluginExtension {
            implementation = "com.google.cloud.tools.jib.gradle.extension.layerfilter.JibLayerFilterExtension"
            configuration(
                Action<Configuration> {
                    filters {
                        // Delete all properties files
                        filter {
                            glob = "/app/resources/application*properties"
                        }
                        // but retain the container properties file we'll need
                        filter {
                            glob = "/app/resources/application-container.properties"
                            toLayer = "app config"
                        }
                        // but retain the properties file we'll need
                        filter {
                            glob = "/app/resources/application.properties"
                            toLayer = "app config"
                        }
                    }
                },
            )
        }
    }
    extraDirectories {
        paths {
            path {
                setFrom(file("docker"))
                into = "/"
                includes.set(listOf("docker-entrypoint.sh"))
            }
            path {
                setFrom(file("build/libs"))
                into = "/"
                includes.set(listOf("server-${rootProject.version}.jar"))
            }
        }
        permissions.set(
            mapOf("/docker-entrypoint.sh" to "755"),
        )
    }
    container {
        ports = listOf("8080")
        entrypoint = listOf("/docker-entrypoint.sh", "/server-${rootProject.version}.jar")
        creationTime.set("USE_CURRENT_TIMESTAMP")
    }
}

tasks.withType<JibTask> {
    // Need to install the server distribution because it's packaged in the extra directories
    dependsOn("build")
}
