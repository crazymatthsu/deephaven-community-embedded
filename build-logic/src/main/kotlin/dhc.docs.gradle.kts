// The documents of the repository: the README files and docs/.
// checkDocs is part of `check`: every link from one document to another file or heading has to
// lead somewhere.

import io.github.cmh.dhc.buildlogic.CheckDocLinks

plugins {
    base
}

val repositoryRoot = rootProject.layout.projectDirectory

// What is not part of the repository: build output and the directories of tools.
val notOurs = listOf(
    "**/build/**",
    "**/.gradle/**",
    "**/.git/**",
    "**/.claude/**",
    "**/.idea/**",
    "**/node_modules/**",
)

val checkDocs = tasks.register<CheckDocLinks>("checkDocs") {
    group = "verification"
    description = "Checks that every link between the Markdown documents leads to a file and a heading that exist."
    rootDirectory = repositoryRoot
    documents.from(fileTree(repositoryRoot) {
        include("**/*.md")
        exclude(notOurs)
        // The fixture builds of the build-logic tests are not documents of this repository.
        exclude("**/src/test/fixtures/**")
    })
    // A document may link to any file, a source file included.
    targets.from(fileTree(repositoryRoot) {
        exclude(notOurs)
    })
}

tasks.named("check") {
    dependsOn(checkDocs)
}
