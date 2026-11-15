// Plain Java hosts: an application with its own main that runs Deephaven in-process.
//
// P1 gives it its dependency rules and the mandatory JVM flags.
// P2 wires the config directory and environment into `run`.

import io.github.cmh.dhe.buildlogic.DeephavenRules

plugins {
    id("dhe.java-conventions")
    application
    id("dhe.verify-alignment")
}

// Deephaven BOM enforced (F9), Kafka extension excluded (F2), JVM flags on tests (F3).
DeephavenRules.applyTo(project, bomConfiguration = "implementation")

application {
    // F3 on `run` and baked into the start scripts that installDist generates.
    applicationDefaultJvmArgs = DeephavenRules.mandatoryJvmFlags
}
