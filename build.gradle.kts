group = "io.provenance.objectstore.gateway"
version = project.property("version")?.takeIf { it != "unspecified" } ?: "1.0-SNAPSHOT"

plugins {
	kotlin("jvm")
//	kotlin("plugin.spring") version "1.6.10" apply false
//	id("org.springframework.boot") version "2.6.4" apply false
//	id("io.spring.dependency-management") version "1.0.11.RELEASE" apply false
	id("idea")
//	id("com.google.protobuf") version "0.8.18" apply false
	id("maven-publish")
	id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
	id("signing")
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
            stagingProfileId.set("3180ca260b82a7") // prevents querying for the staging profile id, performance optimization
		}
	}
}

subprojects {
	val projectName = name
	if (listOf("client", "proto").contains(projectName)) {
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
					groupId = "io.provenance.objectstore.gateway"
					artifactId = projectName

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
	}
}
