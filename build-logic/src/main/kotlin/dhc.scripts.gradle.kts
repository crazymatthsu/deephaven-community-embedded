// The shell scripts of the repository: scripts/ and the two that go into the container images.
// checkScripts is part of `check`: nothing in them that macOS bash 3.2 or the BSD tools refuse.

import io.github.cmh.dhc.buildlogic.CheckScripts

plugins {
    base
}

val repositoryRoot = rootProject.layout.projectDirectory

val checkScripts = tasks.register<CheckScripts>("checkScripts") {
    group = "verification"
    description = "Checks that the shell scripts keep to what macOS bash 3.2, Linux and Windows Git Bash all run."
    rootDirectory = repositoryRoot
    scripts.from(fileTree(repositoryRoot) {
        include("scripts/*.sh", "docker/*.sh")
    })
}

tasks.named("check") {
    dependsOn(checkScripts)
}
