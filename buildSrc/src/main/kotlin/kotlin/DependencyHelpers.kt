import org.gradle.api.plugins.ObjectConfigurationAction
import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.gradle.kotlin.dsl.ScriptHandlerScope
import org.gradle.kotlin.dsl.exclude
import org.gradle.plugin.use.PluginDependenciesSpec

data class PluginSpec(
    val id: String,
    val version: String = ""
) {
    fun addTo(scope: PluginDependenciesSpec) {
        scope.id(id).also { spec ->
            if (version.isNotEmpty()) {
                spec.version(version)
            }
        }
    }

    fun addTo(action: ObjectConfigurationAction) {
        action.plugin(this.id)
    }
}

data class DependencySpec(
    val name: String,
    val version: String = "",
    val isChanging: Boolean = false,
    val isTransitive: Boolean = true,
    val exclude: List<String> = emptyList()
) {
    fun plugin(scope: PluginDependenciesSpec) {
        scope.apply {
            id(name).version(version.takeIf { it.isNotEmpty() })
        }
    }

    fun classpath(scope: ScriptHandlerScope) {
        val spec = this
        with(scope) {
            dependencies {
                classpath(spec.toDependencyNotation())
            }
        }
    }

    fun implementation(handler: DependencyHandlerScope) {
        applyDependency("implementation", handler)
    }

    fun api(handler: DependencyHandlerScope) {
        applyDependency("api", handler)
    }

    fun protobuf(handler: DependencyHandlerScope) {
        applyDependency("protobuf", handler)
    }

    fun testImplementation(handler: DependencyHandlerScope) {
        applyDependency("testImplementation", handler)
    }

    fun toDependencyNotation(): String =
        listOfNotNull(
            name,
            version.takeIf { it.isNotEmpty() }
        ).joinToString(":")

    private fun applyDependency(type: String, handler: DependencyHandlerScope) {
        val spec = this
        with(handler) {
            type.invoke(spec.toDependencyNotation()) {
                isChanging = spec.isChanging
                isTransitive = spec.isTransitive
                spec.exclude.forEach { excludeDependencyNotation ->
                    val (group, module) = excludeDependencyNotation.split(":", limit = 2)
                    this.exclude(group = group, module = module)
                }
            }
        }
    }
}

/**
 * Helper class to wrap your dependency objects in.  Allows for all added dependencies to be collected, and then
 * pulled as a group during dependency declaration for more concise syntax.
 * If you REALLY want to, you can just make one mega object and include them all, and then your dependencies block
 * in your build.gradle.kts becomes a single line for all your dependencies.  That's only if you want to include absolutely
 * everything, though.
 */
abstract class DependencyCollector {
    private val dependencies = mutableListOf<DependencySpec>()

    fun DependencySpec.include(): DependencySpec = this.also { dependencies.add(it) }

    fun included(inclusionFn: () -> DependencySpec): DependencySpec = inclusionFn().include()

    fun all(): Array<DependencySpec> = dependencies.toTypedArray()
}
