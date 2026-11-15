// Every Java module: Java 21 toolchain, UTF-8, warnings as errors, JUnit 5, reproducible archives.

import io.github.cmh.dhe.buildlogic.lib
import io.github.cmh.dhe.buildlogic.libs
import io.github.cmh.dhe.buildlogic.version

plugins {
    java
}

val javaVersion = libs.version("java").toInt()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaVersion
    // "processing" is left out: javac reports annotations that no processor claims, which is
    // normal with Dagger on the processor path and would fail every module under -Werror.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

// Same inputs, same bytes: no timestamps, stable entry order.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {
    "testImplementation"(platform(libs.lib("junit-bom")))
    "testImplementation"(libs.lib("junit-jupiter"))
    "testRuntimeOnly"(libs.lib("junit-platform-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
