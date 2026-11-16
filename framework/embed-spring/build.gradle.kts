plugins {
    id("dhc.deephaven-library")
}

description = "Spring Boot auto-configuration around embed-core."

dependencies {
    // F9: Spring's BOM as a plain platform. It only suggests versions, so Deephaven's win.
    api(platform(libs.spring.boot.dependencies))

    api(project(":framework:embed-core"))
    implementation(libs.spring.boot.autoconfigure)

    // Optional for a host: the health indicators exist when it has the actuator, the table
    // listing when it has Spring MVC. Neither is forced on a host that wants neither.
    compileOnly(libs.spring.boot.actuator.autoconfigure)
    compileOnly(libs.spring.webmvc)

    testImplementation(project(":framework:embed-testkit"))
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.spring.boot.starter.test)
}

configurations.configureEach {
    // F8: Tomcat's WebSocket implementation collides with the Jetty one Deephaven brings.
    exclude(group = "org.apache.tomcat.embed", module = "tomcat-embed-websocket")
}
