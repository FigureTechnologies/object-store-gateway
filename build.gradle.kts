group = "io.provenance.objectstore.gateway"
version = project.property("version")?.takeIf { it != "unspecified" } ?: "1.0-SNAPSHOT"

plugins {
	Plugins.Idea.addTo(this)
	Plugins.NexusPublishing.addTo(this)
}
