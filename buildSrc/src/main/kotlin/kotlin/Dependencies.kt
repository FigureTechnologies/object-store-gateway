object Versions {
    const val BouncyCastle = "1.70"
    const val Grpc = "1.39.0"
    const val GrpcSpringboot = "3.4.3"
    const val GrpcNetty = "1.24.0"
    const val Jackson = "2.12.5"
    const val JacksonProtobuf = "0.9.12"
    const val Kotlin = "1.6.10"
    const val KotlinCoroutines = "1.5.2"
    const val KotlinLogging = "2.1.21"
    const val OkHttp = "4.9.1"
    const val Protobuf = "3.19.1"
    const val ProvenanceClient = "1.0.5"
    const val ProvenanceEventStream = "0.4.0"
    const val ProvenanceHdWallet = "0.1.15"
    const val ProvenanceProto = "1.8.0-rc10"
    const val ProvenanceScope = "0.4.9"
    const val Scarlet = "0.1.12"
    const val SpringBoot = "2.6.4"
    const val SpringBootDependencyManagement = "1.0.11.RELEASE"
    const val Exposed = "0.37.3"
    const val Postgres = "42.3.3"
    const val SqLite = "3.36.0.3"
    const val HikariCP = "5.0.1"
}

object Plugins {
    val Idea = PluginSpec("idea")
    val SpringBoot = PluginSpec("org.springframework.boot", Versions.SpringBoot)
    val SpringDependencyManagement = PluginSpec("io.spring.dependency-management", Versions.SpringBootDependencyManagement)
}

object Dependencies {
    object BouncyCastle : DependencyCollector() {
        val BcProv = DependencySpec("org.bouncycastle:bcprov-jdk15on", Versions.BouncyCastle).include()
    }

    // GRPC (for Provenance - required dependencies because Prov stuff only brings in GRPC for runtime)
    object Grpc : DependencyCollector() {
        val GrpcProtobuf = DependencySpec("io.grpc:grpc-protobuf", Versions.Grpc, exclude = listOf("com.google.protobuf:protobuf-java", "com.google.protobuf:protobuf-java-util")).include()
        val GrpcStub = DependencySpec("io.grpc:grpc-stub", Versions.Grpc).include()
        val GrpcSpringbootStarter = DependencySpec("io.github.lognet:grpc-spring-boot-starter", Versions.GrpcSpringboot).include()
    }

    object Netty : DependencyCollector() {
        val GrpcNetty = DependencySpec("io.grpc:grpc-netty", Versions.GrpcNetty).include()
    }

    object Jackson : DependencyCollector() {
        val KotlinModule = DependencySpec("com.fasterxml.jackson.module:jackson-module-kotlin", Versions.Jackson).include()
        val ProtobufModule = DependencySpec("com.hubspot.jackson:jackson-datatype-protobuf", Versions.JacksonProtobuf).include()
    }

    object Kotlin : DependencyCollector() {
        val AllOpen = DependencySpec("org.jetbrains.kotlin:kotlin-allopen", Versions.Kotlin).include()
        val CoroutinesCoreJvm = DependencySpec("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm", Versions.KotlinCoroutines).include()
        val CoroutinesReactor = DependencySpec("org.jetbrains.kotlinx:kotlinx-coroutines-reactor", Versions.KotlinCoroutines).include()
        val CoroutinesJdk8 = DependencySpec("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8", Versions.KotlinCoroutines).include()
        val CoroutinesSLF4J = DependencySpec("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j", Versions.KotlinCoroutines).include()
        val Reflect = DependencySpec("org.jetbrains.kotlin:kotlin-reflect", Versions.Kotlin).include()
        val StdLibJdk8 = DependencySpec("org.jetbrains.kotlin:kotlin-stdlib-jdk8", Versions.Kotlin).include()
    }

    object Logging : DependencyCollector() {
        val KotlinLogging = DependencySpec("io.github.microutils:kotlin-logging-jvm", Versions.KotlinLogging).include()
    }

    object OkHttp : DependencyCollector() {
        // Manually bring OkHttp3 into the project at the correct version for compatibility with event stream
        val OkHttp3 = DependencySpec("com.squareup.okhttp3:okhttp", Versions.OkHttp).include()
    }

