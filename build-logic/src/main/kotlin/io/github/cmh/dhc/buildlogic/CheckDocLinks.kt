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
 * Checks the links between the Markdown documents of the repository: a link to a file has to
 * point at a file that exists, and a link to a heading at a heading that exists. It reports
 * every broken link it finds, not only the first.
 *
 * The documents send a reader from a symptom to a finding to a section of the design, so a link
 * that goes nowhere is a real fault, and one that renaming a heading causes without any other
 * sign. Links to other sites are not followed: the build needs no network beyond its
 * repositories, and a site being down must not fail it.
 *
 * What it understands: inline links and images `[text](target)`, with headings turned into
 * anchors the way GitHub does it. Code blocks and code spans are skipped.
 */
abstract class CheckDocLinks : DefaultTask() {

    /** The repository root: links are reported relative to it and may not leave it. */
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    /** Every Markdown document to check. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val documents: ConfigurableFileCollection

    /**
     * Everything a link may point at. Declared so that deleting a file a document links to makes
     * the task run again.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val targets: ConfigurableFileCollection

    private val link = Regex("""!?\[[^\]\n]*]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)""")
    private val heading = Regex("""^ {0,3}#{1,6}\s+(.*?)\s*#*\s*$""")
    private val fence = Regex("""^ {0,3}(```|~~~)""")
    private val codeSpan = Regex("""`[^`\n]*`""")
    private val otherSite = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""")
    private val markdownLink = Regex("""!?\[([^\]]*)]\([^)]*\)""")

    @TaskAction
    fun check() {
        val root = rootDirectory.get().asFile.canonicalFile
        val files = documents.files.map { it.canonicalFile }.sortedBy { it.path }
        if (files.isEmpty()) {
            throw GradleException("checkDocs: no Markdown documents found below $root. A check of nothing would pass for the wrong reason.")
        }
        val anchors = HashMap<File, Set<String>>()
        val problems = mutableListOf<String>()
        var links = 0

        for (file in files) {
            val name = file.relativeTo(root).invariantSeparatorsPath
            for ((number, text) in prose(file)) {
                for (match in link.findAll(text)) {
                    val target = match.groupValues[1]
                    if (otherSite.containsMatchIn(target)) continue
                    links++
                    val place = "$name line $number"
                    val path = target.substringBefore('#')
                    val anchor = target.substringAfter('#', "")
                    val linked = if (path.isEmpty()) file else File(file.parentFile, decode(path)).canonicalFile
                    when {
                        !linked.startsWith(root) ->
                            problems += "$place: [$target] points outside the repository."
                        !linked.exists() ->
                            problems += "$place: [$target] points at ${linked.relativeTo(root).invariantSeparatorsPath}, which does not exist."
                        anchor.isNotEmpty() && linked.isFile && linked.name.endsWith(".md") -> {
                            val known = anchors.getOrPut(linked) { anchorsOf(linked) }
                            if (anchor.lowercase() !in known) {
                                problems += "$place: [$target] names a heading that ${linked.relativeTo(root).invariantSeparatorsPath} " +
                                    "does not have. Its headings are: ${known.sorted().joinToString(", ") { "#$it" }}"
                            }
                        }
                    }
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "checkDocs found ${problems.size} broken link(s) in ${files.size} document(s):\n" +
                    problems.joinToString("\n") { "  - $it" }
            )
        }
        logger.lifecycle("checkDocs: ${files.size} documents, $links links between them, none broken.")
    }

    /** The lines of a document that are text: no code blocks, and code spans blanked out. */
    private fun prose(file: File): List<Pair<Int, String>> {
        val lines = mutableListOf<Pair<Int, String>>()
        var open: String? = null
        file.readLines().forEachIndexed { index, line ->
            val marker = fence.find(line)?.groupValues?.get(1)
            when {
                open == null && marker != null -> open = marker
                open != null && marker == open -> open = null
                open == null -> lines += (index + 1) to codeSpan.replace(line, "")
            }
        }
        return lines
    }

    /** The anchors of a document's headings, as GitHub makes them. */
    private fun anchorsOf(file: File): Set<String> {
        val seen = HashMap<String, Int>()
        val result = linkedSetOf<String>()
        var open: String? = null
        for (line in file.readLines()) {
            val marker = fence.find(line)?.groupValues?.get(1)
            if (open == null && marker != null) {
                open = marker
                continue
            }
            if (open != null) {
                if (marker == open) open = null
                continue
            }
            val text = heading.find(line)?.groupValues?.get(1) ?: continue
            val slug = slug(text)
            val count = seen.merge(slug, 1, Int::plus)!!
            // A second heading with the same text gets -1, a third -2.
            result += if (count == 1) slug else "$slug-${count - 1}"
        }
        return result
    }

    /**
     * GitHub's rule: the text of the heading without its markup, in lower case, without anything
     * that is not a letter, a digit, a space, a hyphen or an underscore, and then every space as
     * a hyphen.
     */
    internal fun slug(headingText: String): String {
        val plain = markdownLink.replace(headingText) { it.groupValues[1] }
        return plain.lowercase()
            .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
            .replace(' ', '-')
    }

    private fun decode(path: String): String = path.replace("%20", " ")
}
