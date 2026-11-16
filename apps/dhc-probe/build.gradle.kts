plugins {
    id("dhc.embedded-java-app")
}

description = "Tool: reads a table of a running instance from outside, for scripts/smoke.sh."

// Not a host: nothing of Deephaven is started here. It is built like one because it needs the
// same things on the way out: Deephaven's client libraries at Deephaven's versions, the JVM
// flags, and start scripts that work in every shell.
application {
    mainClass = "io.github.cmh.dhc.testkit.Probe"
}

dependencies {
    implementation(project(":framework:embed-testkit"))
}
