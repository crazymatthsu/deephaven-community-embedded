package io.github.cmh.dhc.buildlogic

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
 * Checks the shell scripts against the rule they are written under: they run on the bash 3.2
 * that macOS ships, on Linux and in Windows Git Bash. It reports every problem it finds.
 *
 * It reads the scripts as text and runs nothing, so it gives the same answer on every machine,
 * with or without a bash. What it looks for is what has no place in such a script:
 *  - what bash learned after 3.2: associative arrays, name references, `mapfile`, `coproc`,
 *    `${name^^}` and `${name,,}`, `&>>`, `|&`;
 *  - options only the GNU tools have, where macOS has the BSD ones: `sed -i`, `sed -r`,
 *    `grep -P`, `readlink -f`, `date -d`, `xargs -r`, `stat -c`, and the commands `timeout` and
 *    `realpath`;
 *  - Windows line endings, which bash reads as part of the command;
 *  - a script that is run and does not stop at its first error (`set -eu`).
 *
 * It is not a shell parser. A line that needs one of these on purpose can end with the comment
 * `# checkScripts: allow`, which also tells the reader that somebody decided so.
 */
abstract class CheckScripts : DefaultTask() {

    /** The repository root: scripts are reported relative to it. */
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scripts: ConfigurableFileCollection

    private class Rule(val pattern: Regex, val message: String)

    private val start = """(?:^|[;&|(`]|\$\(|\b(?:then|do|else|if|while|!)\s)\s*"""

    private val rules = listOf(
        Rule(Regex("""\b(?:declare|local|typeset)\s+-[a-zA-Z]*A"""), "an associative array; bash 3.2 has none"),
        Rule(Regex("""\b(?:declare|local|typeset)\s+-[a-zA-Z]*n\b"""), "a name reference; bash 3.2 has none"),
        Rule(Regex("""$start(?:mapfile|readarray)\b"""), "mapfile/readarray; bash 3.2 has neither. Use a while read loop"),
        Rule(Regex("""${start}coproc\b"""), "coproc; bash 3.2 has none"),
        Rule(Regex("""\$\{[A-Za-z_][A-Za-z0-9_]*(?:\[[^]]*])?(?:\^\^?|,,?)"""), "\${name^^} or \${name,,}; bash 3.2 has neither. Use tr"),
        Rule(Regex("""&>>"""), "&>>; bash 3.2 has none. Write >> file 2>&1"),
        Rule(Regex("""\|&"""), "|&; bash 3.2 has none. Write 2>&1 |"),
        Rule(Regex("""\bsed\s+(?:-[a-zA-Z]+\s+)*-[a-zA-Z]*i"""), "sed -i; GNU and BSD sed disagree about it. Write to a new file and move it"),
        Rule(Regex("""\bsed\s+(?:-[a-zA-Z]+\s+)*-[a-zA-Z]*r"""), "sed -r; BSD sed has none. Write sed -E"),
        Rule(Regex("""\bgrep\s+(?:-[a-zA-Z]+\s+)*-[a-zA-Z]*P"""), "grep -P; BSD grep has none"),
        Rule(Regex("""\breadlink\s+(?:-[a-zA-Z]+\s+)*-[a-zA-Z]*f"""), "readlink -f; BSD readlink has none. Use cd and pwd"),
        Rule(Regex("""\bdate\s+(?:[+-][^\s]+\s+)*(?:-d\b|--date)"""), "date -d; BSD date has none"),
        Rule(Regex("""\bxargs\s+(?:-[a-zA-Z]+\s+)*-[a-zA-Z]*r"""), "xargs -r; BSD xargs has none"),
        Rule(Regex("""\bstat\s+(?:-[a-zA-Z]+\s+)*-[a-zA-Z]*c"""), "stat -c; BSD stat has none"),
        Rule(Regex("""${start}timeout\s"""), "the timeout command; macOS has none. Count in a loop"),
        Rule(Regex("""${start}realpath\s"""), "the realpath command; older macOS has none. Use cd and pwd"),
    )

    private val allowed = Regex("""#\s*checkScripts:\s*allow\s*$""")
    private val stopsAtErrors = Regex("""^\s*set\s+-[a-zA-Z]*e[a-zA-Z]*u|^\s*set\s+-[a-zA-Z]*u[a-zA-Z]*e""")

    @TaskAction
    fun check() {
        val root = rootDirectory.get().asFile
        val files = scripts.files.sortedBy { it.path }
        if (files.isEmpty()) {
            throw GradleException("checkScripts: no shell scripts found. A check of nothing would pass for the wrong reason.")
        }
        val problems = mutableListOf<String>()
        for (file in files) {
            val name = file.relativeTo(root).invariantSeparatorsPath
            val text = file.readText()
            if (text.contains('\r')) {
                problems += "$name: has Windows line endings. bash reads the carriage return as part of the command; " +
                    ".gitattributes asks for LF."
            }
            val lines = text.replace("\r", "").lines()
            val first = lines.firstOrNull().orEmpty()
            val run = first.startsWith("#!")
            when {
                run && first != "#!/usr/bin/env bash" ->
                    problems += "$name line 1: starts with '$first'. Write #!/usr/bin/env bash: bash is not at the same " +
                        "path everywhere, and sh is not bash."
                !first.startsWith("#") ->
                    problems += "$name line 1: a script starts with #!/usr/bin/env bash, a file that is only sourced " +
                        "with a comment that says so."
                run && lines.none { stopsAtErrors.containsMatchIn(it) } ->
                    problems += "$name: has no 'set -eu'. Without it a failed command or a mistyped variable goes unnoticed."
            }
            lines.forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.startsWith("#") || allowed.containsMatchIn(line)) return@forEachIndexed
                for (rule in rules) {
                    if (rule.pattern.containsMatchIn(line)) {
                        problems += "$name line ${index + 1}: ${rule.message}: $line"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "checkScripts found ${problems.size} problem(s) in ${files.size} script(s). They have to run on macOS " +
                    "bash 3.2, Linux and Windows Git Bash:\n" + problems.joinToString("\n") { "  - $it" }
            )
        }
        logger.lifecycle("checkScripts: ${files.size} scripts, nothing that bash 3.2 or the BSD tools would refuse.")
    }
}
