plugins {
    id("dhc.groovy-app-module")
}

description = "Role A: publishes mock order executions. A Groovy application-mode module plus the Java it calls."

dependencies {
    api(project(":framework:embed-core"))
    api(project(":framework:orders-model"))

    testImplementation(project(":framework:embed-testkit"))
    // Deephaven logs through SLF4J; without a backend a failing test would have no log to read.
    testRuntimeOnly(libs.logback.classic)
}
