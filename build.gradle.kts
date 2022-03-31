group = "io.provenance.objectstore.gateway"
version = project.property("version")?.takeIf { it != "unspecified" } ?: "1.0-SNAPSHOT"

plugins {
	Plugins.Idea.addTo(this)
	Plugins.NexusPublishing.addTo(this)
	Plugins.MavenPublish.addTo(this)
	Plugins.Signing.addTo(this)

	kotlin("jvm") version "1.6.10"
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
	if (listOf("client, proto").contains(this.name)) {
		publishing {
			publications {
				create<MavenPublication>("maven") {
					groupId = "io.provenance.objectstore.gateway"
					artifactId = this.name

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
