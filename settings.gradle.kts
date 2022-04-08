rootProject.name = "object-store-gateway"

include("server")
include("proto")
include("example")
include("client")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        kotlin("jvm") version "1.6.10"
        kotlin("plugin.spring") version "1.6.10" apply false
        id("org.springframework.boot") version "2.6.4" apply false
        id("io.spring.dependency-management") version "1.0.11.RELEASE" apply false
        id("idea")
        id("com.google.protobuf") version "0.8.18" apply false
        id("maven-publish") apply false
        id("io.github.gradle-nexus.publish-plugin") version "1.1.0" apply false
        id("signing") apply false
    }
}

buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath("com.google.protobuf:protobuf-gradle-plugin:0.8.18")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
