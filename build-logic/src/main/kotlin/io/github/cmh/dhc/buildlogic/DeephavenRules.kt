package io.github.cmh.dhc.buildlogic

import java.util.TreeMap
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/** The `libs` version catalog (gradle/libs.versions.toml) of the build a plugin is applied in. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { GradleException("gradle/libs.versions.toml has no library '$alias'") }

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { GradleException("gradle/libs.versions.toml has no version '$alias'") }.requiredVersion

/** `group:name` to version for every external module in a resolved graph, sorted by coordinates. */
internal fun moduleVersions(root: ResolvedComponentResult): Map<String, String> {
    val versions = TreeMap<String, String>()
    val seen = HashSet<ResolvedComponentResult>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (!seen.add(component)) continue
        val id = component.id
        if (id is ModuleComponentIdentifier) {
            versions["${id.group}:${id.module}"] = id.version
        }
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue.add(it.selected) }
    }
    return versions
}

/** Every dependency in a resolved graph that could not be resolved, as readable text. */
internal fun unresolvedDependencies(root: ResolvedComponentResult): List<String> {
    val unresolved = sortedSetOf<String>()
    val seen = HashSet<ResolvedComponentResult>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (!seen.add(component)) continue
        component.dependencies.forEach { dependency ->
            when (dependency) {
                is ResolvedDependencyResult -> queue.add(dependency.selected)
                is UnresolvedDependencyResult ->
                    unresolved.add("${dependency.attempted}: ${dependency.failure.message?.lineSequence()?.firstOrNull()}")
            }
        }
    }
    return unresolved.toList()
}

/**
 * The dependency and JVM rules that the feasibility spike showed every Deephaven module needs.
 * Each is written once, here, and referred to by its finding in docs/00-feasibility-spike.md.
 */
object DeephavenRules {

    const val DEEPHAVEN_GROUP = "io.deephaven"
    const val DEEPHAVEN_BOM = "deephaven-bom"

    /** F2: needs the Confluent repository, which this build does not use. */
    const val KAFKA_EXTENSION = "deephaven-extensions-kafka"

    /** F8: collides with the Jetty WebSocket implementation that Deephaven brings. */
    const val TOMCAT_GROUP = "org.apache.tomcat.embed"
    const val TOMCAT_WEBSOCKET = "tomcat-embed-websocket"

    /**
     * F3: Deephaven does not start without these, and they cannot be set from inside the JVM.
     * This list is the single definition; `run`, `bootRun`, tests and start scripts all read it.
     * Each flag is one argument (the `=` form) so it survives being written into a start script.
     */
    val mandatoryJvmFlags: List<String> = listOf(
        "--add-exports=java.management/sun.management=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
    )

    /**
     * Applies the rules that hold for every module with Deephaven on its classpath:
     * the Deephaven BOM as an enforced platform (F9), no Kafka extension (F2), and the mandatory
     * JVM flags on tests (F3).
     *
     * @param bomConfiguration `api` for libraries, so consumers inherit the platform;
     *        `implementation` for applications.
     */
    fun applyTo(project: Project, bomConfiguration: String) {
        with(project) {
            configurations.configureEach {
                exclude(group = DEEPHAVEN_GROUP, module = KAFKA_EXTENSION)
            }
            dependencies.add(bomConfiguration, dependencies.enforcedPlatform(libs.lib(DEEPHAVEN_BOM)))
            tasks.withType<Test>().configureEach {
                jvmArgs(mandatoryJvmFlags)
            }
        }
    }
}
