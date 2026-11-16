plugins {
    id("dhc.embedded-spring-app")
}

description = "Host: Spring Boot running role B."

springBoot {
    mainClass = "io.github.cmh.dhc.apps.dhcb.spring.DhcBSpringApp"
}

dependencies {
    implementation(project(":apps:dhc-b-module"))
    implementation(project(":framework:embed-spring"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
}

// Tests with instance A in a second process (T4), and with this application itself as a process,
// started from the extracted layout and from the fat jar (T5).
val dhcAInstall = project(":apps:dhc-a-java").layout.buildDirectory.dir("install/dhc-a-java")
val extracted = layout.buildDirectory.dir("extracted")
val fatJar = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar").flatMap { it.archiveFile }

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(project())
                implementation(testFixtures(project(":apps:dhc-b-module")))
                implementation(project(":framework:embed-testkit"))
                implementation(libs.spring.boot.starter.test)
            }
            targets.all {
                testTask.configure {
                    description = "Runs this host against instance A in a second process, in-process and as packaged."
                    dependsOn(":apps:dhc-a-java:installDist", "extractBootJar", "bootJar")
                    systemProperty("dhc.a.install", dhcAInstall.get().asFile.absolutePath)
                    systemProperty("dhc.b.extracted", extracted.get().asFile.absolutePath)
                    systemProperty("dhc.b.fatJar", fatJar.get().asFile.absolutePath)
                    shouldRunAfter(tasks.named("test"))
                }
            }
        }
    }
}

// The tests run on the application's own classpath: the same platforms, and the same libraries
// pinned to Deephaven's versions (F9), as the application that is shipped.
configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.implementation.get())
}
configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations.runtimeOnly.get())
}
