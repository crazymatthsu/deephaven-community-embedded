// Plain Java hosts: an application with its own main that runs Deephaven in-process.

import io.github.cmh.dhc.buildlogic.DeephavenRules
import io.github.cmh.dhc.buildlogic.lib
import io.github.cmh.dhc.buildlogic.libs

plugins {
    id("dhc.java-conventions")
    application
    id("dhc.verify-alignment")
}

// Deephaven BOM enforced (F9), Kafka extension excluded (F2), JVM flags on tests (F3).
DeephavenRules.applyTo(project, bomConfiguration = "implementation")

dependencies {
    // Deephaven logs through SLF4J and brings no backend. A Spring host gets Spring's; a plain
    // Java host gets Logback, configured by the host's own logback.xml.
    "runtimeOnly"(libs.lib("logback-classic"))
}

application {
    // F3 on `run` and baked into the start scripts that installDist generates.
    applicationDefaultJvmArgs = DeephavenRules.mandatoryJvmFlags
}

// The start scripts name one jar, not every jar. An embedded Deephaven has close to three hundred
// jars. Listed one by one, as the application plugin does by default, they make a classpath that
// Windows refuses: the .bat line passes the 8,191 characters cmd accepts, and the command line
// passes the 32,767 characters a Windows process may have once the install path is long.
// Instead the application's jar lists the others in its manifest, in classpath order, and the
// scripts start that jar. Deephaven compiles formulas correctly from such a layout (F7).
tasks.named<Jar>("jar") {
    val libraries = configurations.runtimeClasspath
    inputs.files(libraries).withPropertyName("runtimeClasspath").withNormalizer(ClasspathNormalizer::class)
    doFirst {
        manifest.attributes("Class-Path" to libraries.get().files.joinToString(" ") { it.name })
    }
}

tasks.named<CreateStartScripts>("startScripts") {
    classpath = files(tasks.named<Jar>("jar"))
}

// `run` is the development way to start a host, so it gets the development settings. The
// installed distribution gets none of these and starts with the safe defaults.
tasks.named<JavaExec>("run") {
    environment(DeephavenRules.developmentEnvironment(project))
}
