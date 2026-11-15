// Spring hosts: a Spring Boot application that runs Deephaven in-process.
//
// P1 gives it its dependency rules and the mandatory JVM flags.
// P5 adds extractBootJar (F7) and the rest of the run and packaging behaviour.

import io.github.cmh.dhc.buildlogic.DeephavenAlignmentExtension
import io.github.cmh.dhc.buildlogic.DeephavenRules
import io.github.cmh.dhc.buildlogic.lib
import io.github.cmh.dhc.buildlogic.libs
import io.github.cmh.dhc.buildlogic.moduleVersions
import io.github.cmh.dhc.buildlogic.version
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("dhc.java-conventions")
    id("org.springframework.boot")
    id("dhc.verify-alignment")
}

// F9: Spring's dependency management would force Spring's older Jetty and Netty over the ones
// Deephaven needs. The plugin is not on the build-logic classpath; this stops a module that
// brings it in by itself.
pluginManager.withPlugin("io.spring.dependency-management") {
    throw GradleException(
        "$path applies io.spring.dependency-management. It must not: the plugin forces Spring's managed " +
            "versions over the ones Deephaven needs (finding F9). dhc.embedded-spring-app already imports " +
            "Spring's BOM as a plain platform, so Spring dependencies need no version without it."
    )
}

// Deephaven BOM enforced (F9), Kafka extension excluded (F2), JVM flags on tests (F3).
DeephavenRules.applyTo(project, bomConfiguration = "implementation")

configurations.configureEach {
    // F8: Tomcat's WebSocket implementation collides with the Jetty one Deephaven brings.
    exclude(group = DeephavenRules.TOMCAT_GROUP, module = DeephavenRules.TOMCAT_WEBSOCKET)
}

dependencies {
    // F9: a plain platform only suggests versions, so where Deephaven needs a higher one
    // (Jetty, Netty) Deephaven's wins.
    implementation(platform(libs.lib("spring-boot-dependencies")))
}

// F9, the other direction. Where Spring manages a higher version than Deephaven uses (Jackson,
// Gson, HttpClient and others), a plain platform would raise it. So every library that Deephaven
// brings is pinned to the version Deephaven alone resolves, whatever Spring suggests. Spring
// decides only the libraries Deephaven does not bring.
val deephavenVersion = libs.version("deephaven")
val alignment = extensions.getByType<DeephavenAlignmentExtension>() // from dhc.verify-alignment
configurations.named("implementation") {
    dependencyConstraints.addAllLater(
        provider {
            moduleVersions(alignment.reference.get())
                // io.deephaven itself is already fixed by the enforced BOM.
                .filterKeys { !it.startsWith("${DeephavenRules.DEEPHAVEN_GROUP}:") }
                .map { (module, pinned) ->
                    project.dependencies.constraints.create(module) {
                        version { strictly(pinned) }
                        because("Deephaven $deephavenVersion alone resolves $pinned (finding F9)")
                    }
                }
        }
    )
}

tasks.named<BootRun>("bootRun") {
    jvmArgs(DeephavenRules.mandatoryJvmFlags) // F3
}
