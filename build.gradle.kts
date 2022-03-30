group = "io.provenance.objectstore.gateway"
version = project.property("version")?.takeIf { it != "unspecified" } ?: "1.0-SNAPSHOT"

plugins {
	Plugins.Idea.addTo(this)
	Plugins.NexusPublishing.addTo(this)
}

nexusPublishing {
	repositories {
		sonatype {
			nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
			snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
			username.set(findProject("ossrhUsername")?.toString() ?: System.getenv("OSSRH_USERNAME"))
			password.set(findProject("ossrhPassword")?.toString() ?: System.getenv("OSSRH_PASSWORD"))
//            stagingProfileId.set("3180ca260b82a7") // prevents querying for the staging profile id, performance optimization
		}
	}
}
