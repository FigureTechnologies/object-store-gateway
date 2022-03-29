import com.google.protobuf.gradle.generateProtoTasks
import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.ofSourceSet
import com.google.protobuf.gradle.protobuf
import com.google.protobuf.gradle.protoc
import com.google.protobuf.gradle.plugins
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath("com.google.protobuf:protobuf-gradle-plugin:0.8.18")
    }
}

plugins {
    kotlin("jvm") version "1.6.10"
    id("com.google.protobuf") version "0.8.18"
}

repositories {
    mavenCentral()
    google()
}

sourceSets {
    main {
        java.srcDir("build/generated/source/proto/main/java")
        java.srcDir("build/generated/source/proto/main/grpc")
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-java:3.6.1")
    implementation("io.grpc:grpc-stub:1.15.1")
    implementation("io.grpc:grpc-protobuf:1.15.1")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    Dependencies.Provenance.ScopeSdkProto.protobuf(this)
}

protobuf {
    // Configure the protoc executable
    protoc {
        artifact = "com.google.protobuf:protoc:3.5.0"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.0.0-pre2"
        }
//		id("grpckt") {
//			artifact = "io.grpc:protoc-gen-grpc-kotlin:1.2.1:jdk7@jar"
//		}
    }
    generateProtoTasks {
        ofSourceSet("main").forEach {
            it.plugins {
                // Apply the "grpc" plugin whose spec is defined above, without
                // options.  Note the braces cannot be omitted, otherwise the
                // plugin will not be added. This is because of the implicit way
                // NamedDomainObjectContainer binds the methods.
                id("grpc")
//				id("grpckt")
            }
//			it.builtins {
//				id("kotlin")
//			}
        }
    }
}



//import com.google.protobuf.gradle.generateProtoTasks
//        import com.google.protobuf.gradle.id
//        import com.google.protobuf.gradle.ofSourceSet
//        import com.google.protobuf.gradle.protobuf
//        import com.google.protobuf.gradle.protoc
//        import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
//        import com.google.protobuf.gradle.plugins
//
//        buildscript {
//            repositories {
//                mavenCentral()
//                google()
//            }
//            dependencies {
//                classpath("com.google.protobuf:protobuf-gradle-plugin:0.8.18")
//            }
//        }
//
//plugins {
//    id("org.springframework.boot") version "2.6.4"
//    id("io.spring.dependency-management") version "1.0.11.RELEASE"
//    kotlin("jvm") version "1.6.10"
//    kotlin("plugin.spring") version "1.6.10"
//    id("com.google.protobuf") version "0.8.18"
//}
//
//group = "io.provenance"
//version = "0.0.1-SNAPSHOT"
//java.sourceCompatibility = JavaVersion.VERSION_11
//
//repositories {
//    mavenCentral()
//    google()
//}
//
//sourceSets {
//    main {
//        java.srcDir("build/generated/source/proto/main/java")
//        java.srcDir("build/generated/source/proto/main/grpc")
//    }
//}
//// todo: fancier versioning
//dependencies {
//    implementation("org.springframework.boot:spring-boot-starter")
//    implementation("org.jetbrains.kotlin:kotlin-reflect")
//    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
//    testImplementation("org.springframework.boot:spring-boot-starter-test")
//
//    // provenance
//    implementation("io.provenance.eventstream:es-api:0.4.0")
//    implementation("io.provenance.eventstream:es-api-model:0.4.0")
//    implementation("io.provenance.eventstream:es-cli:0.4.0")
//    implementation("io.provenance.eventstream:es-core:0.4.0")
//    implementation("io.provenance.client:pb-grpc-client-kotlin:1.0.5")
//    implementation("io.provenance:proto-kotlin:1.8.0")
//    implementation("io.provenance.scope:sdk:0.4.9")
//
//    // proto
//    implementation("com.google.protobuf:protobuf-java:3.6.1")
//    implementation("io.grpc:grpc-stub:1.15.1")
//    implementation("io.grpc:grpc-protobuf:1.15.1")
//    implementation("io.github.lognet:grpc-spring-boot-starter:3.4.3")
////	implementation("com.google.protobuf:protobuf-java-util:3.19.4")
////	implementation("com.google.protobuf:protobuf-kotlin:3.19.4")
////	implementation("io.grpc:grpc-kotlin-stub:1.2.1")
//
//    // Logging
//    implementation("io.github.microutils:kotlin-logging-jvm:2.1.21")
//
//    // Kotlin
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.5.2")
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.5.2")
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.5.2")
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.5.2")
//    implementation("org.jetbrains.kotlin:kotlin-reflect:1.6.10")
//    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.6.10")
//
//    // EventStream
//    implementation("com.tinder.scarlet:message-adapter-moshi:0.1.12")
//    implementation("com.tinder.scarlet:scarlet:0.1.12")
//    implementation("com.tinder.scarlet:stream-adapter-coroutines:0.1.12")
//    // Exclude Okhttp from this scarlet lib.  It doesn't match what the event-stream library needs
//    implementation("com.tinder.scarlet:websocket-okhttp:0.1.12") {
//        exclude("com.squareup.okhttp3:okhttp")
//    }
//
//    // OkHttp
//    implementation("com.squareup.okhttp3:okhttp:4.9.1")
//}
//
//tasks.withType<KotlinCompile> {
//    kotlinOptions {
//        freeCompilerArgs = listOf("-Xjsr305=strict")
//        jvmTarget = "11"
//    }
//}
//
//tasks.withType<Test> {
//    useJUnitPlatform()
//}
//
//protobuf {
//    // Configure the protoc executable
//    protoc {
//        artifact = "com.google.protobuf:protoc:3.0.0"
//    }
//    plugins {
//        id("grpc") {
//            artifact = "io.grpc:protoc-gen-grpc-java:1.0.0-pre2"
//        }
////		id("grpckt") {
////			artifact = "io.grpc:protoc-gen-grpc-kotlin:1.2.1:jdk7@jar"
////		}
//    }
//    generateProtoTasks {
//        ofSourceSet("main").forEach {
//            it.plugins {
//                // Apply the "grpc" plugin whose spec is defined above, without
//                // options.  Note the braces cannot be omitted, otherwise the
//                // plugin will not be added. This is because of the implicit way
//                // NamedDomainObjectContainer binds the methods.
//                id("grpc")
////				id("grpckt")
//            }
////			it.builtins {
////				id("kotlin")
////			}
//        }
//    }
//}
