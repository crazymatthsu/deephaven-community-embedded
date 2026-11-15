plugins {
    id("dhc.deephaven-library")
}

description = "JUnit support for embedded Deephaven: the extension, table assertions, the probe client."

dependencies {
    api(project(":framework:embed-core"))

    // This module is test support, so JUnit is part of its main API.
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter.api)
}
