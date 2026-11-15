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

// `run` is the development way to start a host, so it gets the development settings. The
// installed distribution gets none of these and starts with the safe defaults.
tasks.named<JavaExec>("run") {
    // Anonymous access for development only (decision D6); DHC_AUTH_MODE in the environment wins.
    environment("DHC_AUTH_MODE", providers.environmentVariable("DHC_AUTH_MODE").getOrElse("anonymous"))
    // The instance's own directory (F10) stays inside build/, so `clean` removes it.
    environment(
        "DHC_WORK_DIR",
        providers.environmentVariable("DHC_WORK_DIR").getOrElse(layout.buildDirectory.dir("dhc-work").get().asFile.absolutePath),
    )
}
