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

tasks.test {
    // One test loads the repository's own config/ directory, for every application and environment.
    val config = rootProject.layout.projectDirectory.dir("config")
    inputs.files(fileTree(config) { include("defaults.env", "apps/**", "env/**") })
        .withPropertyName("repositoryConfig")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("dhc.repo.config", config.asFile.absolutePath)
}
