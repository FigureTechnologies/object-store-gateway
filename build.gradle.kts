group = "tech.figure.objectstore.gateway"
version = project.property("version")?.takeIf { it != "unspecified" } ?: "1.0-SNAPSHOT"

plugins {
    kotlin("jvm")
    id("idea")
    id("maven-publish")
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
    id("signing")
    id("org.jlleitschuh.gradle.ktlint")
    id("com.adarshr.test-logger")
}

repositories {
    mavenCentral()
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
            username.set(findProject("ossrhUsername")?.toString() ?: System.getenv("OSSRH_USERNAME"))
            password.set(findProject("ossrhPassword")?.toString() ?: System.getenv("OSSRH_PASSWORD"))
            stagingProfileId.set("858b6e4de4734a") // tech.figure staging profile id
        }
    }
}

subprojects {
    val projectName = name
    if (projectName in listOf("client", "server", "shared")) {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "com.adarshr.test-logger")
    }
    if (projectName in listOf("client", "proto", "shared")) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")
        apply(plugin = "java-library")

        java {
            withJavadocJar()
            withSourcesJar()
        }

        publishing {
            publications {
                create<MavenPublication>("maven") {
                    groupId = "tech.figure.objectstore.gateway"
                    artifactId = projectName

                    from(components["java"])

                    pom {
                        name.set("Figure Tech Object Store Gateway Client Library")
                        description.set("A library for interacting with the Figure Tech Object Store Gateway")
                        url.set("https://figure.tech")

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
                            developer {
                                id.set("hyperschwartz")
                                name.set("Jake Schwartz")
                                email.set("jschwartz@figure.com")
                            }
                        }

                        scm {
                            connection.set("git@github.com:FigureTechnologies/object-store-gateway.git")
                            developerConnection.set("git@github.com:FigureTechnologies/object-store-gateway.git")
                            url.set("https://github.com/FigureTechnologies/object-store-gateway")
                        }
                    }
                }
            }

            signing {
                sign(publishing.publications["maven"])
            }

            tasks.javadoc {
                if (JavaVersion.current().isJava9Compatible) {
                    (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
                }
            }
        }
    }
}
