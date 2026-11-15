package io.github.cmh.dhc.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes `<id>.index` for every application-mode module in `src/main/appmode/`: the module's
 * files, one per line.
 *
 * A jar cannot be listed like a directory, so the framework reads this index to know which files
 * to copy out of the jar when an application starts (`AppModules` in embed-core).
 */
@CacheableTask
abstract class GenerateAppModuleIndex : DefaultTask() {

    /** `src/main/appmode/`: one `<id>.app` per module, its scripts under `<id>/`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appmodeDirectory: DirectoryProperty

    /** Receives `<id>.index` files; added to the jar next to the module files. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = appmodeDirectory.get().asFile
        val target = outputDirectory.get().asFile
        target.listFiles()?.forEach { it.delete() }
        target.mkdirs()
        source.listFiles { file -> file.isFile && file.name.endsWith(".app") }.orEmpty().sortedBy { it.name }.forEach { app ->
            val id = app.name.removeSuffix(".app")
            val scripts = source.resolve(id).walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(source).invariantSeparatorsPath }
                .sorted()
                .toList()
            target.resolve("$id.index").writeText((listOf(app.name) + scripts).joinToString(separator = "\n", postfix = "\n"))
        }
    }
}
