import org.jetbrains.kotlin.gradle.plugin.statistics.ReportStatisticsToElasticSearch.url
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        mavenCentral()
    }
}

plugins {
    kotlin("jvm") version "1.6.10"
    Plugins.MavenPublish.addTo(this)
    Plugins.Signing.addTo(this)
}

repositories {
    mavenCentral()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11"
    }
}

java {
    withJavadocJar()
    withSourcesJar()
}

dependencies {
    api(project(":proto"))
    listOf(
        *Dependencies.Grpc.all(),
        *Dependencies.Protobuf.all(),
        *Dependencies.Netty.all(),
        *Dependencies.BouncyCastle.all(),
        *Dependencies.Provenance.all(),
    ).forEach { it.implementation(this) }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.provenance.objectstore.gateway"
            artifactId = "client"

            from(components["java"])

            pom {
                name.set("Provenance Object Store Gateway Client Library")
                description.set("A library for interacting with the Provenance Object Store Gateway")
                url.set("https://provenance.io")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("piercetrey-figure")
                        name.set("Pierce Trey")
                        email.set("ptrey@figure.com")
                    }
                }

                scm {
                    connection.set("git@github.com:provenance-io/object-store-gateway.git")
                    developerConnection.set("git@github.com:provenance-io/object-store-gateway.git")
                    url.set("https://github.com/provenance-io/object-store-gateway")
                }
            }
        }
    }

    signing {
        sign(publishing.publications["maven"])
    }

    tasks.javadoc {
        if(JavaVersion.current().isJava9Compatible) {
            (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
        }
    }
}
