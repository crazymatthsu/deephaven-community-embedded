package io.github.cmh.dhe.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Extension `deephavenAlignment`, created by the dhe.verify-alignment plugin. */
abstract class DeephavenAlignmentExtension {

    /**
     * "Deephaven alone": root of the graph that the application's Deephaven modules resolve to
     * under the Deephaven BOM with nothing else on the classpath.
     */
    abstract val reference: Property<ResolvedComponentResult>
}

/**
 * Fails when a library on an application's runtime classpath resolves to a different version than
 * Deephaven alone resolves it to (F9).
 *
 * "Deephaven alone" is [referenceRoot]: the Deephaven modules the application uses, resolved
 * under the Deephaven BOM with nothing else on the classpath. Libraries Deephaven does not bring
 * (Spring, Tomcat, the application's own) are not compared.
 */
@DisableCachingByDefault(because = "Compares two already resolved graphs; nothing worth caching")
abstract class VerifyDeephavenAlignment : DefaultTask() {

    /** Root of the application's resolved runtime classpath. */
    @get:Input
    abstract val applicationRoot: Property<ResolvedComponentResult>

    /** Root of the graph that the same Deephaven modules resolve to on their own. */
    @get:Input
    abstract val referenceRoot: Property<ResolvedComponentResult>

    /** Gradle path of the application, for messages. */
    @get:Input
    abstract val applicationPath: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val app = applicationPath.get()
        // A resolution result is lenient: a graph that failed to resolve would otherwise look
        // like a graph with fewer libraries, and compare as equal.
        failOnUnresolved("the runtime classpath of $app", applicationRoot.get())
        failOnUnresolved("the Deephaven modules of $app resolved on their own", referenceRoot.get())

        val actual = moduleVersions(applicationRoot.get())
        val reference = moduleVersions(referenceRoot.get())

        val deephavenModules = reference.keys.count {
            it.startsWith("${DeephavenRules.DEEPHAVEN_GROUP}:") && it != "${DeephavenRules.DEEPHAVEN_GROUP}:${DeephavenRules.DEEPHAVEN_BOM}"
        }
        if (deephavenModules == 0) {
            throw GradleException(
                "verifyDeephavenAlignment: $app has no ${DeephavenRules.DEEPHAVEN_GROUP} module on its runtime " +
                    "classpath, so there is nothing to compare. An embedded application must depend on Deephaven."
            )
        }

        val mismatches = reference.filter { (module, version) -> actual[module] != null && actual[module] != version }
        val lines = reference.map { (module, version) ->
            val resolved = actual[module]
            when {
                resolved == null -> "absent    $module (Deephaven alone: $version)"
                resolved != version -> "MISMATCH  $module application: $resolved, Deephaven alone: $version"
                else -> "ok        $module:$version"
            }
        }
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "Deephaven alignment of $app: ${reference.size} libraries compared, ${mismatches.size} mismatched\n" +
                    lines.joinToString(separator = "\n", postfix = "\n")
            )
        }

        if (mismatches.isNotEmpty()) {
            val first = mismatches.keys.first()
            throw GradleException(
                buildString {
                    appendLine(
                        "verifyDeephavenAlignment: ${mismatches.size} of ${reference.size} libraries on the runtime " +
                            "classpath of $app resolve to a different version than Deephaven alone resolves them to."
                    )
                    mismatches.forEach { (module, version) ->
                        appendLine("  $module  application: ${actual[module]}  Deephaven alone: $version")
                    }
                    appendLine("These versions are chosen by Deephaven and must not be overridden (finding F9).")
                    appendLine("To see what changed one of them:")
                    append("  ./gradlew $app:dependencyInsight --configuration runtimeClasspath --dependency $first")
                }
            )
        }
        logger.info("verifyDeephavenAlignment: {} libraries of {} match Deephaven alone", reference.size, app)
    }

    private fun failOnUnresolved(what: String, root: ResolvedComponentResult) {
        val unresolved = unresolvedDependencies(root)
        if (unresolved.isNotEmpty()) {
            throw GradleException(
                "verifyDeephavenAlignment: could not resolve $what:\n" + unresolved.joinToString("\n") { "  $it" }
            )
        }
    }
}
