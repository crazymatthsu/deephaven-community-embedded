plugins {
    id("dhc.deephaven-library")
}

description = "Runs Deephaven inside a JVM: start/stop, component factory, settings, engine access."

dependencies {
    // The dependency set the feasibility spike started a server with.
    api(libs.deephaven.server.jetty)
    implementation(libs.deephaven.extensions.flight.sql)
    implementation(libs.deephaven.util.processenvironment)
    runtimeOnly(libs.deephaven.log.to.slf4j)
}
