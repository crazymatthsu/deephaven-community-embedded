package io.github.cmh.dhc.buildlogic

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Checks the configuration directory (design section 6): `defaults.env`, `apps/<app>.env` and
 * `env/<environment>.env`. It reports every problem it finds, not only the first.
 *
 * The rules, and why each is there:
 *  - a line is `KEY=value` with a key named `DHC_<NAME>`, no quotes, no `$`, no spaces around `=`:
 *    Java, bash and a compose `env_file` then read the same value;
 *  - a key is written once per file;
 *  - no key looks like a secret: keys and passwords come from the environment, never from a file
 *    in the repository;
 *  - every key is in `defaults.env`, so a typo is not taken for a setting;
 *  - a key scoped to an application (`DHC_DHC_B_HTTP_PORT`) appears only in an environment file,
 *    and the application exists;
 *  - `dev`, `uat` and `prod` exist and have the same keys, so that nothing is set for one
 *    environment and forgotten for another.
 */
abstract class ValidateConfig : DefaultTask() {

    @get:Internal
    abstract val configDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val files: ConfigurableFileCollection

    private val requiredEnvironments = listOf("dev", "uat", "prod")
    private val secretLooking = Regex("PSK|PASSWORD|PASSWD|SECRET|TOKEN|PRIVATE|CREDENTIAL")
    private val keyPattern = Regex("DHC_[A-Z0-9_]+")

    @TaskAction
    fun validate() {
        val root = configDirectory.get().asFile
        val problems = mutableListOf<String>()

        val defaultsFile = root.resolve("defaults.env")
        if (!defaultsFile.isFile) {
            throw GradleException("validateConfig: $defaultsFile does not exist. It lists every setting with its default.")
        }
        val defaults = read(defaultsFile, root, problems)
        val appFiles = envFiles(root.resolve("apps"))
        val environmentFiles = envFiles(root.resolve("env"))
        val apps = appFiles.map { it.nameWithoutExtension }

        for (key in defaults.keys) {
            scopeOf(key, apps)?.let { problems += "defaults.env: $key is scoped to the application $it. Scoped keys belong in env/<environment>.env." }
        }
        for (file in appFiles) {
            val name = "apps/${file.name}"
            for (key in read(file, root, problems).keys) {
                val scope = scopeOf(key, apps)
                when {
                    scope != null -> problems += "$name: $key is scoped to the application $scope. This file is one application's already; write the plain key."
                    key !in defaults -> problems += "$name: $key is not a setting. Every setting is in defaults.env; is this a typo?"
                }
            }
        }

        val keysByEnvironment = linkedMapOf<String, Set<String>>()
        for (file in environmentFiles) {
            val name = "env/${file.name}"
            val keys = read(file, root, problems).keys
            keysByEnvironment[file.nameWithoutExtension] = keys
            for (key in keys) {
                val scope = scopeOf(key, apps)
                val plain = if (scope == null) key else "DHC_" + key.removePrefix(scopePrefix(scope))
                if (plain !in defaults) {
                    val scopes = apps.joinToString(", ") { scopePrefix(it) + "<NAME> for $it" }
                    problems += "$name: $key is not a setting. Every setting is in defaults.env, and a key scoped to an " +
                        "application is written $scopes; is this a typo?"
                }
            }
        }
        for (environment in requiredEnvironments) {
            if (environment !in keysByEnvironment) {
                problems += "env/$environment.env does not exist. The environments dev, uat and prod each need a file."
            }
        }
        val everyKey = keysByEnvironment.values.flatten().toSortedSet()
        for ((environment, keys) in keysByEnvironment) {
            for (key in everyKey - keys) {
                val elsewhere = keysByEnvironment.filterValues { key in it }.keys.joinToString(", ") { "$it.env" }
                problems += "env/$environment.env: $key is missing. It is set in $elsewhere, and every environment file " +
                    "has to have the same keys."
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "validateConfig found ${problems.size} problem(s) in $root:\n" + problems.joinToString("\n") { "  - $it" }
            )
        }
        logger.lifecycle(
            "validateConfig: ${defaults.size} settings, ${apps.size} application(s) $apps, " +
                "${keysByEnvironment.size} environment(s) ${keysByEnvironment.keys}."
        )
    }

    private fun envFiles(directory: File): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".env") }?.sortedBy { it.name }.orEmpty()

    private fun scopePrefix(app: String) = "DHC_" + app.uppercase().replace('-', '_') + "_"

    /** The application a key is scoped to, the longest id first so that `dhc-a-x` is not taken for `dhc-a`. */
    private fun scopeOf(key: String, apps: List<String>): String? =
        apps.sortedByDescending { it.length }.firstOrNull { key.startsWith(scopePrefix(it)) }

    private fun read(file: File, root: File, problems: MutableList<String>): Map<String, String> {
        val name = file.relativeTo(root).invariantSeparatorsPath
        val values = linkedMapOf<String, String>()
        file.readLines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val place = "$name line ${index + 1}"
            val equals = line.indexOf('=')
            val key = if (equals < 0) "" else line.substring(0, equals)
            val value = if (equals < 0) "" else line.substring(equals + 1)
            when {
                !keyPattern.matches(key) ->
                    problems += "$place: not KEY=value with a key named DHC_<NAME>: $line"
                secretLooking.containsMatchIn(key) ->
                    // The value is not repeated: if it is a real secret, it should not reach a build log as well.
                    problems += "$place: $key looks like a secret. Secrets come from the environment, never from a file " +
                        "in the repository; remove the line."
                value != value.trim() || value.contains('"') || value.contains('\'') || value.contains('$') ->
                    problems += "$place: the value of $key has quotes, a \$ or spaces at its ends. The file is read by " +
                        "Java, bash and compose, which agree only on plain text."
                key in values ->
                    problems += "$place: $key is set twice in this file."
                else -> values[key] = value
            }
        }
        return values
    }
}
