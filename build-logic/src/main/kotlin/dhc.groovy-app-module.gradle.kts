// apps/*-module: a role module, that is a Groovy application-mode module plus the Java it calls.
//
// The Groovy part lives in src/main/appmode/: one <id>.app per module and its scripts under <id>/.
// This plugin checks it and packages it into the jar under META-INF/dhc/app.d/, from where the
// framework copies it into an instance's work directory at start (AppModules in embed-core).
// A host therefore gets a module by depending on its project, and by nothing else.

import io.github.cmh.dhc.buildlogic.GenerateAppModuleIndex

plugins {
    id("dhc.deephaven-library")
}

val appmodeSource = layout.projectDirectory.dir("src/main/appmode")
val appmodeResourceRoot = "META-INF/dhc/app.d"

// Fails the build on a wrong key in an .app file, a file_N that does not exist, or a script that
// does not parse. It runs on the module's own classpath, so scripts are parsed by the Groovy that
// Deephaven brings (3.0.x), not by the one inside Gradle. The validator itself is in embed-core,
// which every role module depends on.
val validateAppModule = tasks.register<JavaExec>("validateAppModule") {
    group = "verification"
    description = "Checks the application-mode module(s) in src/main/appmode/."
    classpath = configurations.runtimeClasspath.get()
    mainClass = "io.github.cmh.dhc.embed.AppModuleValidator"
    args(appmodeSource.asFile.absolutePath)

    val marker = layout.buildDirectory.file("validateAppModule/ok")
    inputs.dir(appmodeSource).withPropertyName("appmode").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(marker)
    doLast { marker.get().asFile.writeText("ok\n") }
    // A role module whose Groovy part has not been written yet has nothing to check.
    onlyIf("src/main/appmode exists") { appmodeSource.asFile.isDirectory }
}

val generateAppModuleIndex = tasks.register<GenerateAppModuleIndex>("generateAppModuleIndex") {
    description = "Writes the list of files of each application-mode module, for the jar."
    appmodeDirectory = appmodeSource
    outputDirectory = layout.buildDirectory.dir("generated/appmodeIndex")
    // A module that does not validate is not packaged.
    dependsOn(validateAppModule)
    onlyIf("src/main/appmode exists") { appmodeSource.asFile.isDirectory }
}

tasks.named<ProcessResources>("processResources") {
    from(appmodeSource) { into(appmodeResourceRoot) }
    from(generateAppModuleIndex) { into(appmodeResourceRoot) }
}

tasks.named("check") {
    dependsOn(validateAppModule)
}
