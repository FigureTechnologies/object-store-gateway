group = "tech.figure.objectstore.gateway"

plugins {
    kotlin("jvm")
    id("com.figure.gradle.semver-plugin")
    id("com.github.breadmoirai.github-release")
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

val githubToken = findProperty("githubToken")?.toString() ?: System.getenv("GITHUB_TOKEN")
val overwriteRelease = (findProperty("overwriteRelease")?.toString() ?: System.getenv("OVERWRITE_RELEASE"))?.toBoolean() ?: false

githubRelease {
    apiEndpoint("https://api.github.com")
    body("")
    draft(false)
    dryRun(false)
    generateReleaseNotes(true)
    overwrite(overwriteRelease)
    owner("FigureTechnologies")
    prerelease(false)
    repo("object-store-gateway")
    tagName(semver.versionTagName)
    targetCommitish("main")
    token(githubToken)
    client
}

semver {
    // All properties are optional, but it's a good idea to declare those that you would want
    // to override with Gradle properties or environment variables, e.g. "overrideVersion" below
    tagPrefix("v")
    initialVersion("0.0.0")
    findProperty("semver.overrideVersion")?.toString()
        ?.let { overrideVersion(it) }

    val semVerModifier = findProperty("semver.modifier")?.toString()
        ?.let { buildVersionModifier(it) }
        ?: { nextPatch() }
    versionModifier(semVerModifier)
}

rootProject.version = semver.version

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