    object Protobuf : DependencyCollector() {
        val Java = DependencySpec("com.google.protobuf:protobuf-java", Versions.Protobuf).include()
        val JavaUtil = DependencySpec("com.google.protobuf:protobuf-java-util", Versions.Protobuf).include()
    }

    object Provenance : DependencyCollector() {
        val ProvenanceEventStreamApi = DependencySpec("io.provenance.eventstream:es-api", Versions.ProvenanceEventStream).include()
        val ProvenanceEventStreamApiModel = DependencySpec("io.provenance.eventstream:es-api-model", Versions.ProvenanceEventStream).include()
        val ProvenanceEventStreamCli = DependencySpec("io.provenance.eventstream:es-cli", Versions.ProvenanceEventStream).include()
        val ProvenanceEventStreamCore = DependencySpec("io.provenance.eventstream:es-core", Versions.ProvenanceEventStream).include()
        val ProvenanceGrpcClient = DependencySpec("io.provenance.client:pb-grpc-client-kotlin", Versions.ProvenanceClient).include()
        val ProvenanceHdWallet = DependencySpec("io.provenance.hdwallet:hdwallet", Versions.ProvenanceHdWallet).include()
        val ProvenanceProto = DependencySpec("io.provenance:proto-kotlin", Versions.ProvenanceProto).include()
        val ScopeSdk = DependencySpec("io.provenance.scope:sdk", Versions.ProvenanceScope).include()
        val ScopeSdkProto = DependencySpec("io.provenance.scope:proto", Versions.ProvenanceScope) // don't include by default
        val ScopeUtil = DependencySpec("io.provenance.scope:util", Versions.ProvenanceScope).include()
    }

    object Scarlet : DependencyCollector() {
        val MessageAdapterMoshi = DependencySpec("com.tinder.scarlet:message-adapter-moshi", Versions.Scarlet).include()
        val Scarlet = DependencySpec("com.tinder.scarlet:scarlet", Versions.Scarlet).include()
        val StreamAdapterCoroutines = DependencySpec("com.tinder.scarlet:stream-adapter-coroutines", Versions.Scarlet).include()
        // Exclude Okhttp from this scarlet lib.  It doesn't match what the event-stream library needs
        val WebSocketOkHttp = DependencySpec("com.tinder.scarlet:websocket-okhttp", Versions.Scarlet, exclude = listOf("com.squareup.okhttp3:okhttp")).include()
    }

    // A demonstration of the included {} syntax versus the declare + include() function invocation
    object SpringBoot : DependencyCollector() {
        val AutoConfigure = included { DependencySpec("org.springframework.boot:spring-boot-autoconfigure") }
        val Starter = included { DependencySpec("org.springframework.boot:spring-boot-starter") }
        val StarterActuator = included { DependencySpec("org.springframework.boot:spring-boot-starter-actuator") }
        val StarterAOP = included { DependencySpec("org.springframework.boot:spring-boot-starter-aop") }
        val StarterJetty = included { DependencySpec("org.springframework.boot:spring-boot-starter-jetty") }
        val StarterWeb = included { DependencySpec("org.springframework.boot:spring-boot-starter-web") }
    }

    object Exposed : DependencyCollector() {
        val ExposedCore =DependencySpec("org.jetbrains.exposed:exposed-core", Versions.Exposed).include()
        val ExposedDao = DependencySpec("org.jetbrains.exposed:exposed-dao", Versions.Exposed).include()
        val ExposedJdbc = DependencySpec("org.jetbrains.exposed:exposed-jdbc", Versions.Exposed).include()
        val Postgres = DependencySpec("org.postgresql:postgresql", Versions.Postgres).include()
        val HikariCP = DependencySpec("com.zaxxer:HikariCP", Versions.HikariCP).include()
        val SqLite = DependencySpec("org.xerial:sqlite-jdbc", Versions.SqLite).include()
    }
}

object TestDependencies {
    // Kotlin
    object Kotlin : DependencyCollector() {
        val KotlinTest = included { DependencySpec("org.jetbrains.kotlin:kotlin-test", Versions.Kotlin) }
    }

    // Spring Boot
    object SpringBoot : DependencyCollector() {
        val StarterTest = included { DependencySpec("org.springframework.boot:spring-boot-starter-test") }
    }
}
