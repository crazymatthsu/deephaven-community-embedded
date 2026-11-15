plugins {
    id("dhc.deephaven-library")
}

description = "Spring Boot auto-configuration around embed-core."

dependencies {
    // F9: Spring's BOM as a plain platform. It only suggests versions, so Deephaven's win.
    api(platform(libs.spring.boot.dependencies))

    api(project(":framework:embed-core"))
    implementation(libs.spring.boot.autoconfigure)
}
