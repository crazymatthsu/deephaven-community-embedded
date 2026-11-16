// Spring hosts: a Spring Boot application that runs Deephaven in-process.
//
// Dependency rules (F8, F9), the mandatory JVM flags (F3), and the layout it runs from outside
// Gradle: extractBootJar (F7).

import io.github.cmh.dhc.buildlogic.DeephavenAlignmentExtension
import io.github.cmh.dhc.buildlogic.DeephavenRules
import io.github.cmh.dhc.buildlogic.lib
import io.github.cmh.dhc.buildlogic.libs
import io.github.cmh.dhc.buildlogic.moduleVersions
import io.github.cmh.dhc.buildlogic.version
import org.springframework.boot.gradle.tasks.bundling.BootJar
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

// `bootRun` is the development way to start a host, so it gets the development settings. The
// extracted layout gets none of these and starts with the safe defaults.
tasks.named<BootRun>("bootRun") {
    jvmArgs(DeephavenRules.mandatoryJvmFlags) // F3
    // Anonymous access for development only (decision D6); DHC_AUTH_MODE in the environment wins.
    environment("DHC_AUTH_MODE", providers.environmentVariable("DHC_AUTH_MODE").getOrElse("anonymous"))
    // The instance's own directory (F10) stays inside build/, so `clean` removes it.
    environment(
        "DHC_WORK_DIR",
        providers.environmentVariable("DHC_WORK_DIR").getOrElse(layout.buildDirectory.dir("dhc-work").get().asFile.absolutePath),
    )
}

// F6 and F7: Deephaven cannot run from Spring Boot's fat jar, because javac cannot read a jar
// within a jar. Spring Boot's own tool unpacks the fat jar into a thin application jar, whose
// manifest lists the libraries, and a lib/ directory beside it. That layout is what runs outside
// Gradle and what a container image copies:
//
//     java @build/extracted/jvm.args -jar build/extracted/<app>.jar
//
// jvm.args holds the mandatory JVM flags (F3), one per line, in the form `java @file` reads.
val bootJar = tasks.named<BootJar>("bootJar") {
    // <app>.jar, without a version: the name is written into run commands and container files.
    archiveVersion = ""
}
val extractBootJar = tasks.register<JavaExec>("extractBootJar") {
    group = "build"
    description = "Unpacks the Spring Boot jar into the layout Deephaven can run from: a thin jar, lib/ and jvm.args."
    val fatJar = bootJar.flatMap { it.archiveFile }
    val destination = layout.buildDirectory.dir("extracted")
    val flags = DeephavenRules.mandatoryJvmFlags
    inputs.file(fatJar).withPropertyName("bootJar").withPathSensitivity(PathSensitivity.NONE)
    inputs.property("jvmFlags", flags)
    outputs.dir(destination).withPropertyName("extracted")
    // The same as `java -Djarmode=tools -jar <app>.jar extract`.
    classpath(fatJar)
    mainClass = "org.springframework.boot.loader.launch.JarLauncher"
    systemProperty("jarmode", "tools")
    argumentProviders.add(
        CommandLineArgumentProvider { listOf("extract", "--force", "--destination", destination.get().asFile.absolutePath) }
    )
    doFirst {
        // The tool adds to what is there; a library that is no longer a dependency must not stay.
        project.delete(destination)
    }
    doLast {
        destination.get().file("jvm.args").asFile.writeText(flags.joinToString("\n", postfix = "\n"))
    }
}

tasks.named("assemble") {
    dependsOn(extractBootJar)
}
