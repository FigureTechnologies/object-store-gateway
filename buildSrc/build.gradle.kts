buildscript {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

plugins {
    `kotlin-dsl`
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // Add semantic versioning to allow for pruning log4j versions to ensure CVE-2021-44228 does not impact any
    // code within this project
//    implementation("net.swiftzer.semver:semver:1.1.2")
}
