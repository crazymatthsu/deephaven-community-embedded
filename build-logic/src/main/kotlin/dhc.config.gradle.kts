// The configuration directory: defaults.env, apps/ and env/ (design section 6).
// validateConfig is part of `check`; configArchive packages the directory for a deployment that
// mounts it instead of using the copy inside an image.

import io.github.cmh.dhc.buildlogic.ValidateConfig

plugins {
    base
}

val settingsFiles = fileTree(layout.projectDirectory) {
    include("defaults.env", "apps/*.env", "env/*.env")
}

val validateConfig = tasks.register<ValidateConfig>("validateConfig") {
    group = "verification"
    description = "Checks defaults.env, apps/*.env and env/*.env: format, no secrets, no unknown keys, same keys in every environment."
    configDirectory = layout.projectDirectory
    files.from(settingsFiles)
}

tasks.named("check") {
    dependsOn(validateConfig)
}

val configArchive = tasks.register<Zip>("configArchive") {
    group = "build"
    description = "Packages the settings files, as checked, into one archive."
    dependsOn(validateConfig)
    from(settingsFiles)
    archiveBaseName = "dhc-config"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named("assemble") {
    dependsOn(configArchive)
}
